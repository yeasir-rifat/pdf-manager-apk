package com.pdfmanager.app.printing

/** Paper sizes the Print Properties screen offers, and the one-line summary under the print button. */
object PrintSummary {

    /** Pairs of (id used in [PrintSettings.paperSizeId], name shown to the user). */
    val paperSizes = listOf(
        "ISO_A4" to "A4",
        "ISO_A3" to "A3",
        "ISO_A5" to "A5",
        "NA_LETTER" to "Letter",
        "NA_LEGAL" to "Legal"
    )

    /** Example: "A4 • Color • Both sides • 2 copies • 3 pages". [pages] is the number of pages chosen, or null if unknown. */
    fun of(s: PrintSettings, pages: Int?): String {
        val paper = paperSizes.firstOrNull { it.first == s.paperSizeId }?.second ?: "A4"
        val color = when (s.colorMode) {
            ColorChoice.COLOR -> "Color"
            ColorChoice.GRAYSCALE -> "Grayscale"
            ColorChoice.MONOCHROME -> "Black & white"
        }
        val sides = if (s.manualDuplex == ManualDuplexChoice.ON) "Both sides (manual)" else when (s.duplexMode) {
            DuplexChoice.DEFAULT -> null
            DuplexChoice.ONE_SIDED -> "One side"
            DuplexChoice.LONG_EDGE, DuplexChoice.SHORT_EDGE -> "Both sides"
        }
        val layout = when (s.layout) {
            LayoutChoice.NORMAL -> null
            LayoutChoice.NUP -> "${s.pagesPerSheet} per sheet"
            LayoutChoice.BOOKLET -> "Booklet"
        }
        return listOfNotNull(
            paper, color, sides, layout,
            if (s.copies > 1 && s.manualDuplex == ManualDuplexChoice.OFF) "${s.copies} copies" else null,
            if (s.pageNumbers) "Page numbers" else null,
            pages?.let { if (it == 1) "1 page" else "$it pages" }
        ).joinToString(" \u2022 ")
    }

    /**
     * Line under "Sheets already printed": where printing continues if [done] sheets of this
     * pass came out. [chosenPages] are the 0-based pages being printed. With N-up or booklet the
     * sheets no longer match document pages, so only the sheet number is given.
     */
    fun resumeHint(s: PrintSettings, chosenPages: List<Int>?, pass: DuplexPass, done: Int): String {
        if (chosenPages.isNullOrEmpty() || s.layout != LayoutChoice.NORMAL) return "Continues with sheet ${done + 1}"
        val next = PageOrder.duplexPass(chosenPages.size, pass, s.stackStyle).drop(done).firstOrNull()
            ?: return "Continues with sheet ${done + 1}"
        return if (next == PageOrder.BLANK) "Continues with the blank back sheet" else "Continues from page ${chosenPages[next] + 1}"
    }
}
