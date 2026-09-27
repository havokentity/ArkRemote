package com.havok.arkremote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.IOException

enum class PowerStatus { ON, STANDBY, OFFLINE, UNKNOWN }

/**
 * Idempotent power/input control. Every action checks the monitor's reported state first,
 * so "on" never toggles a monitor that is already on (the problem with the IR remotes).
 */
class ArkController(private val store: MonitorStore) {

    suspend fun status(m: Monitor): PowerStatus {
        val info = SamsungApi.info(m.ip) ?: return PowerStatus.OFFLINE
        return if (info.isOn) PowerStatus.ON else PowerStatus.STANDBY
    }

    /** Returns true if the monitor had to be woken (i.e. it was not already on). */
    suspend fun powerOn(m: Monitor): Boolean {
        val info = SamsungApi.info(m.ip)
        if (info != null && info.isOn) return false

        var poked = false
        if (info != null) { // network standby: the remote channel is still up
            runCatching { sendKeys(m, "KEY_POWER") }
            poked = true
        }
        if (m.mac.isNotBlank()) SamsungApi.wakeOnLan(m.mac, m.ip)

        val deadline = System.currentTimeMillis() + 25_000
        var tick = 0
        while (System.currentTimeMillis() < deadline) {
            delay(1000)
            tick++
            val now = SamsungApi.info(m.ip, 1000)
            when {
                now == null -> if (m.mac.isNotBlank() && tick % 3 == 0) SamsungApi.wakeOnLan(m.mac, m.ip)
                now.isOn -> return true
                !poked -> { runCatching { sendKeys(m, "KEY_POWER") }; poked = true }
            }
        }
        throw IOException(
            "${m.name} didn't turn on. Check 'Power On with Mobile' is enabled on the monitor" +
                if (m.mac.isBlank()) " and set its MAC address" else ""
        )
    }

    /** Returns true if a power-off was sent. */
    suspend fun powerOff(m: Monitor): Boolean {
        val info = SamsungApi.info(m.ip) ?: return false
        if (!info.isOn) return false
        sendKeys(m, "KEY_POWER")
        return true
    }

    suspend fun setInput(m: Monitor, key: String) {
        if (powerOn(m)) delay(5000) // give a cold-booted monitor time to accept keys
        sendKeys(m, key)
    }

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
