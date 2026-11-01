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

/** The "Pure black dark mode" switch in Settings. It only has an effect while the dark theme is on. */
object PureBlackPrefs {
    private const val KEY = "pureBlack"

    fun get(context: android.content.Context): Boolean =
        context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: android.content.Context, on: Boolean) {
        context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
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
fun PdfManagerTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = false,
    accent: Int = 0,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val wallpaperColors = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // While wallpaper colors are on, the accent choice waits: the original palette is used for the fixed tokens
    val palette = remember(darkTheme, accent, wallpaperColors) {
        val theme = AccentThemes.get(if (wallpaperColors) 0 else accent)
        if (darkTheme) theme.dark else theme.light
    }
    val staticScheme = remember(darkTheme, palette) {
        (if (darkTheme) DarkColors else LightColors).copy(
            primary = palette.primary, onPrimary = palette.onPrimary,
            primaryContainer = palette.primaryContainer, onPrimaryContainer = palette.onPrimaryContainer,
            secondaryContainer = palette.secondaryContainer, onSecondaryContainer = palette.onSecondaryContainer
        )
    }
    val blackPage = darkTheme && pureBlack
    val appColors = remember(darkTheme, palette, blackPage) {
        val base = (if (darkTheme) DarkAppColors else LightAppColors).withNav(palette.navPill, palette.navActive)
        if (blackPage) base.withPureBlackCards() else base
    }
    val baseScheme = if (wallpaperColors) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else staticScheme
    // Pure black: the page and plain surfaces are fully black, cards are #0F0F12. Text colors are left as they are, they are bright on black.
    val scheme = if (blackPage) {
        baseScheme.copy(
            background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
            // Same card color as the tool cards, so every card on the black page matches
            surfaceContainerLow = Color(0xFF0F0F12)
        )
    } else baseScheme
    val typography = remember { AppFonts.load(context)?.apply(AppTypography) ?: AppTypography }
    MaterialTheme(
        // With wallpaper colors on (Android 12+) the scheme comes from the wallpaper
        colorScheme = scheme,
        typography = typography,
        shapes = AppShapes,
        content = {
            // Fixed redesign tokens (cards, bottom bar...): they ignore the wallpaper colors on purpose
            CompositionLocalProvider(LocalAppColors provides appColors) {
                content()
            }
        }
    )
}
