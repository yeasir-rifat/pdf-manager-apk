package com.pdfmanager.app.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPdfSourceTest {

    private val dir: File = Files.createTempDirectory("ai-src").toFile()

    @After fun clean() { dir.deleteRecursively() }

    private val tenMb = 10L * 1024 * 1024

    @Test fun limitsMatchThePlan() {
        assertEquals(10_485_760L, AiPdfRules.INLINE_MAX_BYTES)
        assertEquals(1000, AiPdfRules.MAX_PAGES)
        assertEquals("ai-tmp", AiPdfRules.TEMP_DIR)
    }

    @Test fun exactlyTenMbStillGoesInline() {
        assertNull(AiPdfRules.refusal(tenMb, 5))
    }

    @Test fun oneByteOverTenMbIsRefused() {
        assertEquals(GeminiError.File, AiPdfRules.refusal(tenMb + 1, 5))
    }

    @Test fun oneByteUnderTenMbGoesInline() {
        assertNull(AiPdfRules.refusal(tenMb - 1, 5))
    }

    @Test fun pageGate() {
        assertNull(AiPdfRules.refusal(1_000, 1))
        assertNull(AiPdfRules.refusal(1_000, 1000))
        assertEquals(GeminiError.Pages, AiPdfRules.refusal(1_000, 1001))
    }

    @Test fun tooManyPagesWinsOverTooBig() {
        assertEquals(GeminiError.Pages, AiPdfRules.refusal(tenMb + 1, 1001))
    }

    @Test fun emptyOrUnreadableFilesAreRefused() {
        assertEquals(GeminiError.File, AiPdfRules.refusal(0, 5))
        assertEquals(GeminiError.File, AiPdfRules.refusal(-1, 5))
        assertEquals(GeminiError.File, AiPdfRules.refusal(1_000, 0))
        assertEquals(GeminiError.File, AiPdfRules.refusal(1_000, -1))
    }

    @Test fun closeDeletesTheCopy() {
        val f = File(dir, "a.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val src = AiPdfSource(f, 3, 1)
        assertTrue(f.exists())
        src.close()
        assertFalse(f.exists())
    }

    @Test fun useDeletesTheCopyEvenWhenTheBlockThrows() {
        val f = File(dir, "b.pdf").apply { writeBytes(byteArrayOf(1)) }
        try {
            AiPdfSource(f, 1, 1).use { error("boom") }
        } catch (e: IllegalStateException) {
        }
        assertFalse(f.exists())
    }

    @Test fun closingTwiceOrAfterTheFileIsGoneIsFine() {
        val f = File(dir, "c.pdf").apply { writeBytes(byteArrayOf(1)) }
        val src = AiPdfSource(f, 1, 1)
        src.close()
        src.close()
    }

    @Test fun partReadsTheFileBytes() {
        val bytes = byteArrayOf(0x25, 0x50, 0x44)
        val f = File(dir, "d.pdf").apply { writeBytes(bytes) }
        val got = AiPdfSource(f, 3, 1).part().open().use { it.readBytes() }
        assertArrayEquals(bytes, got)
    }
}
