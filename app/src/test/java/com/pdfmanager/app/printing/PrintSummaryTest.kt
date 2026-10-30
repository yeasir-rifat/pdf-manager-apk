package com.pdfmanager.app.printing

import org.junit.Assert.assertEquals
import org.junit.Test

class PrintSummaryTest {
    private val dot = " \u2022 "

    @Test fun defaultSettings() =
        assertEquals("A4${dot}Color${dot}12 pages", PrintSummary.of(PrintSettings(), 12))

    @Test fun singlePageWording() =
        assertEquals("A4${dot}Color${dot}1 page", PrintSummary.of(PrintSettings(), 1))

    @Test fun unknownPageCountIsLeftOut() =
        assertEquals("A4${dot}Color", PrintSummary.of(PrintSettings(), null))

    @Test fun letterGrayscaleDoubleSided() {
        val s = PrintSettings(paperSizeId = "NA_LETTER", colorMode = ColorChoice.GRAYSCALE, duplexMode = DuplexChoice.LONG_EDGE)
        assertEquals("Letter${dot}Grayscale${dot}Both sides${dot}3 pages", PrintSummary.of(s, 3))
    }

    @Test fun bothDuplexEdgesSayDoubleSided() {
        assertEquals(
            "A4${dot}Color${dot}Both sides",
            PrintSummary.of(PrintSettings(duplexMode = DuplexChoice.SHORT_EDGE), null)
        )
    }

    @Test fun oneSidedAndBlackAndWhite() {
        val s = PrintSettings(colorMode = ColorChoice.MONOCHROME, duplexMode = DuplexChoice.ONE_SIDED)
        assertEquals("A4${dot}Black & white${dot}One side", PrintSummary.of(s, null))
    }

    @Test fun layoutWatermarkAndPageNumbers() {
        val nup = PrintSettings(layout = LayoutChoice.NUP, pagesPerSheet = 4, pageNumbers = true, copies = 2)
        assertEquals("A4${dot}Color${dot}4 per sheet${dot}2 copies${dot}Page numbers", PrintSummary.of(nup, null))
        assertEquals("A4${dot}Color${dot}Booklet", PrintSummary.of(PrintSettings(layout = LayoutChoice.BOOKLET), null))
    }

    @Test fun manualSidesAreNamed() =
        assertEquals("A4${dot}Color${dot}Both sides (manual)", PrintSummary.of(PrintSettings(manualDuplex = ManualDuplexChoice.ON), null))

    @Test fun unknownPaperFallsBackToA4() =
        assertEquals("A4${dot}Color", PrintSummary.of(PrintSettings(paperSizeId = "SOMETHING_ELSE"), null))

    @Test fun everyOfferedPaperHasAName() {
        assertEquals(listOf("A4", "A3", "A5", "Letter", "Legal"), PrintSummary.paperSizes.map { it.second })
    }

    @Test fun resumeHintNamesTheNextPage() {
        val pages = (0 until 100).toList()
        val s = PrintSettings(manualDuplex = ManualDuplexChoice.ON)
        assertEquals("Continues from page 76", PrintSummary.resumeHint(s, pages, DuplexPass.FIRST, 12))
        assertEquals("Continues from page 25", PrintSummary.resumeHint(s, pages, DuplexPass.SECOND, 12))
        assertEquals("Continues with sheet 13", PrintSummary.resumeHint(s.copy(layout = LayoutChoice.NUP), pages, DuplexPass.FIRST, 12))
    }
}
