package com.havok.arkremote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private class Scan(val prefix: String, val at: Long, val found: List<FoundDevice>)
    private val scanLock = Mutex()
    private var lastScan: Scan? = null

    private fun now() = System.currentTimeMillis()

    /** Live check, retried once so a single dropped packet isn't read as "off". */
    private suspend fun probe(m: Monitor): DeviceInfo? =
        SamsungApi.info(m.ip) ?: SamsungApi.info(m.ip)

    private fun normalizeMac(mac: String) = mac.filter { it.isLetterOrDigit() }.lowercase()

    /** One subnet scan shared by every monitor that needs it, reused for a minute. */
    private suspend fun scanShared(prefix: String): List<FoundDevice> = scanLock.withLock {
        lastScan?.takeIf { it.prefix == prefix && now() - it.at < 60_000 }?.found
            ?: NetworkScanner.scanPrefix(prefix).also { lastScan = Scan(prefix, now(), it) }
    }

    /**
     * Finds a monitor whose DHCP address changed by scanning its /24 for its MAC, and saves
     * the new IP. Returns the updated monitor, or null if it wasn't found at a new address.
     * Only ever called from a user action, never from background status polls: a monitor
     * that is simply off looks the same as one that moved, and polling would scan forever.
     */
    suspend fun relocate(m: Monitor): Monitor? {
        if (m.mac.isBlank()) return null
        val found = scanShared(m.ip.substringBeforeLast('.'))
            .firstOrNull { it.info.mac != null && normalizeMac(it.info.mac) == normalizeMac(m.mac) }
            ?: return null
        if (found.ip == m.ip) return null
        store.update(m.id) { it.copy(ip = found.ip) }
        return store.get(m.id)
    }

    /** Probes the stored IP, falling back to a MAC search if the monitor has moved. */
    private suspend fun reach(m: Monitor): Pair<Monitor, DeviceInfo>? {
        probe(m)?.let { return m to it }
        val moved = relocate(m) ?: return null
        return probe(moved)?.let { moved to it }
    }

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
            // Backfill details for monitors added while they weren't reachable.
            if ((m.model.isBlank() && info.model.isNotBlank()) || (m.mac.isBlank() && info.mac != null)) {
                store.update(m.id) {
                    it.copy(model = it.model.ifBlank { info.model }, mac = it.mac.ifBlank { info.mac?.uppercase() ?: "" })
                }
            }
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
    suspend fun powerOn(monitor: Monitor): Boolean {
        var m = monitor
        val info = probe(m) // no subnet search yet: wake it first, look for it only if it stays silent
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
                current == null -> {
                    if (m.mac.isNotBlank() && tick % 3 == 0) SamsungApi.wakeOnLan(m.mac, m.ip)
                    // Woke up but not at the old address? Look for it once.
                    if (tick == 8) relocate(m)?.let { m = it }
                }
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
    suspend fun powerOff(monitor: Monitor): Boolean {
        val (m, info) = reach(monitor) ?: return false
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

    suspend fun volume(m: Monitor): Int? = SamsungApi.getVolume((store.get(m.id) ?: m).ip)
    suspend fun setVolume(m: Monitor, volume: Int) = SamsungApi.setVolume((store.get(m.id) ?: m).ip, volume)

    /** Sends keys using the freshest stored token, persisting any token the monitor issues. */
    suspend fun sendKeys(m: Monitor, vararg keys: String) {
        var current = store.get(m.id) ?: m
        val token = try {
            SamsungApi.sendKeys(current.ip, current.token, keys.toList())
        } catch (e: UnauthorizedException) {
            throw e
        } catch (e: IOException) {
            current = relocate(current) ?: throw e
            SamsungApi.sendKeys(current.ip, current.token, keys.toList())
        }
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
