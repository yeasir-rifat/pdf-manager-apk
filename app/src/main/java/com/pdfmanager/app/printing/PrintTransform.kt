package com.pdfmanager.app.printing

/** Rules for the optional pre-processing of Step 9 (N-up, booklet, watermark, page numbers). */
object PrintTransform {

    /** Pages-per-sheet values the N-up layout supports (same as the Pages per sheet tool). */
    val nupChoices = listOf(2, 4, 6, 9)

    const val MAX_WATERMARK_LENGTH = 40
    const val MAX_COPIES = 99

    /**
     * Watermarks use the built-in Helvetica font, which can only draw plain English letters,
     * digits and common symbols. Returns an error message, or null when the text is fine
     * (blank text is fine: it means no watermark).
     */
    fun watermarkError(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.length > MAX_WATERMARK_LENGTH) return "Use at most $MAX_WATERMARK_LENGTH characters"
        if (t.any { it.code < 0x20 || it.code > 0x7E }) return "Use English letters, numbers and symbols only"
        return null
    }

    /**
     * One-sided printing always goes last page first, so the printed stack comes out in reading
     * order and can be stapled as it is. Two-sided jobs keep their own order.
     */
    fun withAutoOrder(s: PrintSettings): PrintSettings =
        s.copy(reverseOrder = s.manualDuplex == ManualDuplexChoice.OFF && s.duplexMode == DuplexChoice.ONE_SIDED)

    /** Copies are made by repeating the sheets (collated); not used with guided two-sided printing. */
    fun copiesApply(s: PrintSettings): Boolean = s.copies > 1 && s.manualDuplex == ManualDuplexChoice.OFF

    /** True when the PDF must be rebuilt before it is handed to the print system. */
    fun needsPreprocessing(s: PrintSettings): Boolean =
        s.layout != LayoutChoice.NORMAL || s.watermarkText.isNotBlank() || s.pageNumbers ||
            PageOrder.needsReorder(s.reverseOrder, s.manualDuplex) || copiesApply(s)

    /** The N-up and booklet sheets are A4. Other paper sizes are scaled by the print system. */
    fun sheetsAreA4Only(s: PrintSettings): Boolean = s.layout != LayoutChoice.NORMAL && s.paperSizeId != "ISO_A4"

    /** 2 and 6 per sheet, and booklets, are made on landscape sheets. */
    fun usesLandscapeSheets(s: PrintSettings): Boolean = when (s.layout) {
        LayoutChoice.NORMAL -> false
        LayoutChoice.BOOKLET -> true
        LayoutChoice.NUP -> s.pagesPerSheet == 2 || s.pagesPerSheet == 6
    }

    /**
     * Starting values for the Android print screen that match the rebuilt PDF: landscape sheets
     * are printed as landscape, and a booklet is flipped on the short edge. Choices the user
     * made themselves (anything other than Auto / Printer default) are kept.
     */
    fun adjustForOutput(s: PrintSettings): PrintSettings {
        var r = s
        if (usesLandscapeSheets(r) && r.orientation == OrientationChoice.AUTO) {
            r = r.copy(orientation = OrientationChoice.LANDSCAPE)
        }
        if (r.layout == LayoutChoice.BOOKLET && (r.duplexMode == DuplexChoice.DEFAULT || r.duplexMode == DuplexChoice.LONG_EDGE)) {
            r = r.copy(duplexMode = DuplexChoice.SHORT_EDGE)
        }
        // Manual two-sided printing prints one side at a time, so the printer must not duplex.
        if (r.manualDuplex != ManualDuplexChoice.OFF) {
            r = r.copy(duplexMode = DuplexChoice.ONE_SIDED)
        }
        return r
    }
}
