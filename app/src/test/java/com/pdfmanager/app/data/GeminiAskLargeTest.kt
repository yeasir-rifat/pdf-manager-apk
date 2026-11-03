package com.pdfmanager.app.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAskLargeTest {

    private var server: FakeDriveServer? = null
    private val dir: File = Files.createTempDirectory("ai-large").toFile()

    @After fun stop() { server?.stop(); dir.deleteRecursively() }

    private lateinit var srv: FakeDriveServer
    private val okBody = """{"candidates":[{"content":{"parts":[{"text":"Defined on page 4."}]},"finishReason":"STOP"}]}"""
    private val cache = AiFileCache(MemoryAiStore())
    private val fast = GeminiClient.Poll(5, 500)
    private var opened = 0

    private fun fileUri(id: String = "abc123") = "${srv.base}/v1beta/files/$id"

    /** Counts uploads and questions. [generateReply] decides each question by the file it names. */
    private fun serve(generateReply: (String) -> FakeReply = { FakeReply(200, okBody) }): FakeDriveServer {
        srv = FakeDriveServer { r ->
            when {
                r.method == "POST" && r.path == "/upload/v1beta/files" ->
                    FakeReply(200, "", mapOf("X-Goog-Upload-URL" to "${srv.base}/upload/v1beta/files/s1"))
                r.method == "POST" && r.path == "/upload/v1beta/files/s1" ->
                    FakeReply(200, """{"file":{"name":"files/abc123","uri":"${fileUri()}","state":"ACTIVE"}}""")
                r.method == "POST" && r.path.endsWith(":generateContent") -> generateReply(String(r.body, Charsets.UTF_8))
                else -> FakeReply(404)
            }
        }
        server = srv
        return srv
    }

    private suspend fun openCopy(): AiPdfOpen.Result {
        opened++
        val f = File(dir, "copy-$opened.pdf").apply { writeBytes(ByteArray(2000) { 7 }) }
        return AiPdfOpen.Result.Ready(AiPdfSource(f, f.length(), 30))
    }

    private fun ask(s: FakeDriveServer, key: String = "k-1234", fileKey: String = "f_1", history: List<GeminiRequest.Turn> = emptyList(), progress: (Int) -> Unit = {}) =
        runBlocking {
            GeminiAsk.askLargePdf(
                fileKey, cache, { openCopy() }, "Where is it defined?", key, "gemini-flash-latest",
                AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL, history, "The reader is currently on page 4 of 30.",
                progress, GeminiEndpoints("${s.base}/v1beta"), fast,
            )
        }

    private fun uploads(s: FakeDriveServer) = s.requests.count { it.path.startsWith("/upload/") }
    private fun questions(s: FakeDriveServer) = s.requests.filter { it.path.endsWith(":generateContent") }

    @Test fun firstQuestionUploadsThenAsksWithTheFileLink() {
        val s = serve()
        val r = ask(s)
        assertEquals("Defined on page 4.", (r as GeminiAskResult.Answer).answer.text)
        assertEquals(2, uploads(s))
        val q = String(questions(s).single().body, Charsets.UTF_8)
        assertTrue(q.contains("file_data"))
        assertTrue(q.contains(fileUri()))
        assertFalse(q.contains("inline_data"))
        assertTrue(q.contains("The reader is currently on page 4 of 30."))
    }

    @Test fun secondQuestionOnTheSameFileDoesNotUploadOrCopyAgain() {
        val s = serve()
        ask(s)
        val before = uploads(s)
        val openedBefore = opened
        val r = ask(s)
        assertTrue(r is GeminiAskResult.Answer)
        assertEquals(before, uploads(s))
        assertEquals(openedBefore, opened)
        assertEquals(2, questions(s).size)
    }

    @Test fun anotherFileUploadsAgain() {
        val s = serve()
        ask(s, fileKey = "f_1")
        ask(s, fileKey = "f_2")
        assertEquals(4, uploads(s))
    }

    @Test fun anotherApiKeyUploadsAgain() {
        val s = serve()
        ask(s, key = "k-1111")
        ask(s, key = "k-2222")
        assertEquals(4, uploads(s))
    }

    @Test fun expiredLinkFallsBackToAFreshUploadOnce() {
        val calls = AtomicInteger()
        val s = serve { if (calls.incrementAndGet() == 1) FakeReply(403, "{}") else FakeReply(200, okBody) }
        cache.put(AiFileCache.key("f_1", "k-1234"), fileUri("old"))
        val r = ask(s)
        assertEquals("Defined on page 4.", (r as GeminiAskResult.Answer).answer.text)
        assertEquals(2, questions(s).size)
        assertTrue(String(questions(s)[0].body, Charsets.UTF_8).contains("files/old"))
        assertEquals(2, uploads(s))
        assertEquals(fileUri(), cache.get(AiFileCache.key("f_1", "k-1234")))
    }

    @Test fun aStaleLinkIsTriedOnlyOnceAndNeverLoops() {
        val s = serve { FakeReply(403, "{}") }
        cache.put(AiFileCache.key("f_1", "k-1234"), fileUri("old"))
        val r = ask(s)
        assertEquals(GeminiError.Denied, (r as GeminiAskResult.Failed).error)
        assertEquals(2, questions(s).size)
        assertEquals(2, uploads(s))
    }

    @Test fun limitOrBusyKeepsTheSavedLinkAndDoesNotUpload() {
        for (code in listOf(429, 503)) {
            val s = serve { FakeReply(code, "{}") }
            val key = AiFileCache.key("f_$code", "k-1234")
            cache.put(key, fileUri("keep"))
            val r = ask(s, fileKey = "f_$code")
            assertTrue(r is GeminiAskResult.Failed)
            assertEquals(0, uploads(s))
            assertEquals(fileUri("keep"), cache.get(key))
        }
    }

    @Test fun uploadedCopyIsDeletedAfterwards() {
        val s = serve()
        ask(s)
        assertFalse(File(dir, "copy-1.pdf").exists())
    }

    @Test fun cachedLinkNeedsNoCopyAtAll() {
        val s = serve()
        cache.put(AiFileCache.key("f_1", "k-1234"), fileUri())
        ask(s)
        assertEquals(0, opened)
    }

    @Test fun uploadErrorIsShortAndNothingIsAsked() {
        srv = FakeDriveServer { FakeReply(429) }
        server = srv
        val r = ask(srv)
        assertEquals(GeminiError.Limit, (r as GeminiAskResult.Failed).error)
        assertTrue(questions(srv).isEmpty())
        assertFalse(File(dir, "copy-1.pdf").exists())
    }

    @Test fun failedCopyIsReturnedAsIs() {
        val s = serve()
        val r = runBlocking {
            GeminiAsk.askLargePdf(
                "f_1", cache, { AiPdfOpen.Result.Failed(GeminiError.File) }, "q", "k", "gemini-flash-latest",
                AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL, endpoints = GeminiEndpoints("${s.base}/v1beta"),
            )
        }
        assertEquals(GeminiError.File, (r as GeminiAskResult.Failed).error)
        assertTrue(s.requests.isEmpty())
    }

    @Test fun progressIsPassedOn() {
        val s = serve()
        val seen = java.util.Collections.synchronizedList(ArrayList<Int>())
        ask(s, progress = { seen += it })
        assertEquals(100, seen.last())
    }

    @Test fun noKeyOrBadModelSendsNothing() {
        val s = serve()
        val none = runBlocking {
            GeminiAsk.askLargePdf("f", cache, { openCopy() }, "q", " ", "gemini-flash-latest", AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL, endpoints = GeminiEndpoints("${s.base}/v1beta"))
        }
        assertEquals(GeminiError.Key, (none as GeminiAskResult.Failed).error)
        val bad = runBlocking {
            GeminiAsk.askLargePdf("f", cache, { openCopy() }, "q", "k", "bad/model", AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL, endpoints = GeminiEndpoints("${s.base}/v1beta"))
        }
        assertEquals(GeminiError.Model, (bad as GeminiAskResult.Failed).error)
        assertTrue(s.requests.isEmpty())
    }

    @Test fun limitsForUploadsMatchThePlan() {
        assertEquals(2L * 1024 * 1024 * 1024, AiPdfRules.FILES_MAX_BYTES)
        val over = AiPdfRules.INLINE_MAX_BYTES + 1
        assertEquals(GeminiError.File, AiPdfRules.refusal(over, 5))
        assertEquals(null, AiPdfRules.refusal(over, 5, AiPdfRules.FILES_MAX_BYTES))
        assertEquals(null, AiPdfRules.refusal(AiPdfRules.FILES_MAX_BYTES, 5, AiPdfRules.FILES_MAX_BYTES))
        assertEquals(GeminiError.File, AiPdfRules.refusal(AiPdfRules.FILES_MAX_BYTES + 1, 5, AiPdfRules.FILES_MAX_BYTES))
        assertEquals(GeminiError.Pages, AiPdfRules.refusal(over, 1001, AiPdfRules.FILES_MAX_BYTES))
    }
}
