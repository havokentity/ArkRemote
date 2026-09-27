package com.havok.arkremote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class PowerStatus { ON, STANDBY, OFFLINE, UNKNOWN }

/**
 * Idempotent power/input control. Every action checks the monitor's reported state first,
 * so "on" never toggles a monitor that is already on (the problem with the IR remotes).
 */
class ArkController(private val store: MonitorStore) {

    private class Seen(val status: PowerStatus, val at: Long)
    private class Expect(val status: PowerStatus, val until: Long)

    private val seen = ConcurrentHashMap<String, Seen>()
    private val misses = ConcurrentHashMap<String, Int>()
    private val expected = ConcurrentHashMap<String, Expect>()

    private fun now() = System.currentTimeMillis()

    /** Live check, retried once so a single dropped packet isn't read as "off". */
    private suspend fun probe(m: Monitor): DeviceInfo? =
        SamsungApi.info(m.ip) ?: SamsungApi.info(m.ip)

    private fun record(m: Monitor, info: DeviceInfo?): PowerStatus {
        val status = when {
            info == null -> PowerStatus.OFFLINE
            info.isOn -> PowerStatus.ON
            else -> PowerStatus.STANDBY
        }
        seen[m.id] = Seen(status, now())
        return status
    }

    /**
     * Status for display. Smooths over transient failures (needs two failed polls before
     * showing "off") and holds the target state while a power change is in flight.
     */
    suspend fun status(m: Monitor): PowerStatus {
        val info = SamsungApi.info(m.ip)
        val actual = if (info == null) {
            val n = (misses[m.id] ?: 0) + 1
            misses[m.id] = n
            if (n < 2) seen[m.id]?.status ?: PowerStatus.UNKNOWN else record(m, null)
        } else {
            misses[m.id] = 0
            record(m, info)
        }
        val exp = expected[m.id] ?: return actual
        val reached = if (exp.status == PowerStatus.ON) actual == PowerStatus.ON else actual != PowerStatus.ON
        if (reached || now() > exp.until) {
            expected.remove(m.id)
            return actual
        }
        return exp.status
    }

    private fun knownOn(m: Monitor) =
        seen[m.id]?.let { it.status == PowerStatus.ON && now() - it.at < 10_000 } == true

    /** Returns true if the monitor had to be woken (i.e. it was not already on). */
    suspend fun powerOn(m: Monitor): Boolean {
        val info = probe(m)
        if (info != null && info.isOn) {
            record(m, info)
            return false
        }
        expected[m.id] = Expect(PowerStatus.ON, now() + 30_000)

        var poked = false
        if (info != null) { // network standby: the remote channel is still up
            runCatching { sendKeys(m, "KEY_POWER") }
            poked = true
        }
        if (m.mac.isNotBlank()) SamsungApi.wakeOnLan(m.mac, m.ip)

        val deadline = now() + 25_000
        var tick = 0
        while (now() < deadline) {
            delay(1000)
            tick++
            val current = SamsungApi.info(m.ip, 1500)
            when {
                current == null -> if (m.mac.isNotBlank() && tick % 3 == 0) SamsungApi.wakeOnLan(m.mac, m.ip)
                current.isOn -> { record(m, current); return true }
                !poked -> { runCatching { sendKeys(m, "KEY_POWER") }; poked = true }
            }
        }
        expected.remove(m.id)
        throw IOException(
            "${m.name} didn't turn on. Check 'Power On with Mobile' is enabled on the monitor" +
                if (m.mac.isBlank()) " and set its MAC address" else ""
        )
    }

    /** Returns true if a power-off was sent. */
    suspend fun powerOff(m: Monitor): Boolean {
        val info = probe(m) ?: return false
        if (!info.isOn) {
            record(m, info)
            return false
        }
        sendKeys(m, "KEY_POWER")
        expected[m.id] = Expect(PowerStatus.STANDBY, now() + 20_000)
        return true
    }

    suspend fun setInput(m: Monitor, input: InputPort) {
        // Fast path: we saw it on moments ago, so skip the power check entirely.
        if (!knownOn(m) && powerOn(m)) delay(5000) // give a cold-booted monitor time to accept keys
        sendKeys(m, input.key)
    }

    suspend fun volume(m: Monitor): Int? = SamsungApi.getVolume(m.ip)
    suspend fun setVolume(m: Monitor, volume: Int) = SamsungApi.setVolume(m.ip, volume)

    /** Sends keys using the freshest stored token, persisting any token the monitor issues. */
    suspend fun sendKeys(m: Monitor, vararg keys: String) {
        val current = store.get(m.id) ?: m
        val token = SamsungApi.sendKeys(current.ip, current.token, keys.toList())
        if (token != null && token != current.token) store.update(m.id) { it.copy(token = token) }
    }

    /** Runs [action] on every monitor in parallel; returns "Name: error" lines for failures. */
    suspend fun forAll(monitors: List<Monitor>, action: suspend (Monitor) -> Unit): List<String> =
        coroutineScope {
            monitors.map { m ->
                async { runCatching { action(m) }.exceptionOrNull()?.let { "${m.name}: ${it.message}" } }
            }.awaitAll().filterNotNull()
        }
}
