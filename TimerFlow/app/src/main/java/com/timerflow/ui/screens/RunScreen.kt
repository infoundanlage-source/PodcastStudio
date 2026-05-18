package com.timerflow.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timerflow.data.model.TimerStep
import com.timerflow.service.TimerService
import com.timerflow.ui.theme.BreakColor
import com.timerflow.ui.theme.FinishedColor
import com.timerflow.ui.theme.WorkColor
import com.timerflow.viewmodel.RunViewModel
import com.timerflow.viewmodel.SequenceViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(
    sequenceId: Long,
    seqViewModel: SequenceViewModel,
    runViewModel: RunViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timerState by runViewModel.serviceState.collectAsState()

    // Load sequence and start timer when screen opens
    LaunchedEffect(sequenceId) {
        val sws = seqViewModel.loadSequence(sequenceId) ?: return@LaunchedEffect
        runViewModel.bind(context)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Permission handled via Accompanist or manual flow; skipped for brevity
        }

        // Start service
        val intent = Intent(context, TimerService::class.java)
        context.startForegroundService(intent)
        runViewModel.bind(context)

        // Give service time to bind, then start
        kotlinx.coroutines.delay(300)
        runViewModel.getService()?.start(sws.steps, sws.sequence.isLoop)
    }

    DisposableEffect(Unit) {
        onDispose { runViewModel.unbind(context) }
    }

    val service = runViewModel.getService()
    val liveState = service?.state?.collectAsState()?.value ?: timerState

    val accentColor = when {
        liveState.isFinished -> FinishedColor
        liveState.currentStep?.isBreak == true -> BreakColor
        else -> WorkColor
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Läuft") },
                navigationIcon = {
                    IconButton(onClick = {
                        service?.stop()
                        onBack()
                    }) { Icon(Icons.Default.Close, "Beenden") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Countdown ring + time
            Box(
                modifier = Modifier.padding(32.dp).size(240.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgress(progress = liveState.progress, color = accentColor)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatSeconds(liveState.remainingSeconds),
                        fontSize = 56.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    liveState.currentStep?.let { step ->
                        Text(
                            text = step.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (liveState.isFinished) {
                        Text("Fertig!", style = MaterialTheme.typography.titleMedium, color = FinishedColor)
                    }
                }
            }

            // Step progress indicator
            Text(
                text = "${liveState.currentIndex + 1} / ${liveState.steps.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // Controls
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        service?.stop()
                        onBack()
                    }
                ) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop")
                }
                Button(
                    onClick = { service?.togglePause() },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    modifier = Modifier.size(72.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        if (liveState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                }
                if (liveState.isLoop) {
                    Icon(Icons.Default.Loop, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()

            // Step list
            val listState = rememberLazyListState()
            LaunchedEffect(liveState.currentIndex) {
                listState.animateScrollToItem(liveState.currentIndex)
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(liveState.steps) { idx, step ->
                    StepListItem(step = step, isActive = idx == liveState.currentIndex, isDone = idx < liveState.currentIndex)
                }
            }
        }
    }
}

@Composable
private fun CircularProgress(progress: Float, color: Color) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(800, easing = LinearEasing),
        label = "progress"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = 16.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = color.copy(alpha = 0.15f),
            startAngle = -90f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = -90f, sweepAngle = 360f * animatedProgress, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun StepListItem(step: TimerStep, isActive: Boolean, isDone: Boolean) {
    val accentColor = if (step.isBreak) BreakColor else WorkColor
    val alpha = when {
        isActive -> 1f
        isDone -> 0.4f
        else -> 0.65f
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) accentColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp, 40.dp).background(accentColor.copy(alpha = alpha)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(step.name, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                Text(formatSeconds(step.durationSeconds), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
            if (isDone) Icon(Icons.Default.CheckCircle, null, tint = WorkColor.copy(alpha = 0.6f))
            if (isActive) Icon(Icons.Default.PlayArrow, null, tint = accentColor)
        }
    }
}

private fun formatSeconds(s: Int): String {
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
