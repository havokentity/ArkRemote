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
)

/** The four HDMI inputs, in button order. Labels are user-editable (e.g. "Work PC"). */
val INPUT_KEYS = listOf("KEY_HDMI1", "KEY_HDMI2", "KEY_HDMI3", "KEY_HDMI4")

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

    fun inputLabels(): List<String> =
        INPUT_KEYS.mapIndexed { i, key -> prefs.getString("label_$key", null) ?: "HDMI ${i + 1}" }

    fun setInputLabel(index: Int, label: String) {
        prefs.edit().putString("label_${INPUT_KEYS[index]}", label.ifBlank { "HDMI ${index + 1}" }).apply()
    }
}
