package com.pdfmanager.app.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiClientTest {

    private var server: FakeDriveServer? = null

    @After fun stop() { server?.stop() }

    private fun serve(respond: (FakeReq) -> FakeReply): FakeDriveServer =
        FakeDriveServer(respond).also { server = it }

    private fun client(s: FakeDriveServer, key: String = "secret-key-1234", readMs: Int = 5_000) =
        GeminiClient(key, GeminiEndpoints("${s.base}/v1beta"), connectMs = 2_000, readMs = readMs)

    private val body = GeminiRequest.body("Say OK", AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL)

    @Test fun sendsKeyInHeaderNeverInUrl() = runBlocking {
        val s = serve { FakeReply(200, """{"candidates":[]}""") }
        client(s).generate("gemini-flash-latest", body)
        val req = s.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/v1beta/models/gemini-flash-latest:generateContent", req.path)
        assertEquals("secret-key-1234", req.headers["x-goog-api-key"])
        assertFalse(req.path.contains("secret-key-1234"))
        assertTrue(req.query == null || !req.query.contains("secret-key-1234"))
        assertTrue(req.headers["content-type"]!!.startsWith("application/json"))
        assertEquals(body, String(req.body, Charsets.UTF_8))
    }

    @Test fun successReturnsCodeAndText() = runBlocking {
        val s = serve { FakeReply(200, """{"text":"বাংলা OK"}""") }
        val r = client(s).generate("m1", body)
        assertTrue(r.ok)
        assertEquals(200, r.code)
        assertEquals("""{"text":"বাংলা OK"}""", r.body)
    }

    @Test fun errorCodesComeBackAsResultsNotExceptions() = runBlocking {
        for (code in listOf(400, 401, 403, 404, 429, 500, 503)) {
            val s = serve { FakeReply(code, """{"error":{"code":$code}}""") }
            val r = client(s).generate("m1", body)
            assertEquals(code, r.code)
            assertFalse(r.ok)
            // On a 401 HttpURLConnection (streaming mode) gives no error stream at all, so there is no body to check
            if (code != 401) assertTrue(r.body.contains("\"code\":$code"))
            s.stop()
        }
    }

    @Test fun noConnectionIsANetworkException() = runBlocking {
        val s = serve { FakeReply(200) }
        val c = client(s)
        s.stop()
        try {
            c.generate("m1", body)
            fail("expected GeminiNetworkException")
        } catch (e: GeminiNetworkException) {
            assertFalse(e.timeout)
            assertFalse(e.message.orEmpty().contains("secret-key-1234"))
        }
    }

    @Test fun slowServerIsATimeout() = runBlocking {
        val s = serve { Thread.sleep(1_500); FakeReply(200, "{}") }
        try {
            client(s, readMs = 300).generate("m1", body)
            fail("expected GeminiNetworkException")
        } catch (e: GeminiNetworkException) {
            assertTrue(e.timeout)
        }
    }

    @Test fun cancellingStopsTheCallAtOnce() = runBlocking {
        val gotRequest = CountDownLatch(1)
        val s = serve { gotRequest.countDown(); Thread.sleep(4_000); FakeReply(200, "{}") }
        val started = System.nanoTime()
        val job = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) { client(s, readMs = 20_000).generate("m1", body) }
        assertTrue(gotRequest.await(3, TimeUnit.SECONDS))
        job.cancel()
        try {
            job.await()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue("took $seconds s", seconds < 3.0)
    }

    @Test fun badModelIdsAreRefusedBeforeAnyRequest() = runBlocking {
        val s = serve { FakeReply(200) }
        for (bad in listOf("", "a/b", "a b", "m?x=1", "m:generateContent", "../x", "a\nb")) {
            try {
                client(s).generate(bad, body)
                fail("expected IllegalArgumentException for '$bad'")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(s.requests.isEmpty())
    }

    @Test fun emptyKeyIsRefusedBeforeAnyRequest() = runBlocking {
        val s = serve { FakeReply(200) }
        try {
            client(s, key = "  ").generate("m1", body)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(s.requests.isEmpty())
    }

    @Test fun modelIdCheck() {
        assertTrue(GeminiClient.isValidModelId("gemini-2.5-flash"))
        assertTrue(GeminiClient.isValidModelId("gemini-flash-latest"))
        assertFalse(GeminiClient.isValidModelId("models/gemini"))
        assertFalse(GeminiClient.isValidModelId("a".repeat(101)))
    }

    @Test fun defaultTimeoutsMatchThePlan() {
        assertEquals(20_000, GeminiClient.CONNECT_MS)
        assertEquals(120_000, GeminiClient.READ_MS)
    }
}
