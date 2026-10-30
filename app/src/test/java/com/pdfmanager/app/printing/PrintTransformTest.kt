package com.pdfmanager.app.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintTransformTest {

    @Test fun blankWatermarkIsFine() {
        assertNull(PrintTransform.watermarkError(""))
        assertNull(PrintTransform.watermarkError("   "))
    }

    @Test fun plainEnglishWatermarkIsFine() = assertNull(PrintTransform.watermarkError("DRAFT - Do not copy 2026"))

    @Test fun banglaWatermarkIsAccepted() = assertNull(PrintTransform.watermarkError("\u0996\u09B8\u09A1\u09BC\u09BE"))

    @Test fun lineBreakInWatermarkIsRejected() = assertNotNull(PrintTransform.watermarkError("A\nB"))

    @Test fun tooLongWatermarkIsRejected() {
        assertNull(PrintTransform.watermarkError("A".repeat(PrintTransform.MAX_WATERMARK_LENGTH)))
        assertNotNull(PrintTransform.watermarkError("A".repeat(PrintTransform.MAX_WATERMARK_LENGTH + 1)))
    }

    @Test fun plainSettingsNeedNoPreprocessing() = assertFalse(PrintTransform.needsPreprocessing(PrintSettings()))

    @Test fun eachOptionTriggersPreprocessing() {
        assertTrue(PrintTransform.needsPreprocessing(PrintSettings(layout = LayoutChoice.NUP)))
        assertTrue(PrintTransform.needsPreprocessing(PrintSettings(layout = LayoutChoice.BOOKLET)))
        assertTrue(PrintTransform.needsPreprocessing(PrintSettings(watermarkText = "DRAFT")))
        assertTrue(PrintTransform.needsPreprocessing(PrintSettings(pageNumbers = true)))
        assertFalse(PrintTransform.needsPreprocessing(PrintSettings(watermarkText = "  ")))
    }

    @Test fun landscapeSheetsFor2And6PerSheetAndBooklet() {
        assertTrue(PrintTransform.usesLandscapeSheets(PrintSettings(layout = LayoutChoice.NUP, pagesPerSheet = 2)))
        assertTrue(PrintTransform.usesLandscapeSheets(PrintSettings(layout = LayoutChoice.NUP, pagesPerSheet = 6)))
        assertFalse(PrintTransform.usesLandscapeSheets(PrintSettings(layout = LayoutChoice.NUP, pagesPerSheet = 4)))
        assertFalse(PrintTransform.usesLandscapeSheets(PrintSettings(layout = LayoutChoice.NUP, pagesPerSheet = 9)))
        assertTrue(PrintTransform.usesLandscapeSheets(PrintSettings(layout = LayoutChoice.BOOKLET)))
        assertFalse(PrintTransform.usesLandscapeSheets(PrintSettings(pagesPerSheet = 2)))
    }

    @Test fun adjustKeepsUserChoices() {
        val auto = PrintTransform.adjustForOutput(PrintSettings(layout = LayoutChoice.BOOKLET))
        assertEquals(OrientationChoice.LANDSCAPE, auto.orientation)
        assertEquals(DuplexChoice.SHORT_EDGE, auto.duplexMode)

        val chosen = PrintTransform.adjustForOutput(
            PrintSettings(layout = LayoutChoice.BOOKLET, orientation = OrientationChoice.PORTRAIT, duplexMode = DuplexChoice.ONE_SIDED)
        )
        assertEquals(OrientationChoice.PORTRAIT, chosen.orientation)
        assertEquals(DuplexChoice.ONE_SIDED, chosen.duplexMode)

        val normal = PrintSettings(watermarkText = "DRAFT")
        assertEquals(normal, PrintTransform.adjustForOutput(normal))
    }

    @Test fun a4WarningOnlyForArrangedSheetsOnOtherPaper() {
        assertTrue(PrintTransform.sheetsAreA4Only(PrintSettings(layout = LayoutChoice.NUP, paperSizeId = "NA_LETTER")))
        assertFalse(PrintTransform.sheetsAreA4Only(PrintSettings(layout = LayoutChoice.NUP)))
        assertFalse(PrintTransform.sheetsAreA4Only(PrintSettings(paperSizeId = "NA_LETTER")))
    }

    @Test fun reverseAndManualDuplexTriggerPreprocessing() {
        assertTrue(PrintTransform.needsPreprocessing(PrintSettings(reverseOrder = true)))
        assertTrue(PrintTransform.needsPreprocessing(PrintSettings(manualDuplex = ManualDuplexChoice.ON)))
    }

    @Test fun manualDuplexTurnsPrinterDuplexOff() {
        val r = PrintTransform.adjustForOutput(
            PrintSettings(manualDuplex = ManualDuplexChoice.ON, duplexMode = DuplexChoice.LONG_EDGE)
        )
        assertEquals(DuplexChoice.ONE_SIDED, r.duplexMode)
    }

    @Test fun bookletBothSidesFlipsOnShortEdge() {
        val r = PrintTransform.adjustForOutput(PrintSettings(layout = LayoutChoice.BOOKLET, duplexMode = DuplexChoice.LONG_EDGE))
        assertEquals(DuplexChoice.SHORT_EDGE, r.duplexMode)
    }

    @Test fun oneSidedPrintsLastPageFirst() {
        assertTrue(PrintTransform.withAutoOrder(PrintSettings(duplexMode = DuplexChoice.ONE_SIDED)).reverseOrder)
        assertFalse(PrintTransform.withAutoOrder(PrintSettings(duplexMode = DuplexChoice.LONG_EDGE)).reverseOrder)
        assertFalse(PrintTransform.withAutoOrder(PrintSettings(duplexMode = DuplexChoice.DEFAULT)).reverseOrder)
        assertFalse(PrintTransform.withAutoOrder(PrintSettings(duplexMode = DuplexChoice.ONE_SIDED, manualDuplex = ManualDuplexChoice.ON)).reverseOrder)
    }
}
