package com.pdfmanager.app.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCheckTest {

    private var server: FakeDriveServer? = null

    @After fun stop() { server?.stop() }

    private fun serve(respond: (FakeReq) -> FakeReply) = FakeDriveServer(respond).also { server = it }

    private fun run(s: FakeDriveServer, key: String = "k-1234", model: String = "gemini-flash-latest") =
        runBlocking { GeminiCheck.run(key, model, GeminiEndpoints("${s.base}/v1beta")) }

    private val okBody = """{"candidates":[{"content":{"parts":[{"text":"OK"}]},"finishReason":"STOP"}]}"""

    @Test fun validKeyGivesOk() {
        val s = serve { FakeReply(200, okBody) }
        assertNull(run(s))
        val req = s.requests.single()
        assertEquals("/v1beta/models/gemini-flash-latest:generateContent", req.path)
        assertTrue(String(req.body, Charsets.UTF_8).contains(GeminiCheck.PROMPT))
    }

    @Test fun wrongKey() {
        assertEquals(GeminiError.Key, run(serve { FakeReply(400, """{"error":{"status":"INVALID_ARGUMENT"}}""") }))
    }

    @Test fun wrongModelName() {
        assertEquals(GeminiError.Model, run(serve { FakeReply(404, """{"error":{"status":"NOT_FOUND"}}""") }))
    }

    @Test fun limitDeniedAndBusy() {
        assertEquals(GeminiError.Limit, run(serve { FakeReply(429, "{}") }))
        assertEquals(GeminiError.Denied, run(serve { FakeReply(403, "{}") }))
        assertEquals(GeminiError.Busy, run(serve { FakeReply(503, "{}") }))
    }

    @Test fun blockedAndEmptyAnswers() {
        assertEquals(GeminiError.Blocked, run(serve { FakeReply(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""") }))
        assertEquals(GeminiError.Empty, run(serve { FakeReply(200, """{"candidates":[]}""") }))
    }

    @Test fun airplaneModeIsOffline() {
        val s = serve { FakeReply(200, okBody) }
        s.stop()
        assertEquals(GeminiError.Offline, run(s))
    }

    @Test fun noKeyAndBadModelSendNothing() {
        val s = serve { FakeReply(200, okBody) }
        assertEquals(GeminiError.Key, run(s, key = " "))
        assertEquals(GeminiError.Model, run(s, model = "a/b"))
        assertEquals(GeminiError.Model, run(s, model = ""))
        assertTrue(s.requests.isEmpty())
    }
}
