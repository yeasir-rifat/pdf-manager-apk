package com.pdfmanager.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/** The "Vibration feedback" switch in Settings. On by default; kept in the same "ui" preferences as the other view choices. */
object HapticPrefs {
    private const val KEY = "vibration"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/**
 * Every vibration in the app goes through here, so the Settings switch turns all of them off at once.
 * The switch is read on each call, so a change in Settings applies right away.
 */
@Stable
class AppHaptics(private val feedback: HapticFeedback, private val context: Context) {
    /** Light tap: bookmark added, jump to a page. */
    fun tick() = perform(HapticFeedbackType.TextHandleMove)

    /** Firmer tap: a delete was confirmed, or a long press started selecting. */
    fun confirm() = perform(HapticFeedbackType.LongPress)

    private fun perform(type: HapticFeedbackType) {
        if (HapticPrefs.enabled(context)) feedback.performHapticFeedback(type)
    }
}

@Composable
fun rememberAppHaptics(): AppHaptics {
    val feedback = LocalHapticFeedback.current
    val context = LocalContext.current
    return remember(feedback, context) { AppHaptics(feedback, context) }
}
