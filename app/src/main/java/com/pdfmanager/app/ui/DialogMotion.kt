package com.pdfmanager.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Length of the dialog enter motion. Kept under 200 ms so it never feels slow. */
internal const val DIALOG_ENTER_MS = 180

/** Dialog content starts a little smaller than its final size. */
private const val DIALOG_START_SCALE = 0.92f

/**
 * Light fade and scale-in for a dialog. Pass it as the `modifier` of an AlertDialog.
 * It only changes how the dialog is drawn, so taps and typing work from the first frame.
 * When the system animations are turned off, the dialog appears at once.
 */
@Composable
internal fun Modifier.dialogEnter(): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(DIALOG_ENTER_MS, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        val s = DIALOG_START_SCALE + (1f - DIALOG_START_SCALE) * p
        scaleX = s
        scaleY = s
    }
}
