package com.pdfmanager.app.ui

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
    fun glyph(accent: ToolAccent): Color = glyphs[accent.ordinal]
    fun tile(accent: ToolAccent): Color = if (solidTiles) glyph(accent) else glyph(accent).copy(alpha = 0.22f)
    fun onTile(accent: ToolAccent): Color = if (solidTiles) Color.White else glyph(accent)
}

internal val LightAppColors = AppColors(
    toolCard = Color(0xFFF5F4F9),
    navBar = Color(0xFFF5F4F9),
    navPill = Color(0xFFFAD0D3),
    navActive = Color(0xFF262626),
    navInactive = Color(0xFF5C5B62),
    textPrimary = Color(0xFF262626),
    textSecondary = Color(0xFF6B6A72),
    divider = Color(0xFFE6E4EC),
    //            Coral               Green               Blue                Amber               Purple              Rose
    glyphs = listOf(
        Color(0xFFE8583A), Color(0xFF3F8F2E), Color(0xFF3B5BDB),
        Color(0xFFB26A00), Color(0xFF8E4EC6), Color(0xFFC2255C)
    ),
    solidTiles = true
)

internal val DarkAppColors = AppColors(
    toolCard = Color(0xFF25242A),
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
