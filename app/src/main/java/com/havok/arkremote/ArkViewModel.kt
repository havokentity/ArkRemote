package com.havok.arkremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UiState(
    val monitors: List<Monitor> = emptyList(),
    val status: Map<String, PowerStatus> = emptyMap(),
    /** Monitor id -> what it's currently doing ("Turning on…"). */
    val busy: Map<String, String> = emptyMap(),
    val presets: List<Preset> = emptyList(),
    val volume: Map<String, Int> = emptyMap(),
    val scanning: Boolean = false,
    val scanResults: List<FoundDevice>? = null,
)

class ArkViewModel(application: Application) : AndroidViewModel(application) {
    private val ark = application as ArkApplication
    private val store = ark.store
    private val controller = ark.controller

    private val _state = MutableStateFlow(UiState(monitors = store.load(), presets = store.presets()))
    val state = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val refreshLock = Mutex()
    @Volatile private var fastPollUntil = 0L
    private val volumeJobs = mutableMapOf<String, Job>()

    private fun reload() = _state.update { it.copy(monitors = store.load(), presets = store.presets()) }
    private fun say(msg: String) { _messages.tryEmit(msg) }
    private fun setBusy(ids: Collection<String>, label: String?) = _state.update { s ->
        s.copy(busy = if (label == null) s.busy - ids.toSet() else s.busy + ids.associateWith { label })
    }

    /** Poll status while the screen is visible: every 4 s, or every 1 s just after an action. */
    suspend fun pollWhileVisible() {
        while (true) {
            refreshNow()
            delay(if (System.currentTimeMillis() < fastPollUntil) 1000 else 4000)
        }
    }

    private fun speedUpPolling() { fastPollUntil = System.currentTimeMillis() + 25_000 }

    /** One refresh at a time, so a slow old result can't overwrite a newer one. */
    private suspend fun refreshNow() {
        if (refreshLock.isLocked) return
        refreshLock.withLock {
            val monitors = store.load()
            val statuses = coroutineScope {
                monitors.map { m -> async { m.id to controller.status(m) } }.awaitAll().toMap()
            }
            _state.update { it.copy(monitors = monitors, status = statuses) }
        }
    }

    private fun refresh() = viewModelScope.launch { refreshNow() }

    // --- Group actions -------------------------------------------------------------------

    fun allOn() = group("Turning on…") { controller.powerOn(it) }
    fun allOff() = group("Turning off…") { controller.powerOff(it) }

    fun applyPreset(index: Int) {
        val preset = store.presets()[index]
        group("Switching to ${preset.name}…") { m ->
            preset.inputFor(index, m)?.let { controller.setInput(m, it) }
        }
    }

    private fun group(label: String, action: suspend (Monitor) -> Unit) = viewModelScope.launch {
        val targets = store.load().filter { it.inGroup }
        if (targets.isEmpty()) return@launch say("No monitors are ticked for the group")
        val ids = targets.map { it.id }
        setBusy(ids, label)
        speedUpPolling()
        val errors = controller.forAll(targets, action)
        setBusy(ids, null)
        errors.forEach(::say)
        reload()
        refresh()
    }

    // --- Single-monitor actions ----------------------------------------------------------

    fun powerOn(id: String) = single(id, "Turning on…") { controller.powerOn(it) }
    fun powerOff(id: String) = single(id, "Turning off…") { controller.powerOff(it) }
    fun input(id: String, input: InputPort) = single(id, "Switching to ${input.label}…") { controller.setInput(it, input) }
    fun key(id: String, key: String) = single(id, null) { controller.sendKeys(it, key) }

    fun pair(id: String) = single(id, "Accept the prompt on the monitor…") {
        controller.sendKeys(it)
        say("${it.name} paired")
    }

    private fun single(id: String, label: String?, action: suspend (Monitor) -> Unit) = viewModelScope.launch {
        val m = store.get(id) ?: return@launch
        if (label != null) {
            setBusy(listOf(id), label)
            speedUpPolling()
        }
        runCatching { action(m) }.onFailure { say("${m.name}: ${it.message}") }
        if (label != null) setBusy(listOf(id), null)
        reload()
        if (label != null) refresh()
    }

    // --- Volume --------------------------------------------------------------------------

    fun loadVolume(id: String) = viewModelScope.launch {
        val m = store.get(id) ?: return@launch
        controller.volume(m)?.let { v -> _state.update { it.copy(volume = it.volume + (id to v)) } }
    }

    /** Called continuously while dragging; sends the latest value, at most every ~150 ms. */
    fun setVolume(id: String, volume: Int) {
        _state.update { it.copy(volume = it.volume + (id to volume)) }
        if (volumeJobs[id]?.isActive == true) return
        volumeJobs[id] = viewModelScope.launch {
            val m = store.get(id) ?: return@launch
            var sent = -1
            while (true) {
                val target = _state.value.volume[id] ?: return@launch
                if (target == sent) break
                runCatching { controller.setVolume(m, target) }
                    .onFailure { say("${m.name}: ${it.message}"); return@launch }
                sent = target
                delay(150)
            }
        }
    }

    fun mute(id: String) {
        key(id, "KEY_MUTE")
    }

    // --- Editing -------------------------------------------------------------------------

    fun toggleGroup(id: String) {
        store.update(id) { it.copy(inGroup = !it.inGroup) }
        reload()
    }

    fun saveMonitor(existingId: String?, name: String, ip: String, mac: String) = viewModelScope.launch {
        val cleanIp = ip.trim()
        var monitor = (existingId?.let(store::get) ?: Monitor(name = "", ip = "", mac = ""))
            .copy(name = name.trim(), ip = cleanIp, mac = mac.trim().uppercase())
        if (existingId == null || monitor.mac.isBlank() || monitor.model.isBlank()) {
            SamsungApi.info(cleanIp)?.let { info ->
                monitor = monitor.copy(
                    name = monitor.name.ifBlank { info.name },
                    mac = monitor.mac.ifBlank { info.mac?.uppercase() ?: "" },
                    model = info.model,
                )
            } ?: say("Couldn't reach $cleanIp — saved anyway")
        }
        if (monitor.name.isBlank()) monitor = monitor.copy(name = "Ark ${cleanIp.substringAfterLast('.')}")
        val list = store.load()
        store.save(if (existingId == null) list + monitor else list.map { if (it.id == existingId) monitor else it })
        reload()
        refresh()
    }

    fun delete(id: String) {
        store.save(store.load().filterNot { it.id == id })
        reload()
    }

    fun savePreset(index: Int, preset: Preset) {
        store.savePreset(index, preset)
        reload()
    }

    fun scan() = viewModelScope.launch {
        _state.update { it.copy(scanning = true, scanResults = null) }
        val found = runCatching { NetworkScanner.scan(getApplication()) }.getOrDefault(emptyList())
        _state.update { it.copy(scanning = false, scanResults = found) }
    }

    fun addFound(device: FoundDevice) = saveMonitor(null, device.info.name, device.ip, device.info.mac ?: "")

    fun closeScan() = _state.update { it.copy(scanResults = null, scanning = false) }
}
