package com.havok.arkremote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Monitor(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    val mac: String,
    val model: String = "",
    val token: String? = null,
    /** Included in the "All" group actions. */
    val inGroup: Boolean = true,
) {
    val inputs: List<InputPort> get() = inputsFor(model)
}

data class InputPort(val id: String, val label: String, val detail: String, val key: String)

/** Physical inputs on each model's One Connect box, in port order. */
fun inputsFor(model: String): List<InputPort> = when {
    // Odyssey Ark 2nd gen (G97NC): HDMI 1 is eARC / HDMI 2.0, then two HDMI 2.1, then DisplayPort 1.4.
    model.startsWith("LS55CG") -> listOf(
        InputPort("HDMI1", "HDMI 1", "eARC · 2.0", "KEY_HDMI1"),
        InputPort("HDMI2", "HDMI 2", "2.1", "KEY_HDMI2"),
        InputPort("HDMI3", "HDMI 3", "2.1", "KEY_HDMI3"),
        // No dedicated DisplayPort key is documented; the DP input sits in the 4th source slot.
        InputPort("DP", "DP", "1.4", "KEY_HDMI4"),
    )
    // Odyssey Ark 1st gen (G97NB): four HDMI 2.1, eARC on HDMI 3.
    model.startsWith("LS55BG") -> listOf(
        InputPort("HDMI1", "HDMI 1", "2.1", "KEY_HDMI1"),
        InputPort("HDMI2", "HDMI 2", "2.1", "KEY_HDMI2"),
        InputPort("HDMI3", "HDMI 3", "eARC · 2.1", "KEY_HDMI3"),
        InputPort("HDMI4", "HDMI 4", "2.1", "KEY_HDMI4"),
    )
    else -> (1..4).map { InputPort("HDMI$it", "HDMI $it", "", "KEY_HDMI$it") }
}

/**
 * A "switch all" button, e.g. "Work PC". [inputs] maps monitor id -> input id ("" = leave that
 * monitor alone). Monitors missing from the map use their input at the preset's position.
 */
data class Preset(val name: String, val inputs: Map<String, String> = emptyMap())

const val PRESET_COUNT = 4

class MonitorStore(context: Context) {
    private val prefs = context.getSharedPreferences("ark_remote", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<Monitor> {
        val raw = prefs.getString("monitors", null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Monitor(
                id = o.getString("id"),
                name = o.getString("name"),
                ip = o.getString("ip"),
                mac = o.optString("mac"),
                model = o.optString("model"),
                token = o.optString("token").ifBlank { null },
                inGroup = o.optBoolean("inGroup", true),
            )
        }
    }

    @Synchronized
    fun save(monitors: List<Monitor>) {
        val arr = JSONArray()
        monitors.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("ip", m.ip)
                    .put("mac", m.mac)
                    .put("model", m.model)
                    .put("token", m.token ?: "")
                    .put("inGroup", m.inGroup)
            )
        }
        prefs.edit().putString("monitors", arr.toString()).apply()
    }

    fun get(id: String): Monitor? = load().firstOrNull { it.id == id }

    @Synchronized
    fun update(id: String, transform: (Monitor) -> Monitor) {
        save(load().map { if (it.id == id) transform(it) else it })
    }

    fun presets(): List<Preset> = (0 until PRESET_COUNT).map { i ->
        val raw = prefs.getString("preset_$i", null)
        if (raw == null) {
            // Carry over names given to the old fixed HDMI buttons.
            Preset(prefs.getString("label_KEY_HDMI${i + 1}", null) ?: "Input ${i + 1}")
        } else {
            val o = JSONObject(raw)
            val map = o.optJSONObject("inputs") ?: JSONObject()
            Preset(o.getString("name"), map.keys().asSequence().associateWith { map.getString(it) })
        }
    }

    fun savePreset(index: Int, preset: Preset) {
        val o = JSONObject()
            .put("name", preset.name.ifBlank { "Input ${index + 1}" })
            .put("inputs", JSONObject(preset.inputs))
        prefs.edit().putString("preset_$index", o.toString()).apply()
    }
}

/** The input [preset] (at [index]) selects on [monitor], or null to leave it alone. */
fun Preset.inputFor(index: Int, monitor: Monitor): InputPort? {
    val chosen = inputs[monitor.id] ?: return monitor.inputs.getOrNull(index)
    return monitor.inputs.firstOrNull { it.id == chosen }
}
