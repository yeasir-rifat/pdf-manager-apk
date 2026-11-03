package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiErrorsTest {

    @Test fun everyHttpCodeMapsAsPlanned() {
        assertEquals(GeminiError.Key, GeminiErrors.fromHttp(400))
        assertEquals(GeminiError.Key, GeminiErrors.fromHttp(401))
        assertEquals(GeminiError.Denied, GeminiErrors.fromHttp(403))
        assertEquals(GeminiError.Model, GeminiErrors.fromHttp(404))
        assertEquals(GeminiError.Limit, GeminiErrors.fromHttp(429))
        assertEquals(GeminiError.Busy, GeminiErrors.fromHttp(500))
        assertEquals(GeminiError.Busy, GeminiErrors.fromHttp(503))
        assertEquals(GeminiError.Busy, GeminiErrors.fromHttp(502))
        assertEquals(GeminiError.Timeout, GeminiErrors.fromHttp(408))
        assertEquals(GeminiError.Timeout, GeminiErrors.fromHttp(504))
    }

    @Test fun unexpectedCodesAreBusyNotACrash() {
        for (code in listOf(0, 100, 301, 402, 418, 599)) assertEquals(code.toString(), GeminiError.Busy, GeminiErrors.fromHttp(code))
    }

    @Test fun payloadTooLargeIsAFileProblem() {
        assertEquals(GeminiError.File, GeminiErrors.fromHttp(413))
    }

    @Test fun successCodesAreNoError() {
        for (code in listOf(200, 201, 204, 299)) assertNull(GeminiErrors.fromHttp(code))
    }

    @Test fun networkFailures() {
        assertEquals(GeminiError.Offline, GeminiErrors.fromNetwork(GeminiNetworkException(timeout = false)))
        assertEquals(GeminiError.Timeout, GeminiErrors.fromNetwork(GeminiNetworkException(timeout = true)))
    }

    @Test fun blockedAndEmptyAnswers() {
        assertEquals(GeminiError.Blocked, GeminiErrors.fromAnswer(GeminiResponse.parse("""{"promptFeedback":{"blockReason":"SAFETY"}}""")))
        assertEquals(GeminiError.Blocked, GeminiErrors.fromAnswer(GeminiResponse.parse("""{"candidates":[{"finishReason":"SAFETY"}]}""")))
        assertEquals(GeminiError.Empty, GeminiErrors.fromAnswer(GeminiResponse.parse("""{"candidates":[]}""")))
        assertEquals(GeminiError.Empty, GeminiErrors.fromAnswer(null))
    }

    @Test fun answersWithTextAreNoError() {
        assertNull(GeminiErrors.fromAnswer(GeminiResponse.parse("""{"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}]}""")))
        assertNull(GeminiErrors.fromAnswer(GeminiResponse.parse("""{"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"MAX_TOKENS"}]}""")))
    }

    @Test fun fromResponseChecksCodeThenAnswer() {
        assertEquals(GeminiError.Limit, GeminiErrors.fromResponse(GeminiHttpResult(429, """{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}""")))
        assertEquals(GeminiError.Empty, GeminiErrors.fromResponse(GeminiHttpResult(200, "<html>")))
        assertEquals(GeminiError.Blocked, GeminiErrors.fromResponse(GeminiHttpResult(200, """{"promptFeedback":{"blockReason":"OTHER"}}""")))
        assertNull(GeminiErrors.fromResponse(GeminiHttpResult(200, """{"candidates":[{"content":{"parts":[{"text":"OK"}]},"finishReason":"STOP"}]}""")))
    }

    @Test fun serverTextNeverReachesTheMessage() {
        val result = GeminiHttpResult(400, """{"error":{"message":"API key AIza-secret is not valid"}}""")
        val error = GeminiErrors.fromResult(result)!!
        assertEquals(GeminiError.Key, error)
        assertTrue(!error.name.contains("secret"))
    }

    @Test fun everyErrorHasItsOwnString() {
        val ids = GeminiError.values().map { GeminiErrors.messageRes(it) }
        assertEquals(GeminiError.values().size, ids.toSet().size)
    }

    @Test fun a400AboutTheRequestIsNotAKeyProblem() {
        val big = GeminiHttpResult(400, """{"error":{"message":"Request payload size exceeds the limit"}}""")
        assertEquals(GeminiError.File, GeminiErrors.fromResult(big))
        val pages = GeminiHttpResult(400, """{"error":{"message":"The document has too many pages"}}""")
        assertEquals(GeminiError.File, GeminiErrors.fromResult(pages))
        val key = GeminiHttpResult(400, """{"error":{"message":"API key not valid. Please pass a valid API key."}}""")
        assertEquals(GeminiError.Key, GeminiErrors.fromResult(key))
        assertEquals(GeminiError.Key, GeminiErrors.fromResult(GeminiHttpResult(400, "")))
        assertEquals(GeminiError.File, GeminiErrors.fromHttp(413))
    }

    @Test fun systemTextAsksForPlainFormulas() {
        val t = GeminiRequest.systemText(AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL)
        assertTrue(t.contains("never LaTeX"))
        assertTrue(!t.contains('\u2014'))
    }
}
