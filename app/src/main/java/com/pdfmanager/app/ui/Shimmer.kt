package com.pdfmanager.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

private const val SHIMMER_PERIOD_MS = 1300L

private val LightBase = Color(0xFFE9E7EF)
private val LightHighlight = Color(0xFFF7F6FA)
private val DarkBase = Color(0xFF2A2930)
private val DarkHighlight = Color(0xFF3A3942)

/**
 * Grey placeholder with a soft light band that sweeps from left to right. Use it instead of a
 * spinner while a thumbnail or page is loading.
 *
 * The sweep position comes from the clock, so every shimmer on the screen moves in step. The
 * position is only read while drawing, so the animation never recomposes anything and many
 * shimmers at once stay cheap. [dark] chooses the dark palette; by default it follows the theme.
 */
@Composable
fun Modifier.shimmer(dark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f): Modifier {
    val base = if (dark) DarkBase else LightBase
    val highlight = if (dark) DarkHighlight else LightHighlight
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { phase = (it % SHIMMER_PERIOD_MS).toFloat() / SHIMMER_PERIOD_MS }
        }
    }
    return this.drawBehind {
        val band = size.width * 0.6f
        val start = -band + (size.width + band) * phase
        drawRect(
            Brush.horizontalGradient(
                colors = listOf(base, highlight, base),
                startX = start,
                endX = start + band
            )
        )
    }
}
