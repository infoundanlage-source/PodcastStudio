package com.timerflow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Primary,
    primaryContainer = PrimaryContainer,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
)

@Composable
fun TimerFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
