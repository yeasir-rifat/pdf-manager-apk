package com.pdfmanager.app.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Inter for English letters and Noto Serif Bengali for Bengali letters, both loaded from assets/fonts.
 *
 * Regular and Bold each get their own chain, so a bold heading is bold in both scripts:
 *   Regular: Inter-Regular  -> NotoSerifBengali-Regular
 *   Bold:    Inter-Bold     -> NotoSerifBengali-Bold
 *
 * On Android 10+ the two fonts are chained, so one line can hold English and Bengali letters and each
 * letter comes from the right font. On Android 8-9 Typeface cannot be chained, so Inter is used and the
 * system's own Bengali font covers the Bengali letters.
 *
 * Any missing file is skipped: Kalpurush.ttf is still accepted as the Bengali font if the Noto files are
 * not there, and with no files at all the system font is used.
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
        private const val INTER_REGULAR = "Inter-Regular.ttf"
        private const val INTER_BOLD = "Inter-Bold.ttf"
        private const val BN_REGULAR = "NotoSerifBengali-Regular.ttf"
        private const val BN_BOLD = "NotoSerifBengali-Bold.ttf"
        private const val BN_OLD = "Kalpurush.ttf" // only used when the Noto files are missing

        /** Returns null when none of the font files are present. */
        fun load(context: Context): AppFonts? = runCatching {
            val assets = context.assets
            val present = assets.list(DIR)?.toSet().orEmpty()
            fun firstPresent(vararg files: String): String? = files.firstOrNull { it in present }

            val latinR = firstPresent(INTER_REGULAR, INTER_BOLD)
            val latinB = firstPresent(INTER_BOLD, INTER_REGULAR)
            val bengaliR = firstPresent(BN_REGULAR, BN_BOLD, BN_OLD)
            val bengaliB = firstPresent(BN_BOLD, BN_REGULAR, BN_OLD)
            if (latinR == null && bengaliR == null) return null

            fun family(latin: String?, bengali: String?): FontFamily {
                val primary = latin ?: bengali ?: return FontFamily.Default
                val typeface: Typeface? =
                    if (Build.VERSION.SDK_INT >= 29 && latin != null && bengali != null) chained(assets, latin, bengali)
                    else runCatching { Typeface.createFromAsset(assets, "$DIR/$primary") }.getOrNull()
                return typeface?.let { FontFamily(it) } ?: FontFamily.Default
            }
            AppFonts(regular = family(latinR, bengaliR), bold = family(latinB, bengaliB))
        }.getOrNull()

        // Only called on Android 10+ (see the SDK_INT check in load); the annotation tells Lint so
        @RequiresApi(29)
        private fun chained(assets: AssetManager, latinFile: String, bengaliFile: String): Typeface? = runCatching {
            fun fam(file: String) = android.graphics.fonts.FontFamily.Builder(
                android.graphics.fonts.Font.Builder(assets, "$DIR/$file").build()
            ).build()
            Typeface.CustomFallbackBuilder(fam(latinFile))
                .addCustomFallback(fam(bengaliFile))
                .setSystemFallback("sans-serif")
                .build()
        }.getOrNull()
    }
}
