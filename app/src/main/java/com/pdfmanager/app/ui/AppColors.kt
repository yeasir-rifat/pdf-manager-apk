package com.pdfmanager.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Category accent families used by the tool icons. The order matches the color lists in [AppColors]. */
enum class ToolAccent { Coral, Green, Blue, Amber, Purple, Rose }

/**
 * Fixed design tokens of the redesigned screens (tool cards, bottom bar, dividers).
 * They do NOT follow the wallpaper (Material You) colors, so the lavender surfaces and the
 * pink selected-tab pill look the same on every phone. Light and dark sets are chosen in [PdfManagerTheme].
 */
@Immutable
class AppColors(
    /** Tool card and list-row surface. */
    val toolCard: Color,
    /** Thin border around every card. Thickness is [AppDimens.hairline]. */
    val cardBorder: Color,
    /** Bottom navigation panel surface. */
    val navBar: Color,
    /** Soft rounded highlight behind the selected tab icon. */
    val navPill: Color,
    /** Icon and label of the selected tab. */
    val navActive: Color,
    /** Icon and label of the other tabs. */
    val navInactive: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    /** Icon color per [ToolAccent], in enum order. */
    private val glyphs: List<Color>,
    /** Light theme: solid accent tile with a white icon. Dark theme: softly tinted tile with an accent icon. */
    private val solidTiles: Boolean
) {
    /** The same tokens with the selected-tab pill and its icon taken from the chosen accent theme. */
    fun withNav(navPill: Color, navActive: Color) = AppColors(
        toolCard, cardBorder, navBar, navPill, navActive, navInactive,
        textPrimary, textSecondary, divider, glyphs, solidTiles
    )

    /** Pure black dark mode: cards are a touch lighter than the black page and keep a visible border. */
    fun withPureBlackCards() = AppColors(
        toolCard = Color(0xFF0F0F12), cardBorder = Color(0xFF2A2A30), navBar = navBar,
        navPill = navPill, navActive = navActive, navInactive = navInactive,
        textPrimary = textPrimary, textSecondary = textSecondary, divider = divider,
        glyphs = glyphs, solidTiles = solidTiles
    )

    fun glyph(accent: ToolAccent): Color = glyphs[accent.ordinal]
    fun tile(accent: ToolAccent): Color = if (solidTiles) glyph(accent) else glyph(accent).copy(alpha = 0.22f)
    fun onTile(accent: ToolAccent): Color = if (solidTiles) Color.White else glyph(accent)
}

internal val LightAppColors = AppColors(
    // Warm neutral grey instead of lavender, so cards sit quietly next to the crimson brand color.
    // Text on it: primary 262626 is about 14:1, secondary 6B6A72 about 4.9:1 (both above 4.5:1).
    toolCard = Color(0xFFF7F5F4),
    cardBorder = Color(0xFFE2DEDC),
    navBar = Color(0xFFF7F5F4),
    navPill = Color(0xFFFAD0D3),
    navActive = Color(0xFF262626),
    navInactive = Color(0xFF5C5B62),
    textPrimary = Color(0xFF262626),
    textSecondary = Color(0xFF6B6A72),
    divider = Color(0xFFE8E5E3),
    //            Coral               Green               Blue                Amber               Purple              Rose
    glyphs = listOf(
        Color(0xFFE8583A), Color(0xFF3F8F2E), Color(0xFF3B5BDB),
        Color(0xFFB26A00), Color(0xFF8E4EC6), Color(0xFFC2255C)
    ),
    solidTiles = true
)

