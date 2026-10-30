package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NupLayoutTest {
    @Test
    fun landscapeSheetsHaveMoreColumnsThanRows() {
        val l = PdfLayout.nupLayout(6, true)
        assertTrue(l.landscape)
        assertEquals(3, l.cols)
        assertEquals(2, l.rows)
    }

    @Test
    fun portraitSheetsHaveMoreRowsThanColumns() {
        val l = PdfLayout.nupLayout(2, false)
        assertFalse(l.landscape)
        assertEquals(1, l.cols)
        assertEquals(2, l.rows)
    }

    @Test
    fun everyCountFillsItsGrid() {
        for (n in PdfLayout.nupCounts) for (land in listOf(true, false)) {
            val l = PdfLayout.nupLayout(n, land)
            assertEquals(n, l.cols * l.rows)
            assertEquals(land, l.landscape)
        }
    }

    @Test
    fun fourPerSheetDefaultsToLandscape() {
        assertTrue(PdfLayout.defaultLandscape(4))
    }
}
