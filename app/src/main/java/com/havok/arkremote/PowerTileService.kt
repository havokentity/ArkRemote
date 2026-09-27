package com.havok.arkremote

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Quick Settings tile: if any group monitor is on, turn the group off; otherwise turn it on. */
class PowerTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as ArkApplication

    override fun onStartListening() {
        scope.launch { render(anyOn(), null) }
    }

    override fun onClick() {
        val tile = qsTile ?: return
        if (tile.state == Tile.STATE_UNAVAILABLE) return
        val group = app.store.load().filter { it.inGroup }
        if (group.isEmpty()) return
        scope.launch {
            val turnOff = anyOn()
            render(!turnOff, if (turnOff) "Turning off…" else "Turning on…")
            val errors = app.controller.forAll(group) {
                if (turnOff) app.controller.powerOff(it) else app.controller.powerOn(it)
            }
            render(anyOn(), if (errors.isEmpty()) null else "${errors.size} failed")
        }
    }

    private suspend fun anyOn(): Boolean {
        val group = app.store.load().filter { it.inGroup }
        return coroutineScope {
            group.map { m -> async { app.controller.status(m) == PowerStatus.ON } }.awaitAll().any { it }
        }
    }

    private fun render(on: Boolean, subtitle: String?) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle ?: if (on) "On" else "Off"
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
