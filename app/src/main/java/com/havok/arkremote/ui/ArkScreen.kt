package com.havok.arkremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.havok.arkremote.ArkViewModel
import com.havok.arkremote.FoundDevice
import com.havok.arkremote.Monitor
import com.havok.arkremote.PowerStatus
import kotlinx.coroutines.delay

private sealed interface DialogState {
    data class Edit(val monitor: Monitor?) : DialogState
    data class ConfirmDelete(val monitor: Monitor) : DialogState
    data class RenameInput(val index: Int, val current: String) : DialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArkScreen(vm: ArkViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DialogState?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh()
                delay(5000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ark Remote", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = vm::scan) { Icon(Icons.Default.Search, "Scan network") }
                    IconButton(onClick = { dialog = DialogState.Edit(null) }) { Icon(Icons.Default.Add, "Add monitor") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                GroupCard(
                    groupSize = state.monitors.count { it.inGroup },
                    busy = state.monitors.any { it.inGroup && it.id in state.busy },
                    inputLabels = state.inputLabels,
                    onAllOn = vm::allOn,
                    onAllOff = vm::allOff,
                    onInput = vm::allInput,
                    onRenameInput = { i -> dialog = DialogState.RenameInput(i, state.inputLabels[i]) },
                )
            }
            if (state.monitors.isEmpty()) item { EmptyHint(onScan = vm::scan) }
            items(state.monitors, key = { it.id }) { m ->
                MonitorCard(
                    monitor = m,
                    status = state.status[m.id] ?: PowerStatus.UNKNOWN,
                    busy = state.busy[m.id],
                    inputLabels = state.inputLabels,
                    onToggleGroup = { vm.toggleGroup(m.id) },
                    onPowerOn = { vm.powerOn(m.id) },
                    onPowerOff = { vm.powerOff(m.id) },
                    onInput = { i -> vm.input(m.id, i) },
                    onKey = { k -> vm.key(m.id, k) },
                    onPair = { vm.pair(m.id) },
                    onEdit = { dialog = DialogState.Edit(m) },
                    onDelete = { dialog = DialogState.ConfirmDelete(m) },
                )
            }
        }
    }

