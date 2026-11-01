package com.pdfmanager.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Spacing, shape and border tokens shared by all screens.
 * Later design steps use these names instead of repeating raw numbers.
 */
object AppDimens {
    val space4 = 4.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp
    val space24 = 24.dp

    /** Corner radius of every card. */
    val cardRadius = 20.dp

    /** Thickness of the thin border around cards. The color is `AppTheme.colors.cardBorder`. */
    val hairline = 0.5.dp
}

/**
 * The four text styles of the app: Title, Subtitle, Body and Caption.
 *
 * They start from the Material styles, so they keep the app font (Inter and Kalpurush) and follow
 * the theme. Every line height is at least 1.4 times the font size, so Bengali marks above and
 * below the line are never clipped.
 */
object AppText {
    val title: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp)

    val subtitle: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 22.sp)

    val body: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)

    val caption: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp)
}
