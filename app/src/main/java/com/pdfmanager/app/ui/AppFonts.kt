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
 * Inter for the English UI, loaded from assets/fonts. The app is English only, so Inter is always the
 * main font.
 *
 * On Android 10+ Kalpurush is chained behind Inter, so Bengali letters (for example in a Bengali file
 * name) still show correctly on a line that also has Inter letters. On Android 8-9 the system's own
 * fallback fonts cover those letters. Any missing file is skipped and the system font is used instead.
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
        private const val KALPURUSH = "Kalpurush.ttf"

        /** Returns null when none of the font files are present. */
        fun load(context: Context): AppFonts? = runCatching {
            val assets = context.assets
            val present = assets.list(DIR)?.toSet().orEmpty()
            val hasInterR = INTER_REGULAR in present
            val hasInterB = INTER_BOLD in present
            val hasKal = KALPURUSH in present
            if (!hasInterR && !hasInterB && !hasKal) return null

            fun family(interFile: String, hasInter: Boolean): FontFamily {
                val primary = when {
                    hasInter -> interFile
                    hasKal -> KALPURUSH
                    else -> return FontFamily.Default
                }
                val typeface: Typeface? =
                    if (Build.VERSION.SDK_INT >= 29 && primary != KALPURUSH && hasKal) chained(assets, primary)
                    else runCatching { Typeface.createFromAsset(assets, "$DIR/$primary") }.getOrNull()
                return typeface?.let { FontFamily(it) } ?: FontFamily.Default
            }
            val regular = family(INTER_REGULAR, hasInterR)
            // If only one Inter weight is present, use it for both
            val bold = family(if (hasInterB) INTER_BOLD else INTER_REGULAR, hasInterB || hasInterR)
            AppFonts(regular, bold)
        }.getOrNull()

        // Only called on Android 10+ (see the SDK_INT check in load); the annotation tells Lint so
        @RequiresApi(29)
        private fun chained(assets: AssetManager, interFile: String): Typeface? = runCatching {
            fun fam(file: String) = android.graphics.fonts.FontFamily.Builder(
                android.graphics.fonts.Font.Builder(assets, "$DIR/$file").build()
            ).build()
            Typeface.CustomFallbackBuilder(fam(interFile))
                .addCustomFallback(fam(KALPURUSH))
                .setSystemFallback("sans-serif")
                .build()
        }.getOrNull()
    }
}
