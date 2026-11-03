package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiRetryTest {

    private fun limit(body: String = "", headers: Map<String, String> = emptyMap(), code: Int = 429) =
        GeminiHttpResult(code, body, headers)

    @Test fun headerSecondsAreUsed() {
        assertEquals(5, GeminiRetry.seconds(limit(headers = mapOf("retry-after" to "5"))))
    }

    @Test fun bodyDelayIsUsed() {
        assertEquals(34, GeminiRetry.seconds(limit("""{"error":{"details":[{"retryDelay":"34s"}]}}""")))
    }

    @Test fun fractionRoundsUp() {
        assertEquals(35, GeminiRetry.seconds(limit("""{"retryDelay": "34.5s"}""")))
        assertEquals(34, GeminiRetry.seconds(limit("""{"retryDelay": "34.0s"}""")))
    }

    @Test fun headerWinsOverBody() {
        assertEquals(7, GeminiRetry.seconds(limit("""{"retryDelay":"34s"}""", mapOf("retry-after" to "7"))))
    }

    @Test fun noDelayMeansNoWait() {
        assertEquals(0, GeminiRetry.seconds(limit("{}")))
        assertEquals(0, GeminiRetry.seconds(limit("not json")))
        assertEquals(0, GeminiRetry.seconds(limit(headers = mapOf("retry-after" to "Wed, 21 Oct 2026 07:28:00 GMT"))))
    }

    @Test fun waitIsCapped() {
        assertEquals(GeminiRetry.MAX_SECONDS, GeminiRetry.seconds(limit(headers = mapOf("retry-after" to "99999"))))
    }

    @Test fun onlyA429HasAWait() {
        assertEquals(0, GeminiRetry.seconds(limit(headers = mapOf("retry-after" to "5"), code = 503)))
    }

    @Test fun askCarriesTheWaitForALimitError() {
        val server = FakeDriveServer { FakeReply(429, """{"error":{"details":[{"retryDelay":"12s"}]}}""") }
        try {
            val dir = java.nio.file.Files.createTempDirectory("retry").toFile()
            val f = java.io.File(dir, "a.pdf").apply { writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)) }
            val r = kotlinx.coroutines.runBlocking {
                GeminiAsk.askPdf(
                    AiPdfSource(f, f.length(), 1), "Q?", "k-1234", "gemini-flash-latest",
                    AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL, GeminiEndpoints("${server.base}/v1beta"),
                )
            } as GeminiAskResult.Failed
            assertEquals(GeminiError.Limit, r.error)
            assertEquals(12, r.retryAfterSec)
            dir.deleteRecursively()
        } finally {
            server.stop()
        }
    }
}
