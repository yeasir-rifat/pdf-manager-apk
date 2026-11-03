package com.pdfmanager.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiRequestPdfTest {

    /** The fake PDF from the plan: 3 bytes. Base64 of 25 50 44 is "JVBE". */
    private val fake = byteArrayOf(0x25, 0x50, 0x44)

    private fun part(bytes: ByteArray) = GeminiRequest.PdfPart { ByteArrayInputStream(bytes) }

    private fun write(turns: List<GeminiRequest.Turn>): String {
        val out = ByteArrayOutputStream()
        GeminiRequest.write(out, turns, AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL)
        return out.toString("UTF-8")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parts(body: String): List<Map<String, Any?>> {
        val root = DriveJson.parseObject(body)!!
        val contents = root["contents"] as List<Map<String, Any?>>
        return contents.last()["parts"] as List<Map<String, Any?>>
    }

    @Test fun threeBytePdfIsBase64InsideInlineData() {
        val body = write(listOf(GeminiRequest.Turn(GeminiRequest.USER, "Explain", part(fake))))
        val p = parts(body)
        @Suppress("UNCHECKED_CAST")
        val inline = p[0]["inline_data"] as Map<String, Any?>
        assertEquals("application/pdf", inline["mime_type"])
        assertEquals("JVBE", inline["data"])
        assertArrayEquals(fake, Base64.getDecoder().decode(inline["data"] as String))
    }

    @Test fun pdfPartComesBeforeTheTextPart() {
        val p = parts(write(listOf(GeminiRequest.Turn(GeminiRequest.USER, "Explain", part(fake)))))
        assertEquals(2, p.size)
        assertTrue(p[0].containsKey("inline_data"))
        assertEquals("Explain", p[1]["text"])
    }

    @Test fun everyPaddingCaseSurvives() {
        for (n in 0..10) {
            val bytes = ByteArray(n) { (it * 7 + 3).toByte() }
            val p = parts(write(listOf(GeminiRequest.Turn(GeminiRequest.USER, "q", part(bytes)))))
            @Suppress("UNCHECKED_CAST")
            val data = (p[0]["inline_data"] as Map<String, Any?>)["data"] as String
            assertArrayEquals("size $n", bytes, Base64.getDecoder().decode(data))
        }
    }

    @Test fun bigPdfSurvivesAcrossBufferEdges() {
        val bytes = ByteArray(3 * 1024 * 1024 + 5) { (it % 251).toByte() }
        val p = parts(write(listOf(GeminiRequest.Turn(GeminiRequest.USER, "q", part(bytes)))))
        @Suppress("UNCHECKED_CAST")
        val data = (p[0]["inline_data"] as Map<String, Any?>)["data"] as String
        assertArrayEquals(bytes, Base64.getDecoder().decode(data))
    }

    @Test fun smallReadsFromTheSourceStillEncodeRight() {
        val bytes = ByteArray(1000) { it.toByte() }
        // a stream that hands out one byte at a time
        val slow = GeminiRequest.PdfPart {
            object : InputStream() {
                private var i = 0
                override fun read(): Int = if (i < bytes.size) bytes[i++].toInt() and 0xff else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (i >= bytes.size) return -1
                    b[off] = bytes[i++]
                    return 1
                }
            }
        }
        val p = parts(write(listOf(GeminiRequest.Turn(GeminiRequest.USER, "q", slow))))
        @Suppress("UNCHECKED_CAST")
        val data = (p[0]["inline_data"] as Map<String, Any?>)["data"] as String
        assertArrayEquals(bytes, Base64.getDecoder().decode(data))
    }

    @Test fun banglaQuestionNextToAPdf() {
        val q = "এই পৃষ্ঠাটি ব্যাখ্যা করো \"দ্রুত\"\nধন্যবাদ"
        val p = parts(write(listOf(GeminiRequest.Turn(GeminiRequest.USER, q, part(fake)))))
        assertEquals(q, p[1]["text"])
    }

    @Test fun noPdfGivesTheSameBodyAsBefore() {
        val turns = listOf(GeminiRequest.Turn(GeminiRequest.USER, "Say OK"))
        assertEquals(GeminiRequest.body("Say OK", AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL), write(turns))
        assertFalse(write(turns).contains("inline_data"))
    }

    @Test fun bodyAndWriteGiveTheSameText() {
        val turns = listOf(GeminiRequest.Turn(GeminiRequest.USER, "q", part(fake)))
        assertEquals(
            GeminiRequest.body(turns, AiPrefs.LANG_EN, AiPrefs.LEN_SHORT),
            ByteArrayOutputStream().also { GeminiRequest.write(it, turns, AiPrefs.LANG_EN, AiPrefs.LEN_SHORT) }.toString("UTF-8"),
        )
    }

    @Test fun onlyTheTurnWithAPdfCarriesIt() {
        val turns = listOf(
            GeminiRequest.Turn(GeminiRequest.USER, "first"),
            GeminiRequest.Turn(GeminiRequest.MODEL, "answer"),
            GeminiRequest.Turn(GeminiRequest.USER, "second", part(fake)),
        )
        val body = write(turns)
        assertEquals(1, Regex("inline_data").findAll(body).count())
        @Suppress("UNCHECKED_CAST")
        val contents = DriveJson.parseObject(body)!!["contents"] as List<Map<String, Any?>>
        assertEquals(listOf("user", "model", "user"), contents.map { it["role"] })
        assertEquals(1, (contents[0]["parts"] as List<*>).size)
        assertEquals(2, (contents[2]["parts"] as List<*>).size)
    }

    @Test fun theBodyIsOneValidJsonObject() {
        val root = DriveJson.parseObject(write(listOf(GeminiRequest.Turn(GeminiRequest.USER, "q", part(fake)))))!!
        assertTrue(root.containsKey("system_instruction"))
        assertTrue(root.containsKey("generationConfig"))
    }

    @Test fun unreadableFileGivesAFileException() {
        val broken = GeminiRequest.PdfPart { throw java.io.FileNotFoundException("/secret/path.pdf") }
        try {
            write(listOf(GeminiRequest.Turn(GeminiRequest.USER, "q", broken)))
            fail()
        } catch (e: GeminiFileException) {
            assertFalse(e.message!!.contains("secret"))
        }
    }

    @Test fun readFailureHalfWayGivesAFileException() {
        val failing = GeminiRequest.PdfPart {
            object : InputStream() {
                private var n = 0
                override fun read(): Int = throw IOException("disk")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n++ == 0) { b[off] = 1; return 1 }
                    throw IOException("disk")
                }
            }
        }
        try {
            write(listOf(GeminiRequest.Turn(GeminiRequest.USER, "q", failing)))
            fail()
        } catch (e: GeminiFileException) {
        }
    }

    @Test fun aBrokenOutputStreamStaysAnIoException() {
        val dead = object : java.io.OutputStream() {
            override fun write(b: Int) = throw IOException("pipe")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("pipe")
        }
        try {
            GeminiRequest.write(dead, listOf(GeminiRequest.Turn(GeminiRequest.USER, "q", part(fake))), AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL)
            fail()
        } catch (e: IOException) {
        }
    }

    @Test fun theOutputStreamIsNotClosed() {
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        }
        GeminiRequest.write(out, listOf(GeminiRequest.Turn(GeminiRequest.USER, "q", part(fake))), AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL)
        assertFalse(closed)
    }

    @Test fun noEmDashInTheSystemText() {
        assertFalse(GeminiRequest.systemText(AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL).contains('\u2014'))
    }
}
