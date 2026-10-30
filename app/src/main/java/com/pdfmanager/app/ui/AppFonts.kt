package com.pdfmanager.app.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.os.Build
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale

/**
 * Lato for English and Kalpurush for Bengali, loaded from assets/fonts.
 *
 * On Android 10+ the two fonts are chained, so one text line can mix Lato letters and Kalpurush
 * Bengali letters. On Android 8-9 the whole app uses Kalpurush when the phone language is Bengali,
 * otherwise Lato. Any missing file is skipped and the system font is used instead.
 */
class AppFonts(val regular: FontFamily, val bold: FontFamily) {

    /** Gives every text style the right font. The weight is already part of the font file. */
    fun apply(t: Typography): Typography {
        fun TextStyle.f(): TextStyle {
            val heavy = fontWeight != null && fontWeight!! >= FontWeight.Medium
            return copy(fontFamily = if (heavy) bold else regular, fontWeight = FontWeight.Normal)
        }
        return t.copy(
            displayLarge = t.displayLarge.f(), displayMedium = t.displayMedium.f(), displaySmall = t.displaySmall.f(),
            headlineLarge = t.headlineLarge.f(), headlineMedium = t.headlineMedium.f(), headlineSmall = t.headlineSmall.f(),
            titleLarge = t.titleLarge.f(), titleMedium = t.titleMedium.f(), titleSmall = t.titleSmall.f(),
            bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
            labelLarge = t.labelLarge.f(), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f()
        )
    }

    companion object {
        private const val DIR = "fonts"
        private const val LATO_REGULAR = "Lato-Regular.ttf"
        private const val LATO_BOLD = "Lato-Bold.ttf"
        private const val KALPURUSH = "Kalpurush.ttf"

        /** Returns null when none of the font files are present. */
        fun load(context: Context): AppFonts? = runCatching {
            val assets = context.assets
            val present = assets.list(DIR)?.toSet().orEmpty()
            val hasLatoR = LATO_REGULAR in present
            val hasLatoB = LATO_BOLD in present
            val hasKal = KALPURUSH in present
            if (!hasLatoR && !hasLatoB && !hasKal) return null

            val bengaliPhone = Locale.getDefault().language == "bn"
            fun family(latoFile: String, hasLato: Boolean): FontFamily {
                val primary = when {
                    hasLato && !(bengaliPhone && hasKal && Build.VERSION.SDK_INT < 29) -> latoFile
                    hasKal -> KALPURUSH
                    else -> return FontFamily.Default
                }
                val typeface: Typeface? =
                    if (Build.VERSION.SDK_INT >= 29 && primary != KALPURUSH && hasKal) chained(assets, primary)
                    else runCatching { Typeface.createFromAsset(assets, "$DIR/$primary") }.getOrNull()
                return typeface?.let { FontFamily(it) } ?: FontFamily.Default
            }
            val regular = family(LATO_REGULAR, hasLatoR)
            // If only one Lato weight is present, use it for both
            val bold = family(if (hasLatoB) LATO_BOLD else LATO_REGULAR, hasLatoB || hasLatoR)
            AppFonts(regular, bold)
        }.getOrNull()

        private fun chained(assets: AssetManager, latoFile: String): Typeface? = runCatching {
            fun fam(file: String) = android.graphics.fonts.FontFamily.Builder(
                android.graphics.fonts.Font.Builder(assets, "$DIR/$file").build()
            ).build()
            Typeface.CustomFallbackBuilder(fam(latoFile))
                .addCustomFallback(fam(KALPURUSH))
                .setSystemFallback("sans-serif")
                .build()
        }.getOrNull()
    }
}
