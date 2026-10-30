package com.pdfmanager.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeHelpersTest {

    private val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)
    private val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0)

    // ------------------------------------------------------------ names and kinds

    @Test fun extensionIsLowerCaseWithoutDot() {
        assertEquals("docx", OfficeHelpers.extensionOf("Report.DOCX"))
        assertEquals("gz", OfficeHelpers.extensionOf("a.b.tar.gz"))
        assertEquals("", OfficeHelpers.extensionOf("noext"))
        assertEquals("", OfficeHelpers.extensionOf(null))
    }

    @Test fun kindComesFromExtension() {
        assertEquals(OfficeKind.WORD, OfficeHelpers.kindOfName("a.doc"))
        assertEquals(OfficeKind.WORD, OfficeHelpers.kindOfName("a.DOCX"))
        assertEquals(OfficeKind.EXCEL, OfficeHelpers.kindOfName("a.xls"))
        assertEquals(OfficeKind.EXCEL, OfficeHelpers.kindOfName("a.xlsx"))
        assertEquals(OfficeKind.POWERPOINT, OfficeHelpers.kindOfName("a.ppt"))
        assertEquals(OfficeKind.POWERPOINT, OfficeHelpers.kindOfName("a.pptx"))
        assertNull(OfficeHelpers.kindOfName("a.pdf"))
        assertNull(OfficeHelpers.kindOfName("a.docm"))
        assertNull(OfficeHelpers.kindOfName(null))
    }

    @Test fun googleTargetsAreDocsSheetsSlides() {
        assertEquals("application/vnd.google-apps.document", OfficeKind.WORD.googleMime)
        assertEquals("application/vnd.google-apps.spreadsheet", OfficeKind.EXCEL.googleMime)
        assertEquals("application/vnd.google-apps.presentation", OfficeKind.POWERPOINT.googleMime)
    }

    @Test fun sourceMimeMatchesExtension() {
        assertEquals("application/msword", OfficeHelpers.sourceMime("a.doc"))
        assertTrue(OfficeHelpers.sourceMime("a.xlsx")!!.contains("spreadsheetml"))
        assertTrue(OfficeHelpers.sourceMime("a.pptx")!!.contains("presentationml"))
        assertNull(OfficeHelpers.sourceMime("a.txt"))
    }

    @Test fun pickerMimesCoverBothGenerations() {
        assertEquals(2, OfficeHelpers.pickerMimes(OfficeKind.WORD).size)
        assertEquals(2, OfficeHelpers.pickerMimes(OfficeKind.EXCEL).size)
        assertEquals(2, OfficeHelpers.pickerMimes(OfficeKind.POWERPOINT).size)
    }

    // ------------------------------------------------------------ containers and file check

    @Test fun containerIsReadFromMagicBytes() {
        assertEquals(OfficeContainer.MODERN_ZIP, OfficeHelpers.containerOf(zip))
        assertEquals(OfficeContainer.LEGACY_OLE, OfficeHelpers.containerOf(ole))
        assertEquals(OfficeContainer.UNKNOWN, OfficeHelpers.containerOf(png))
        assertEquals(OfficeContainer.UNKNOWN, OfficeHelpers.containerOf(byteArrayOf(0x50, 0x4B)))
        assertEquals(OfficeContainer.UNKNOWN, OfficeHelpers.containerOf(ByteArray(0)))
    }

    @Test fun goodFilesPass() {
        assertNull(OfficeHelpers.checkFile(OfficeKind.WORD, "a.docx", 1000, zip))
        assertNull(OfficeHelpers.checkFile(OfficeKind.WORD, "a.doc", 1000, ole))
        assertNull(OfficeHelpers.checkFile(OfficeKind.EXCEL, "a.XLSX", 1000, zip))
        assertNull(OfficeHelpers.checkFile(OfficeKind.POWERPOINT, "a.ppt", 1000, ole))
    }

    @Test fun renamedFileIsRefused() {
        assertEquals("This is not a valid Word file", OfficeHelpers.checkFile(OfficeKind.WORD, "photo.docx", 1000, png))
        // an old binary file renamed to the new extension
        assertEquals("This is not a valid Excel file", OfficeHelpers.checkFile(OfficeKind.EXCEL, "a.xlsx", 1000, ole))
        assertEquals("This is not a valid Excel file", OfficeHelpers.checkFile(OfficeKind.EXCEL, "a.xls", 1000, zip))
    }

    @Test fun wrongKindIsRefused() {
        assertEquals("This is not a valid PowerPoint file", OfficeHelpers.checkFile(OfficeKind.POWERPOINT, "a.docx", 1000, zip))
        assertNotNull(OfficeHelpers.checkFile(OfficeKind.WORD, null, 1000, zip))
    }

    @Test fun emptyAndHugeFilesAreRefused() {
        assertEquals("This file is empty", OfficeHelpers.checkFile(OfficeKind.WORD, "a.docx", 0, zip))
        assertEquals("File is too large (max 50 MB)", OfficeHelpers.checkFile(OfficeKind.WORD, "a.docx", OfficeHelpers.MAX_FILE_BYTES + 1, zip))
        assertNull(OfficeHelpers.checkFile(OfficeKind.WORD, "a.docx", OfficeHelpers.MAX_FILE_BYTES, zip))
    }

    @Test fun pdfHeaderIsRecognised() {
        assertTrue(OfficeHelpers.looksLikePdf("%PDF-1.7\n".toByteArray()))
        assertTrue(OfficeHelpers.looksLikePdf("junk%PDF-1.4".toByteArray()))
        assertFalse(OfficeHelpers.looksLikePdf("<html>".toByteArray()))
        assertFalse(OfficeHelpers.looksLikePdf(ByteArray(0)))
        assertFalse(OfficeHelpers.looksLikePdf("%PD".toByteArray()))
    }

    // ------------------------------------------------------------ output names

    @Test fun outputNameUsesPrefixAndOriginalName() {
        assertEquals("Word_Report.pdf", OfficeHelpers.outputName(OfficeKind.WORD, "Report.docx", "T"))
        assertEquals("Excel_Budget 2026.pdf", OfficeHelpers.outputName(OfficeKind.EXCEL, "Budget 2026.xlsx", "T"))
        assertEquals("Slides_a.b.pdf", OfficeHelpers.outputName(OfficeKind.POWERPOINT, "a.b.pptx", "T"))
    }

    @Test fun outputNameKeepsBangla() {
        assertEquals("Word_প্রতিবেদন.pdf", OfficeHelpers.outputName(OfficeKind.WORD, "প্রতিবেদন.docx", "T"))
    }

    @Test fun missingNameFallsBackToStamp() {
        assertEquals("Word_20261030.pdf", OfficeHelpers.outputName(OfficeKind.WORD, null, "20261030"))
        assertEquals("Word_20261030.pdf", OfficeHelpers.outputName(OfficeKind.WORD, "  ", "20261030"))
        assertEquals("Word_20261030.pdf", OfficeHelpers.outputName(OfficeKind.WORD, ".docx", "20261030"))
    }

    @Test fun driveNameHidesTheRealName() {
        assertEquals("pdfm-temp-abc123", OfficeHelpers.neutralDriveName("abc123"))
    }

    // ------------------------------------------------------------ error messages

    @Test fun messagesFollowThePlan() {
        assertEquals("Drive API is not enabled for this app", OfficeHelpers.messageFor(403, "accessNotConfigured"))
        assertEquals("Your Google Drive storage is full", OfficeHelpers.messageFor(403, "storageQuotaExceeded"))
        assertEquals("The converted PDF is too large (over 10 MB) for Google to export", OfficeHelpers.messageFor(403, "exportSizeLimitExceeded"))
        assertEquals("Google is busy, try again in a minute", OfficeHelpers.messageFor(403, "userRateLimitExceeded"))
        assertEquals("Google is busy, try again in a minute", OfficeHelpers.messageFor(429, null))
        assertEquals("Please connect your Google account again", OfficeHelpers.messageFor(401, null))
        assertEquals("Google could not convert this file (locked or damaged?)", OfficeHelpers.messageFor(400, null))
        assertEquals("Google had a problem, try again", OfficeHelpers.messageFor(503, null))
        assertEquals("Google did not allow this request", OfficeHelpers.messageFor(403, "forbidden"))
        assertEquals("Google could not finish this request", OfficeHelpers.messageFor(418, null))
    }

    @Test fun retryOnlyWhenItCanHelp() {
        assertTrue(OfficeHelpers.isRetryable(429, null))
        assertTrue(OfficeHelpers.isRetryable(503, null))
        assertTrue(OfficeHelpers.isRetryable(403, "rateLimitExceeded"))
        assertTrue(OfficeHelpers.isRetryable(403, "userRateLimitExceeded"))
        assertFalse(OfficeHelpers.isRetryable(403, "storageQuotaExceeded"))
        assertFalse(OfficeHelpers.isRetryable(403, "exportSizeLimitExceeded"))
        assertFalse(OfficeHelpers.isRetryable(403, "accessNotConfigured"))
        assertFalse(OfficeHelpers.isRetryable(400, null))
        assertFalse(OfficeHelpers.isRetryable(401, null))
        assertFalse(OfficeHelpers.isRetryable(404, null))
    }

    // ------------------------------------------------------------ backoff

    @Test fun retryAfterIsParsed() {
        assertEquals(5L, OfficeHelpers.parseRetryAfter("5"))
        assertEquals(5L, OfficeHelpers.parseRetryAfter(" 5 "))
        assertEquals(0L, OfficeHelpers.parseRetryAfter("0"))
        assertNull(OfficeHelpers.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(OfficeHelpers.parseRetryAfter("-3"))
        assertNull(OfficeHelpers.parseRetryAfter("999999"))
        assertNull(OfficeHelpers.parseRetryAfter(null))
    }

    @Test fun backoffDoublesAndStopsAtThirtySeconds() {
        assertEquals(1000L, OfficeHelpers.backoffMillis(1))
        assertEquals(2000L, OfficeHelpers.backoffMillis(2))
        assertEquals(4000L, OfficeHelpers.backoffMillis(3))
        assertEquals(30_000L, OfficeHelpers.backoffMillis(9))
        assertEquals(1000L, OfficeHelpers.backoffMillis(0))
    }

    @Test fun jitterAddsUpToAQuarter() {
        assertEquals(1250L, OfficeHelpers.backoffMillis(1, null, 1.0))
        assertEquals(1000L, OfficeHelpers.backoffMillis(1, null, 0.0))
        assertEquals(1250L, OfficeHelpers.backoffMillis(1, null, 7.0))
    }

    @Test fun serverWaitWinsWhenLonger() {
        assertEquals(10_000L, OfficeHelpers.backoffMillis(1, 10L))
        assertEquals(4000L, OfficeHelpers.backoffMillis(3, 1L))
        assertEquals(60_000L, OfficeHelpers.backoffMillis(1, 3600L))
    }

    @Test fun kindsHaveShortPrefixes() {
        assertArrayEquals(arrayOf("Word", "Excel", "Slides"), OfficeKind.values().map { it.prefix }.toTypedArray())
    }
}
