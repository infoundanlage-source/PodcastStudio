package com.timerflow.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timerflow.data.model.SequenceWithSteps
import com.timerflow.data.model.TimerSequence
import com.timerflow.viewmodel.SequenceViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: SequenceViewModel,
    onEdit: (Long?) -> Unit,
    onRun: (Long) -> Unit
) {
    val sequences by viewModel.sequences.collectAsState()
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<TimerSequence?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("TimerFlow", fontWeight = FontWeight.Bold) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(null) }) {
                Icon(Icons.Default.Add, contentDescription = "Neue Sequenz")
            }
        }
    ) { padding ->
        if (sequences.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Noch keine Timer-Sequenzen", style = MaterialTheme.typography.bodyLarge)
                    Text("Tippe + um eine zu erstellen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sequences, key = { it.sequence.id }) { sws ->
                    SequenceCard(
                        sws = sws,
                        onEdit = { onEdit(sws.sequence.id) },
                        onRun = { onRun(sws.sequence.id) },
                        onDelete = { deleteTarget = sws.sequence }
                    )
                }
            }
        }
    }

    deleteTarget?.let { seq ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Löschen?") },
            text = { Text("\"${seq.name}\" wird dauerhaft gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { viewModel.delete(seq) }
                    deleteTarget = null
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun SequenceCard(
    sws: SequenceWithSteps,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(sws.sequence.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (sws.sequence.isLoop) {
                            Icon(Icons.Default.Loop, contentDescription = "Loop", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${sws.steps.size} Schritte · ${formatTotal(sws.totalSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Bearbeiten") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error) }
            }
            if (sws.steps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sws.steps.take(5).forEach { step ->
                        StepChip(step.name, step.isBreak)
                    }
                    if (sws.steps.size > 5) {
                        AssistChip(onClick = {}, label = { Text("+${sws.steps.size - 5}") })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Starten")
            }
        }
    }
}

@Composable
private fun StepChip(name: String, isBreak: Boolean) {
    val color = if (isBreak) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    SuggestionChip(
        onClick = {},
        label = { Text(name, fontSize = 11.sp) },
        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = color.copy(alpha = 0.15f))
    )
}

private fun formatTotal(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}min" else if (m > 0) "${m}min ${s}s" else "${s}s"
}