internal val DarkAppColors = AppColors(
    toolCard = Color(0xFF25242A),
    cardBorder = Color(0xFF3F3E46),
    navBar = Color(0xFF1F1E24),
    navPill = Color(0xFF5A2A30),
    navActive = Color(0xFFFFE9E7),
    navInactive = Color(0xFFB0AEB8),
    textPrimary = Color(0xFFE6E4EC),
    textSecondary = Color(0xFFB4B2BC),
    divider = Color(0xFF34333A),
    glyphs = listOf(
        Color(0xFFFF8A70), Color(0xFF7CCB68), Color(0xFF8FA6FF),
        Color(0xFFF2B84B), Color(0xFFC79BEF), Color(0xFFF78FB3)
    ),
    solidTiles = false
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/** Access to the redesign tokens: `AppTheme.colors.toolCard`. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}

/** One accent theme in one brightness: the Material colors it changes plus the selected-tab pill. */
@Immutable
class AccentColors(
    val primary: Color, val onPrimary: Color,
    val primaryContainer: Color, val onPrimaryContainer: Color,
    val secondaryContainer: Color, val onSecondaryContainer: Color,
    val navPill: Color, val navActive: Color
)

/** A choice in Settings: a name and a light and a dark palette. */
@Immutable
class AccentTheme(@StringRes val nameRes: Int, val light: AccentColors, val dark: AccentColors) {
    /** Color of the round swatch in Settings. */
    fun swatch(dark: Boolean): Color = if (dark) this.dark.primary else light.primary
}

/**
 * The accent themes. Index 0 is the original crimson look, so a fresh install is unchanged.
 * Light palettes keep white text on the primary color above 4.5:1; dark palettes use a light primary
 * with dark text on it. Selected-tab icons use dark text on the light pills and light text on the dark pills.
 */
object AccentThemes {
    val all: List<AccentTheme> = listOf(
        AccentTheme(
            com.pdfmanager.app.R.string.accent_default,
            light = AccentColors(
                Color(0xFFB5121B), Color.White, Color(0xFFFFE2DD), Color(0xFF410004),
                Color(0xFFFBE5E1), Color(0xFF2A1612), Color(0xFFFAD0D3), Color(0xFF262626)
            ),
            dark = AccentColors(
                Color(0xFFFF7A70), Color(0xFF450005), Color(0xFF6B0D13), Color(0xFFFFDAD6),
                Color(0xFF3F2C28), Color(0xFFF6E1DB), Color(0xFF5A2A30), Color(0xFFFFE9E7)
            )
        ),
        AccentTheme(
            com.pdfmanager.app.R.string.accent_blue,
            light = AccentColors(
                Color(0xFF1A5FC2), Color.White, Color(0xFFD6E4FF), Color(0xFF001B3E),
                Color(0xFFDCE6F7), Color(0xFF0F1B2D), Color(0xFFC9DBFA), Color(0xFF262626)
            ),
            dark = AccentColors(
                Color(0xFF9CC2FF), Color(0xFF002F66), Color(0xFF0F4A94), Color(0xFFD6E4FF),
                Color(0xFF2B3445), Color(0xFFDCE6F7), Color(0xFF244A7A), Color(0xFFE6EFFF)
            )
        ),
        AccentTheme(
            com.pdfmanager.app.R.string.accent_green,
            light = AccentColors(
                Color(0xFF1B7A3A), Color.White, Color(0xFFCDEFD3), Color(0xFF00210B),
                Color(0xFFDDEFE0), Color(0xFF14201A), Color(0xFFBDE8C6), Color(0xFF262626)
            ),
            dark = AccentColors(
                Color(0xFF7CD68F), Color(0xFF00391A), Color(0xFF0E5A2B), Color(0xFFCDEFD3),
                Color(0xFF2A3A2E), Color(0xFFDDEFE0), Color(0xFF25563A), Color(0xFFE4F7E8)
            )
        ),
        AccentTheme(
            com.pdfmanager.app.R.string.accent_purple,
            light = AccentColors(
                Color(0xFF6A3DB8), Color.White, Color(0xFFE8DDFF), Color(0xFF22005C),
                Color(0xFFEBE3F7), Color(0xFF1F1530), Color(0xFFDCCBFA), Color(0xFF262626)
            ),
            dark = AccentColors(
                Color(0xFFC9B0FF), Color(0xFF2F0B73), Color(0xFF4D2A94), Color(0xFFE8DDFF),
                Color(0xFF38304A), Color(0xFFEBE3F7), Color(0xFF4A3680), Color(0xFFF0E8FF)
            )
        ),
        AccentTheme(
            com.pdfmanager.app.R.string.accent_rose,
            light = AccentColors(
                Color(0xFFC2255C), Color.White, Color(0xFFFFD9E4), Color(0xFF3E001D),
                Color(0xFFF9E1E8), Color(0xFF2B141B), Color(0xFFF9C8D7), Color(0xFF262626)
            ),
            dark = AccentColors(
                Color(0xFFFF8FB0), Color(0xFF5F0A2B), Color(0xFF8A1744), Color(0xFFFFD9E4),
                Color(0xFF4A2F38), Color(0xFFF9E1E8), Color(0xFF6B2A44), Color(0xFFFFE6EE)
            )
        )
    )

    fun get(index: Int): AccentTheme = all.getOrElse(index) { all[0] }
}

/** The chosen accent theme, kept in the same "ui" preferences as the theme mode. */
object AccentPrefs {
    private const val KEY = "accent"

    fun get(context: android.content.Context): Int =
        context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).getInt(KEY, 0)

    fun set(context: android.content.Context, index: Int) {
        context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit().putInt(KEY, index).apply()
    }
}
