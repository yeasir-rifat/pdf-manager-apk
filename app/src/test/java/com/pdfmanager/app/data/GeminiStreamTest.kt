package com.pdfmanager.app.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiStreamTest {

    private var server: FakeDriveServer? = null
    private val dir: File = Files.createTempDirectory("ai-stream").toFile()

    @After fun stop() { server?.stop(); dir.deleteRecursively() }

    private fun ev(text: String, finish: String? = null): String {
        val fin = if (finish != null) ",\"finishReason\":\"$finish\"" else ""
        return "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":${DriveJson.quote(text)}}]}$fin}]}\n\n"
    }

    private fun bytes(vararg events: String) = events.map { it.toByteArray(Charsets.UTF_8) }

    /** Cuts every event in the middle, so words and JSON are split across chunks. */
    private fun cut(all: ByteArray, size: Int): List<ByteArray> = all.toList().chunked(size).map { it.toByteArray() }

    private fun serve(reply: (FakeReq) -> FakeReply): FakeDriveServer = FakeDriveServer(reply).also { server = it }

    private fun client(s: FakeDriveServer, key: String = "k-1234") = GeminiClient(key, GeminiEndpoints("${s.base}/v1beta"))

    private val body = { out: java.io.OutputStream -> out.write("{}".toByteArray()) }

    @Test fun requestGoesToTheStreamEndpointWithTheKeyInAHeader() {
        val s = serve { FakeReply(200, chunks = bytes(ev("a", "STOP"))) }
        val got = ArrayList<String>()
        val r = runBlocking { client(s).generateSse("gemini-flash-latest", body) { got += it } }
        assertEquals(200, r.code)
        assertEquals("", r.body)
        assertEquals(1, got.size)
        val q = s.requests.single()
        assertEquals("/v1beta/models/gemini-flash-latest:streamGenerateContent", q.path)
        assertEquals("alt=sse", q.query)
        assertEquals("k-1234", q.headers["x-goog-api-key"])
        assertEquals("text/event-stream", q.headers["accept"])
        assertFalse((q.path + q.query).contains("k-1234"))
    }

    @Test fun eventsArriveWhileTheStreamIsStillOpen() {
        val s = serve { FakeReply(200, chunks = bytes(ev("one "), ev("two "), ev("three", "STOP")), chunkDelayMs = 120) }
        val times = ArrayList<Long>()
        val start = System.nanoTime()
        runBlocking { client(s).generateSse("m", body) { times += (System.nanoTime() - start) / 1_000_000 } }
        assertEquals(3, times.size)
        // the first event is delivered long before the last one is sent
        assertTrue("first=${times[0]} last=${times[2]}", times[2] - times[0] >= 100)
    }

    private suspend fun askLargeSuspend(s: FakeDriveServer, onText: (String) -> Unit): GeminiAskResult {
        val cache = AiFileCache(MemoryAiStore())
        cache.put(AiFileCache.key("f", "k-1234"), "${s.base}/v1beta/files/abc")
        return GeminiAsk.askLargePdf(
            "f", cache, { AiPdfOpen.Result.Failed(GeminiError.File) }, "Q?", "k-1234", "gemini-flash-latest",
            AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL, endpoints = GeminiEndpoints("${s.base}/v1beta"), onText = onText,
        )
    }

    private fun askLarge(s: FakeDriveServer, onText: (String) -> Unit): GeminiAskResult =
        runBlocking { askLargeSuspend(s, onText) }

    @Test fun textGrowsPieceByPieceAndTheAnswerIsTheWholeText() {
        val s = serve { FakeReply(200, chunks = bytes(ev("Hello "), ev("big "), ev("world", "STOP"))) }
        val seen = ArrayList<String>()
        val r = askLarge(s) { synchronized(seen) { seen += it } }
        assertEquals(listOf("Hello ", "Hello big ", "Hello big world"), seen)
        assertEquals("Hello big world", (r as GeminiAskResult.Answer).answer.text)
        assertEquals("STOP", r.answer.finishReason)
        assertTrue(String(s.requests.single().body, Charsets.UTF_8).contains("file_data"))
    }

    @Test fun wordsAndBanglaSplitInsideChunksComeOutWhole() {
        val bn = "\u09AC\u09BE\u0982\u09B2\u09BE \u09AD\u09BE\u09B7\u09BE \u0995\u09CD\u09B7\u09AE\u09BE"
        val all = (ev("Sp") + ev("lit wo") + ev("rd. ") + ev(bn, "STOP")).toByteArray(Charsets.UTF_8)
        for (size in listOf(3, 5, 11)) {
            val s = serve { FakeReply(200, chunks = cut(all, size), chunkDelayMs = 1) }
            val seen = ArrayList<String>()
            val r = askLarge(s) { synchronized(seen) { seen += it } }
            assertEquals("size $size", "Split word. $bn", (r as GeminiAskResult.Answer).answer.text)
            assertEquals("size $size", "Split word. $bn", seen.last())
            // every text shown on the way is a start of the final text, never a broken character
            for (t in seen) assertTrue("size $size: $t", "Split word. $bn".startsWith(t))
            s.stop()
        }
    }

    @Test fun aBrokenStreamKeepsThePartialTextAndFailsShort() {
        val s = serve { FakeReply(200, chunks = bytes(ev("part one. "), ev("part two")), broken = true) }
        val seen = ArrayList<String>()
        val r = askLarge(s) { synchronized(seen) { seen += it } }
        assertEquals(GeminiError.Offline, (r as GeminiAskResult.Failed).error)
        assertEquals("part one. part two", seen.last())
    }

    @Test fun anErrorInsideTheStreamFailsAndKeepsThePartialText() {
        val err = "data: {\"error\":{\"code\":503,\"message\":\"secret detail\"}}\n\n".toByteArray()
        val s = serve { FakeReply(200, chunks = listOf(ev("so far").toByteArray(), err)) }
        val seen = ArrayList<String>()
        val r = askLarge(s) { synchronized(seen) { seen += it } }
        assertEquals(GeminiError.Busy, (r as GeminiAskResult.Failed).error)
        assertEquals("so far", seen.last())
    }

    @Test fun httpErrorsAreShortAndNothingIsStreamed() {
        // A 403 on a saved file link is treated as an expired link: the link is dropped and the PDF is uploaded again.
        // askLargeSuspend cannot reopen the PDF (openSource returns File), so that case ends as File, not Denied.
        // (GeminiAskLargeTest.aStaleLinkIsTriedOnlyOnceAndNeverLoops covers the real 403 path.)
        for ((code, err) in listOf(429 to GeminiError.Limit, 403 to GeminiError.File, 503 to GeminiError.Busy)) {
            val s = serve { FakeReply(code, "{\"error\":{\"message\":\"secret k-1234\"}}") }
            val seen = ArrayList<String>()
            val r = askLarge(s) { seen += it }
            assertEquals("code $code", err, (r as GeminiAskResult.Failed).error)
            assertTrue(seen.isEmpty())
            s.stop()
        }
    }

    @Test fun aStreamWithoutTextIsAnEmptyAnswer() {
        val s = serve { FakeReply(200, chunks = bytes("data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n")) }
        val r = askLarge(s) { }
        assertEquals(GeminiError.Empty, (r as GeminiAskResult.Failed).error)
    }

    @Test fun aBlockedPromptIsBlocked() {
        val s = serve { FakeReply(200, chunks = bytes("data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}\n\n")) }
        val r = askLarge(s) { }
        assertEquals(GeminiError.Blocked, (r as GeminiAskResult.Failed).error)
    }

    @Test fun stopKeepsWhatCameAndEndsTheRequestQuickly() {
        val many = (1..40).map { ev("piece $it ").toByteArray() }
        val s = serve { FakeReply(200, chunks = many, chunkDelayMs = 60) }
        val first = CountDownLatch(1)
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        runBlocking {
            val job = launch(Dispatchers.Default) {
                askLargeSuspend(s) { seen += it; first.countDown() }
            }
            assertTrue(first.await(5, TimeUnit.SECONDS))
            val t0 = System.nanoTime()
            job.cancelAndJoin()
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("stopped in ${ms}ms", ms < 1500)
        }
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.last().startsWith("piece 1 "))
        assertTrue("whole answer would be longer", seen.last().length < "piece 40 ".length * 40)
    }
}
