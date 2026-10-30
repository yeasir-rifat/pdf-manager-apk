package com.pdfmanager.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

/** Category accents used for icon badges. They work on both light and dark surfaces. */
object Accent {
    val Indigo = Color(0xFF4C6EF5)
    val Teal = Color(0xFF12B886)
    val Violet = Color(0xFF7950F2)
    val Orange = Color(0xFFF76707)
    val Rose = Color(0xFFE64980)
    val Red = Color(0xFFE5484D)
    val Green = Color(0xFF2F9E44)
    val Amber = Color(0xFFF59F00)
}

// Brand crimson accent on neutral surfaces
private val LightColors = lightColorScheme(
    primary = Color(0xFFB5121B), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2DD), onPrimaryContainer = Color(0xFF410004),
    secondary = Color(0xFF00639B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE5E1), onSecondaryContainer = Color(0xFF2A1612),
    tertiary = Color(0xFF146C2E), onTertiary = Color.White,
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE1E3E1), onSurfaceVariant = Color(0xFF444746),
    outline = Color(0xFF747775), outlineVariant = Color(0xFFC4C7C5),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF303030), inverseOnSurface = Color(0xFFF2F2F2),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFEFEFEF), surfaceContainerHigh = Color(0xFFE9E9E9),
    surfaceContainerHighest = Color(0xFFE3E3E3)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF7A70), onPrimary = Color(0xFF450005),
    primaryContainer = Color(0xFF6B0D13), onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF7FCFFF), onSecondary = Color(0xFF003355),
    secondaryContainer = Color(0xFF3F2C28), onSecondaryContainer = Color(0xFFF6E1DB),
    tertiary = Color(0xFF6DD58C), onTertiary = Color(0xFF0A3818),
    background = Color(0xFF131314), onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF1E1F20), onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF444746), onSurfaceVariant = Color(0xFFC4C7C5),
    outline = Color(0xFF8E918F), outlineVariant = Color(0xFF444746),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFE3E3E3), inverseOnSurface = Color(0xFF303030),
    surfaceContainerLowest = Color(0xFF0E0E0E), surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF282A2C), surfaceContainerHigh = Color(0xFF303234),
    surfaceContainerHighest = Color(0xFF3A3C3E)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PdfManagerTheme(darkTheme: Boolean, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val typography = remember { AppFonts.load(context)?.apply(AppTypography) ?: AppTypography }
    MaterialTheme(
        colorScheme = when {
            // Material You: colors taken from the wallpaper (Android 12+)
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkColors
            else -> LightColors
        },
        typography = typography,
        shapes = AppShapes,
        content = {
            // Fixed redesign tokens (cards, bottom bar...): they ignore the wallpaper colors on purpose
            CompositionLocalProvider(LocalAppColors provides (if (darkTheme) DarkAppColors else LightAppColors)) {
                content()
            }
        }
    )
}
