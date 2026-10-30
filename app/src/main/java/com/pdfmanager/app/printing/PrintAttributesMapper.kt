package com.pdfmanager.app.printing

import android.print.PrintAttributes

/**
 * Turns the settings chosen in the app into starting values for the Android print screen.
 * Only options that Android's PrintAttributes really has are mapped: paper size, orientation,
 * color and two-sided printing. The user can still change them on the system print screen, and
 * the printer or its print service may not offer every value.
 */
object PrintAttributesMapper {

    fun build(settings: PrintSettings): PrintAttributes {
        val builder = PrintAttributes.Builder()

        val base = when (settings.paperSizeId) {
            "ISO_A3" -> PrintAttributes.MediaSize.ISO_A3
            "ISO_A5" -> PrintAttributes.MediaSize.ISO_A5
            "NA_LETTER" -> PrintAttributes.MediaSize.NA_LETTER
            "NA_LEGAL" -> PrintAttributes.MediaSize.NA_LEGAL
            else -> PrintAttributes.MediaSize.ISO_A4
        }
        // Auto leaves the choice to the system, which starts with portrait.
        val media = if (settings.orientation == OrientationChoice.LANDSCAPE) base.asLandscape() else base.asPortrait()
        builder.setMediaSize(media)

        // Android has only two color modes; grayscale and black & white both map to monochrome.
        builder.setColorMode(
            when (settings.colorMode) {
                ColorChoice.COLOR -> PrintAttributes.COLOR_MODE_COLOR
                ColorChoice.GRAYSCALE, ColorChoice.MONOCHROME -> PrintAttributes.COLOR_MODE_MONOCHROME
            }
        )

        when (settings.duplexMode) {
            DuplexChoice.DEFAULT -> Unit
            DuplexChoice.ONE_SIDED -> builder.setDuplexMode(PrintAttributes.DUPLEX_MODE_NONE)
            DuplexChoice.LONG_EDGE -> builder.setDuplexMode(PrintAttributes.DUPLEX_MODE_LONG_EDGE)
            DuplexChoice.SHORT_EDGE -> builder.setDuplexMode(PrintAttributes.DUPLEX_MODE_SHORT_EDGE)
        }
        return builder.build()
    }
}
