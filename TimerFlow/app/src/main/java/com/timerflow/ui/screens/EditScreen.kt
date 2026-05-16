package com.timerflow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.timerflow.data.model.TimerStep
import com.timerflow.viewmodel.SequenceViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    sequenceId: Long?,
    viewModel: SequenceViewModel,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("Neue Sequenz") }
    var isLoop by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf(listOf<TimerStep>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingStep by remember { mutableStateOf<Pair<Int, TimerStep>?>(null) }

    LaunchedEffect(sequenceId) {
        if (sequenceId != null && sequenceId != 0L) {
            viewModel.loadSequence(sequenceId)?.let { sws ->
                name = sws.sequence.name
                isLoop = sws.sequence.isLoop
                steps = sws.steps.sortedBy { it.position }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (sequenceId == null || sequenceId == 0L) "Neue Sequenz" else "Bearbeiten") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Zurück") } },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            viewModel.saveSequence(sequenceId, name, isLoop, steps)
                            onBack()
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Speichern")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "Schritt hinzufügen")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name der Sequenz") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Loop-Modus", style = MaterialTheme.typography.bodyLarge)
                        Text("Sequenz automatisch wiederholen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isLoop, onCheckedChange = { isLoop = it })
                }
            }
            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text("Schritte", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (steps.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Noch keine Schritte — tippe + unten", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(steps, key = { _, s -> s.id.takeIf { it != 0L } ?: s.position }) { index, step ->
                StepRow(
                    step = step,
                    index = index,
                    total = steps.size,
                    onEdit = { editingStep = Pair(index, step) },
                    onDelete = { steps = steps.toMutableList().also { it.removeAt(index) } },
                    onMoveUp = {
                        if (index > 0) {
                            val list = steps.toMutableList()
                            val tmp = list[index - 1]; list[index - 1] = list[index]; list[index] = tmp
                            steps = list
                        }
                    },
                    onMoveDown = {
                        if (index < steps.size - 1) {
                            val list = steps.toMutableList()
                            val tmp = list[index + 1]; list[index + 1] = list[index]; list[index] = tmp
                            steps = list
                        }
                    }
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showAddDialog) {
        StepDialog(
            initial = null,
            onConfirm = { step ->
                steps = steps + step.copy(position = steps.size)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    editingStep?.let { (index, step) ->
        StepDialog(
            initial = step,
            onConfirm = { updated ->
                steps = steps.toMutableList().also { it[index] = updated.copy(position = index) }
                editingStep = null
            },
            onDismiss = { editingStep = null }
        )
    }
}

@Composable
private fun StepRow(
    step: TimerStep,
    index: Int,
    total: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val accentColor = if (step.isBreak) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 4.dp).background(accentColor.copy(alpha = 0.08f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(4.dp).height(56.dp).background(accentColor))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(step.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "${formatTotal(step.durationSeconds)} · ${if (step.isBreak) "Pause" else "Arbeit"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onMoveUp, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, null) }
            IconButton(onClick = onMoveDown, enabled = index < total - 1) { Icon(Icons.Default.KeyboardArrowDown, null) }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, null) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun StepDialog(
    initial: TimerStep?,
    onConfirm: (TimerStep) -> Unit,
    onDismiss: () -> Unit
) {
    var stepName by remember { mutableStateOf(initial?.name ?: "") }
    var minutes by remember { mutableStateOf(((initial?.durationSeconds ?: 60) / 60).toString()) }
    var seconds by remember { mutableStateOf(((initial?.durationSeconds ?: 60) % 60).toString()) }
    var isBreak by remember { mutableStateOf(initial?.isBreak ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Schritt hinzufügen" else "Schritt bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = stepName,
                    onValueChange = { stepName = it },
                    label = { Text("Name") },
                    placeholder = { Text(if (isBreak) "Pause" else "Runde 1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { if (it.length <= 3) minutes = it.filter { c -> c.isDigit() } },
                        label = { Text("Min") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { if (it.length <= 2) seconds = it.filter { c -> c.isDigit() } },
                        label = { Text("Sek") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pause-Schritt", Modifier.weight(1f))
                    Switch(checked = isBreak, onCheckedChange = { isBreak = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val totalSec = (minutes.toIntOrNull() ?: 0) * 60 + (seconds.toIntOrNull() ?: 0)
                if (totalSec > 0) {
                    val finalName = stepName.ifBlank { if (isBreak) "Pause" else "Timer" }
                    onConfirm(TimerStep(
                        id = initial?.id ?: 0,
                        sequenceId = initial?.sequenceId ?: 0,
                        position = initial?.position ?: 0,
                        name = finalName,
                        durationSeconds = totalSec,
                        isBreak = isBreak
                    ))
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
