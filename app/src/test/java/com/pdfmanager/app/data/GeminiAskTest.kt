package com.pdfmanager.app.data

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiAskTest {

    private var server: FakeDriveServer? = null
    private val dir: File = Files.createTempDirectory("ai-ask").toFile()

    @After fun stop() { server?.stop(); dir.deleteRecursively() }

    private fun serve(respond: (FakeReq) -> FakeReply) = FakeDriveServer(respond).also { server = it }

    private val okBody = """{"candidates":[{"content":{"parts":[{"text":"It is a siphon."}]},"finishReason":"STOP"}]}"""

    private val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)

    private fun source(bytes: ByteArray = pdfBytes, pages: Int = 5, size: Long = bytes.size.toLong()): AiPdfSource {
        val f = File(dir, "src-${System.nanoTime()}.pdf").apply { writeBytes(bytes) }
        return AiPdfSource(f, size, pages)
    }

    private fun ask(
        s: FakeDriveServer, src: AiPdfSource, key: String = "k-1234", model: String = "gemini-flash-latest",
        question: String = "What is the figure?",
    ): GeminiAskResult = runBlocking {
        GeminiAsk.askPdf(src, question, key, model, AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL, GeminiEndpoints("${s.base}/v1beta"))
    }

    private fun failed(r: GeminiAskResult): GeminiError = (r as GeminiAskResult.Failed).error

    @Test fun pdfAndQuestionReachTheServerAndTheAnswerComesBack() {
        val s = serve { FakeReply(200, okBody) }
        val r = ask(s, source())
        assertEquals("It is a siphon.", (r as GeminiAskResult.Answer).answer.text)
        val req = s.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/v1beta/models/gemini-flash-latest:generateContent", req.path)
        assertEquals("k-1234", req.headers["x-goog-api-key"])
        val body = String(req.body, Charsets.UTF_8)
        @Suppress("UNCHECKED_CAST")
        val parts = ((DriveJson.parseObject(body)!!["contents"] as List<Map<String, Any?>>)[0]["parts"]) as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val inline = parts[0]["inline_data"] as Map<String, Any?>
        assertArrayEquals(pdfBytes, Base64.getDecoder().decode(inline["data"] as String))
        assertEquals("What is the figure?", parts[1]["text"])
    }

    @Test fun bodyIsSentChunkedWithoutALength() {
        val s = serve { FakeReply(200, okBody) }
        ask(s, source())
        val h = s.requests.single().headers
        assertTrue(h["transfer-encoding"]!!.lowercase().contains("chunked"))
        assertFalse(h.containsKey("content-length"))
    }

    @Test fun keyNeverAppearsInThePathOrBody() {
        val s = serve { FakeReply(200, okBody) }
        ask(s, source(), key = "secret-key-1234")
        val req = s.requests.single()
        assertFalse(req.path.contains("secret-key-1234"))
        assertFalse(String(req.body, Charsets.UTF_8).contains("secret-key-1234"))
    }

    @Test fun overOneThousandPagesSendsNothing() {
        val s = serve { FakeReply(200, okBody) }
        assertEquals(GeminiError.Pages, failed(ask(s, source(pages = 1001))))
        assertTrue(s.requests.isEmpty())
    }

    @Test fun exactlyOneThousandPagesIsSent() {
        val s = serve { FakeReply(200, okBody) }
        assertTrue(ask(s, source(pages = 1000)) is GeminiAskResult.Answer)
        assertEquals(1, s.requests.size)
    }

    @Test fun overTenMbSendsNothing() {
        val s = serve { FakeReply(200, okBody) }
        val r = ask(s, source(size = AiPdfRules.INLINE_MAX_BYTES + 1))
        assertEquals(GeminiError.File, failed(r))
        assertTrue(s.requests.isEmpty())
    }

    @Test fun exactlyTenMbIsSent() {
        val s = serve { FakeReply(200, okBody) }
        assertTrue(ask(s, source(size = AiPdfRules.INLINE_MAX_BYTES)) is GeminiAskResult.Answer)
    }

    @Test fun noKeyAndBadModelSendNothing() {
        val s = serve { FakeReply(200, okBody) }
        assertEquals(GeminiError.Key, failed(ask(s, source(), key = " ")))
        assertEquals(GeminiError.Model, failed(ask(s, source(), model = "bad/model")))
        assertTrue(s.requests.isEmpty())
    }

    @Test fun httpErrorsMapToShortErrors() {
        for ((code, err) in listOf(
            400 to GeminiError.Key, 401 to GeminiError.Key, 403 to GeminiError.Denied, 404 to GeminiError.Model,
            429 to GeminiError.Limit, 500 to GeminiError.Busy, 503 to GeminiError.Busy,
        )) {
            val s = serve { FakeReply(code, """{"error":{"message":"secret-key-1234 leaked"}}""") }
            assertEquals("code $code", err, failed(ask(s, source())))
            s.stop()
        }
    }

    @Test fun blockedAndEmptyAnswers() {
        val blocked = serve { FakeReply(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""") }
        assertEquals(GeminiError.Blocked, failed(ask(blocked, source())))
        val empty = serve { FakeReply(200, """{"candidates":[]}""") }
        assertEquals(GeminiError.Empty, failed(ask(empty, source())))
    }

    @Test fun noConnectionIsOffline() {
        val s = serve { FakeReply(200, okBody) }
        val base = s.base
        s.stop()
        val r = runBlocking {
            GeminiAsk.askPdf(source(), "q", "k-1234", "m1", AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL, GeminiEndpoints("$base/v1beta"))
        }
        assertEquals(GeminiError.Offline, failed(r))
    }

    @Test fun aVanishedFileIsAFileErrorAndNotOffline() {
        val s = serve { FakeReply(200, okBody) }
        val src = source()
        src.file.delete()
        assertEquals(GeminiError.File, failed(ask(s, src)))
    }

    @Test fun askPdfDoesNotCloseTheSource() {
        val s = serve { FakeReply(200, okBody) }
        val src = source()
        ask(s, src)
        assertTrue(src.file.exists())
        src.close()
        assertFalse(src.file.exists())
    }

    @Test fun theCopyIsGoneAfterUseEvenOnAnError() {
        val s = serve { FakeReply(401, "") }
        val src = source()
        runBlocking { src.use { ask(s, it) } }
        assertFalse(src.file.exists())
    }

    @Test fun banglaQuestionSurvivesTheTrip() {
        val s = serve { FakeReply(200, okBody) }
        val q = "এই চিত্রে কী দেখানো হয়েছে?"
        ask(s, source(), question = q)
        assertTrue(String(s.requests.single().body, Charsets.UTF_8).contains(q))
    }

    @Test fun cancellingStopsTheRequestAndTheCopyIsStillDeleted() {
        val release = java.util.concurrent.CountDownLatch(1)
        val s = serve { release.await(5, java.util.concurrent.TimeUnit.SECONDS); FakeReply(200, okBody) }
        val src = source()
        runBlocking {
            val job = async(Dispatchers.Default) {
                src.use {
                    GeminiAsk.askPdf(it, "q", "k-1234", "m1", AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL, GeminiEndpoints("${s.base}/v1beta"))
                }
            }
            while (s.requests.isEmpty()) delay(10)
            job.cancel()
            try { job.await(); fail() } catch (e: CancellationException) { }
            release.countDown()
        }
        assertFalse(src.file.exists())
    }
}