    when (val d = dialog) {
        is DialogState.Edit -> MonitorDialog(
            initial = d.monitor,
            onDismiss = { dialog = null },
            onSave = { name, ip, mac -> vm.saveMonitor(d.monitor?.id, name, ip, mac); dialog = null },
        )
        is DialogState.ConfirmDelete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Remove ${d.monitor.name}?") },
            confirmButton = { TextButton(onClick = { vm.delete(d.monitor.id); dialog = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is DialogState.RenameInput -> TextPromptDialog(
            title = "Rename HDMI ${d.index + 1}",
            hint = "e.g. Gaming PC",
            initial = d.current,
            onDismiss = { dialog = null },
            onConfirm = { vm.renameInput(d.index, it); dialog = null },
        )
        null -> Unit
    }

    if (state.scanning || state.scanResults != null) {
        ScanDialog(
            scanning = state.scanning,
            results = state.scanResults.orEmpty(),
            knownIps = state.monitors.map { it.ip }.toSet(),
            onAdd = vm::addFound,
            onDismiss = vm::closeScan,
        )
    }
}

@Composable
private fun GroupCard(
    groupSize: Int,
    busy: Boolean,
    inputLabels: List<String>,
    onAllOn: () -> Unit,
    onAllOff: () -> Unit,
    onInput: (Int) -> Unit,
    onRenameInput: (Int) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            Text("All monitors", style = MaterialTheme.typography.titleMedium)
            Text(
                "$groupSize in group",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAllOn, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Default.PowerSettingsNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text("All on")
                }
                OutlinedButton(onClick = onAllOff, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Default.PowerSettingsNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text("All off")
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Input, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Switch all to · long-press to rename",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            InputRow(inputLabels, onInput, onRenameInput)
            if (busy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun InputRow(labels: List<String>, onClick: (Int) -> Unit, onLongClick: ((Int) -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            PressableTile(
                label = label,
                onClick = { onClick(i) },
                onLongClick = onLongClick?.let { cb -> { cb(i) } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PressableTile(label: String, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.height(52.dp),
    ) {
        Box(
            Modifier.fillMaxSize().combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun MonitorCard(
    monitor: Monitor,
    status: PowerStatus,
    busy: String?,
    inputLabels: List<String>,
    onToggleGroup: () -> Unit,
    onPowerOn: () -> Unit,
    onPowerOff: () -> Unit,
    onInput: (Int) -> Unit,
    onKey: (String) -> Unit,
    onPair: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable(monitor.id) { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = monitor.inGroup, onCheckedChange = { onToggleGroup() })
                Column(Modifier.weight(1f)) {
                    Text(monitor.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(monitor.model, monitor.ip).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(status, busy != null)
            }
            if (busy != null) {
                Text(
                    busy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }
            if (monitor.token == null) {
                Text(
                    "Not paired yet — tap Pair and accept the prompt on this monitor",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onPowerOn, modifier = Modifier.weight(1f)) { Text("On") }
                OutlinedButton(onClick = onPowerOff, modifier = Modifier.weight(1f)) { Text("Off") }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Remote")
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 8.dp, top = 12.dp)) {
                    InputRow(inputLabels, onInput)
                    Spacer(Modifier.height(16.dp))
                    RemotePad(onKey)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = onPair) { Text("Pair") }
                        TextButton(onClick = onEdit) { Text("Edit") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: PowerStatus, working: Boolean) {
    val (label, color) = when (status) {
        PowerStatus.ON -> "On" to Color(0xFF5BD68A)
        PowerStatus.STANDBY -> "Standby" to Color(0xFFE6B450)
        PowerStatus.OFFLINE -> "Off" to MaterialTheme.colorScheme.outline
        PowerStatus.UNKNOWN -> "…" to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (working) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        } else {
            Box(Modifier.size(10.dp).background(color, CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RemotePad(onKey: (String) -> Unit) {
    @Composable
    fun K(icon: ImageVector, desc: String, key: String) =
        FilledTonalIconButton(onClick = { onKey(key) }, modifier = Modifier.size(56.dp)) { Icon(icon, desc) }

    @Composable
    fun T(text: String, key: String) =
        FilledTonalButton(onClick = { onKey(key) }, modifier = Modifier.size(width = 56.dp, height = 56.dp), contentPadding = PaddingValues(0.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium)
        }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T("Source", "KEY_SOURCE"); K(Icons.Default.KeyboardArrowUp, "Up", "KEY_UP"); K(Icons.Default.Menu, "Menu", "KEY_MENU")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            K(Icons.Default.KeyboardArrowLeft, "Left", "KEY_LEFT"); T("OK", "KEY_ENTER"); K(Icons.Default.KeyboardArrowRight, "Right", "KEY_RIGHT")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            K(Icons.AutoMirrored.Filled.ArrowBack, "Back", "KEY_RETURN"); K(Icons.Default.KeyboardArrowDown, "Down", "KEY_DOWN"); K(Icons.Default.Home, "Home", "KEY_HOME")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            K(Icons.AutoMirrored.Filled.VolumeDown, "Volume down", "KEY_VOLDOWN"); K(Icons.AutoMirrored.Filled.VolumeOff, "Mute", "KEY_MUTE"); K(Icons.AutoMirrored.Filled.VolumeUp, "Volume up", "KEY_VOLUP")
        }
    }
}

@Composable
private fun EmptyHint(onScan: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set up your Arks", style = MaterialTheme.typography.titleMedium)
            Text(
                "On each monitor, enable:\n" +
                    "• Settings › General › Network › Expert Settings › Power On with Mobile\n" +
                    "• Settings › General › Network › Expert Settings › IP Remote (if listed)\n\n" +
                    "Then make sure your phone is on the same Wi-Fi network and scan. " +
                    "After adding each monitor, tap Pair and accept the prompt on screen.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onScan) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("Scan network")
            }
        }
    }
}

@Composable
private fun MonitorDialog(initial: Monitor?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var ip by remember { mutableStateOf(initial?.ip ?: "") }
    var mac by remember { mutableStateOf(initial?.mac ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add monitor" else "Edit monitor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Left Ark)") }, singleLine = true)
                OutlinedTextField(ip, { ip = it }, label = { Text("IP address") }, singleLine = true)
                OutlinedTextField(mac, { mac = it }, label = { Text("MAC (auto-filled if blank)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, ip, mac) }, enabled = ip.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextPromptDialog(title: String, hint: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, placeholder = { Text(hint) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScanDialog(
    scanning: Boolean,
    results: List<FoundDevice>,
    knownIps: Set<String>,
    onAdd: (FoundDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Samsung displays on your network") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    scanning -> {
                        Text("Scanning…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    results.isEmpty() -> Text(
                        "Nothing found. Make sure the monitors are on (or have Power On with Mobile enabled) " +
                            "and your phone is on the same network. You can also add one by IP."
                    )
                    else -> results.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.info.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${d.info.model} · ${d.ip}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (d.ip in knownIps) Text("Added", style = MaterialTheme.typography.labelMedium)
                            else TextButton(onClick = { onAdd(d) }) { Text("Add") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
