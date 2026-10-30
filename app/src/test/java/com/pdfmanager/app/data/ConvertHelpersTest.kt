package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale

class ConvertHelpersTest {

    // ------------------------------------------------------------ decodeText

    @Test fun readsUtf8Bangla() = assertEquals("বাংলা", ConvertHelpers.decodeText("বাংলা".toByteArray(Charsets.UTF_8)))

    @Test fun dropsUtf8ByteOrderMark() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()
        assertEquals("hi", ConvertHelpers.decodeText(bytes))
    }

    @Test fun readsUtf16WithByteOrderMark() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "ab".toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "ab".toByteArray(Charsets.UTF_16BE)
        assertEquals("ab", ConvertHelpers.decodeText(le))
        assertEquals("ab", ConvertHelpers.decodeText(be))
    }

    @Test fun fallsBackToWindows1252() {
        // 0x93 and 0x94 are curly quotes in Windows-1252 and not valid UTF-8
        val bytes = byteArrayOf(0x93.toByte(), 'h'.code.toByte(), 'i'.code.toByte(), 0x94.toByte())
        assertEquals("“hi”", ConvertHelpers.decodeText(bytes))
    }

    @Test fun emptyFileIsEmptyText() = assertEquals("", ConvertHelpers.decodeText(ByteArray(0)))

    // ------------------------------------------------------------ normalizeText

    @Test fun oneKindOfLineBreak() = assertEquals("a\nb\nc\nd\ne", ConvertHelpers.normalizeText("a\r\nb\rc d\u000Ce"))
    @Test fun tabsBecomeFourSpaces() = assertEquals("a    b", ConvertHelpers.normalizeText("a\tb"))
    @Test fun controlCharactersAreRemoved() = assertEquals("ab", ConvertHelpers.normalizeText("a\u0000\u0007b\u007F﻿"))
    @Test fun keepsBanglaJoiners() {
        // Ro + hasanta + ZWJ (ra-phala) and ZWNJ must survive
        val s = "র্‍য ক‌ষ"
        assertEquals(s, ConvertHelpers.normalizeText(s))
    }
    @Test fun blankLinesStay() = assertEquals("a\n\n\nb", ConvertHelpers.normalizeText("a\n\n\nb"))

    // ------------------------------------------------------------ assembleText

    @Test fun pagesAreSeparatedByABlankLine() =
        assertEquals("one\n\ntwo", ConvertHelpers.assembleText(listOf(1 to "one\n", 2 to "two\n\n"), marks = false))

    @Test fun emptyPagesAreLeftOutWithoutMarks() =
        assertEquals("one\n\nthree", ConvertHelpers.assembleText(listOf(1 to "one", 2 to " \n", 3 to "three"), marks = false))

    @Test fun marksNameEveryPageEvenEmptyOnes() {
        val text = ConvertHelpers.assembleText(listOf(2 to "\nhello\n", 3 to "", 5 to "bye"), marks = true)
        assertEquals("--- Page 2 ---\n\nhello\n\n--- Page 3 ---\n\n--- Page 5 ---\n\nbye", text)
    }

    @Test fun noPagesGiveNoText() = assertEquals("", ConvertHelpers.assembleText(emptyList(), marks = true))

    // ------------------------------------------------------------ wordCount / previewOf

    @Test fun countsWords() {
        assertEquals(0, ConvertHelpers.wordCount(""))
        assertEquals(0, ConvertHelpers.wordCount(" \n\t "))
        assertEquals(3, ConvertHelpers.wordCount("one two\nthree"))
        assertEquals(3, ConvertHelpers.wordCount("আমি ভাত খাই"))
    }

    @Test fun shortPreviewIsUnchanged() = assertEquals("a\nb", ConvertHelpers.previewOf("a\nb", 100, 5))
    @Test fun previewStopsAtTheLineLimit() = assertEquals("1\n2…", ConvertHelpers.previewOf("1\n2\n3\n4", 100, 2))
    @Test fun previewStopsAtTheCharacterLimit() = assertEquals("abcd…", ConvertHelpers.previewOf("abcdefgh", 4, 10))
    @Test fun previewNeverSplitsASurrogatePair() = assertEquals("a…", ConvertHelpers.previewOf("a😀b", 2, 10))
    @Test fun exactLimitIsNotCut() = assertEquals("1\n2", ConvertHelpers.previewOf("1\n2", 100, 2))

    // ------------------------------------------------------------ file names

    @Test fun textFileNames() {
        assertEquals("Report.txt", ConvertHelpers.textFileName("Report.pdf", "", true))
        assertEquals("Report.txt", ConvertHelpers.textFileName("Report.pdf", "1-10", true))
        assertEquals("Report_p1-3_5.txt", ConvertHelpers.textFileName("Report.pdf", " 1-3, 5 ", false))
        assertEquals("Report.txt", ConvertHelpers.textFileName("Report.pdf", "  ", false))
        assertEquals("my.notes.txt", ConvertHelpers.textFileName("my.notes.pdf", "", true))
        assertEquals("Text.txt", ConvertHelpers.textFileName(null, "", true))
        assertEquals("Text.txt", ConvertHelpers.textFileName(".pdf", "", true))
    }

    @Test fun longRangeIsShortenedInTheName() {
        val name = ConvertHelpers.textFileName("A.pdf", (1..80).joinToString(","), false)
        assertTrue(name.length <= "A_p".length + 40 + ".txt".length)
    }

    @Test fun imageFileNames() {
        assertEquals("Img_Report_p003_01.jpg", ConvertHelpers.imageFileName("Report.pdf", 3, 1, "jpg"))
        assertEquals("Img_Report_p123_12.png", ConvertHelpers.imageFileName("Report.pdf", 123, 12, "png"))
        assertEquals("Img_PDF_p001_02.png", ConvertHelpers.imageFileName(null, 1, 2, "png"))
    }

    @Test fun numbersUseWesternDigitsOnABanglaPhone() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("bn-BD"))
            assertEquals("Img_R_p007_01.jpg", ConvertHelpers.imageFileName("R.pdf", 7, 1, "jpg"))
            assertEquals("12,345", ConvertHelpers.count(12345))
            assertEquals("2 pages", ConvertHelpers.plural(2, "page"))
        } finally {
            Locale.setDefault(old)
        }
    }

    // ------------------------------------------------------------ image size rules

    @Test fun smallImageNeedsBothSidesShort() {
        assertTrue(ConvertHelpers.isSmall(40, 60, 64))
        assertFalse(ConvertHelpers.isSmall(40, 600, 64))
        assertFalse(ConvertHelpers.isSmall(64, 64, 64))
        assertFalse(ConvertHelpers.isSmall(1, 1, 0))
    }

    @Test fun hugeImagesAreRefused() {
        assertFalse(ConvertHelpers.tooManyPixels(5000, 7000))
        assertTrue(ConvertHelpers.tooManyPixels(6000, 7000))
        // 70000 x 70000 overflows an Int: it must still count as huge
        assertTrue(ConvertHelpers.tooManyPixels(70000, 70000))
    }

    // ------------------------------------------------------------ page breaks

    private fun lines(count: Int, height: Int) =
        IntArray(count) { it * height } to IntArray(count) { (it + 1) * height }

    @Test fun linesFillPagesInOrder() {
        val (t, b) = lines(10, 10)
        assertEquals(
            listOf(LineSpan(0, 3), LineSpan(3, 6), LineSpan(6, 9), LineSpan(9, 10)),
            ConvertHelpers.pageBreaks(t, b, 35)
        )
    }

    @Test fun aLineThatFitsExactlyStaysOnThePage() {
        val (t, b) = lines(6, 10)
        assertEquals(listOf(LineSpan(0, 3), LineSpan(3, 6)), ConvertHelpers.pageBreaks(t, b, 30))
    }

    @Test fun aLineTallerThanThePageGetsItsOwnPage() {
        val tops = intArrayOf(0, 10, 100)
        val bottoms = intArrayOf(10, 100, 110)
        assertEquals(listOf(LineSpan(0, 1), LineSpan(1, 2), LineSpan(2, 3)), ConvertHelpers.pageBreaks(tops, bottoms, 50))
    }

    @Test fun everyLineLandsOnExactlyOnePage() {
        val (t, b) = lines(1234, 7)
        val spans = ConvertHelpers.pageBreaks(t, b, 100)
        assertEquals(0, spans.first().start)
        assertEquals(1234, spans.last().endExclusive)
        for (i in 1 until spans.size) assertEquals(spans[i - 1].endExclusive, spans[i].start)
        assertTrue(spans.all { b[it.endExclusive - 1] - t[it.start] <= 100 })
    }

    @Test fun noLinesNoPages() = assertEquals(emptyList<LineSpan>(), ConvertHelpers.pageBreaks(IntArray(0), IntArray(0), 100))

    // ------------------------------------------------------------ reading limits

    @Test fun readLimitedReturnsAllBytesUnderTheLimit() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        assertTrue(data.contentEquals(ConvertHelpers.readLimited(ByteArrayInputStream(data), 300_000)))
        assertTrue(data.contentEquals(ConvertHelpers.readLimited(ByteArrayInputStream(data), 200_000)))
    }

    @Test fun readLimitedRefusesTooBigFiles() {
        try {
            ConvertHelpers.readLimited(ByteArrayInputStream(ByteArray(2 * 1024 * 1024)), 1024 * 1024)
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertEquals("This file is too large (over 1 MB)", e.message)
        }
    }

    // ------------------------------------------------------------ messages

    @Test fun pluralWords() {
        assertEquals("0 pages", ConvertHelpers.plural(0, "page"))
        assertEquals("1 page", ConvertHelpers.plural(1, "page"))
        assertEquals("1,200 words", ConvertHelpers.plural(1200, "word"))
        assertEquals("2 images", ConvertHelpers.plural(2, "image"))
    }
}
