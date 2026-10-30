package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IncomingPdfTest {
    @Test fun keepsPlainName() = assertEquals("report.pdf", IncomingPdf.safeName("report.pdf"))

    @Test fun percentAndSlashAreRemoved() {
        val name = IncomingPdf.safeName("a%2Fb:c.pdf")
        assertFalse(name.contains('%') || name.contains('/') || name.contains(':'))
    }

    @Test fun banglaLettersAreKept() = assertEquals("\u09AC\u0987.pdf", IncomingPdf.safeName("\u09AC\u0987.pdf"))

    @Test fun emptyNameGetsDefault() = assertEquals("shared.pdf", IncomingPdf.safeName(".pdf"))

    @Test fun extensionIsAlwaysPdf() = assertEquals("photo.pdf", IncomingPdf.safeName("photo.jpg"))
}
