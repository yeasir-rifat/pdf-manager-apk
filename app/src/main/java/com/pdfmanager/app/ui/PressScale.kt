package com.pdfmanager.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Makes a clickable card shrink a little while it is pressed and spring back when released.
 *
 * Pass the same [interactionSource] to the card's click handler. The scale is applied in a
 * graphics layer, so it does not trigger a new layout pass. Inside a scrolling list the system
 * delays the pressed state briefly and cancels it when the list starts to scroll, so fast
 * scrolling does not shrink cards by mistake.
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.97f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
