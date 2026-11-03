package com.pdfmanager.app.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiSseTest {

    private fun events(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        GeminiSse.read(ByteArrayInputStream(bytes)) { out += it }
        return out
    }

    private fun events(s: String) = events(s.toByteArray(Charsets.UTF_8))

    /** An input that hands out [size] bytes at a time, like a slow network. */
    private fun slow(bytes: ByteArray, size: Int): InputStream = object : InputStream() {
        var pos = 0
        override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(size, len, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test fun oneEventPerBlankLine() {
        assertEquals(listOf("a", "b"), events("data: a\n\ndata: b\n\n"))
    }

    @Test fun spaceAfterColonIsOptional() {
        assertEquals(listOf("a", " b"), events("data:a\n\ndata:  b\n\n"))
    }

    @Test fun crlfLineEndings() {
        assertEquals(listOf("a", "b"), events("data: a\r\n\r\ndata: b\r\n\r\n"))
    }

    @Test fun severalDataLinesAreJoinedWithANewline() {
        assertEquals(listOf("one\ntwo"), events("data: one\ndata: two\n\n"))
    }

    @Test fun commentsAndOtherFieldsAreIgnored() {
        assertEquals(listOf("a"), events(": keep alive\nevent: message\nid: 7\ndata: a\n\n"))
    }

    @Test fun lastEventWithoutBlankLineIsStillDelivered() {
        assertEquals(listOf("a", "b"), events("data: a\n\ndata: b"))
    }

    @Test fun emptyStreamHasNoEvents() {
        assertTrue(events("").isEmpty())
        assertTrue(events("\n\n\n").isEmpty())
    }

    @Test fun everySplitPointGivesTheSameEvents() {
        val text = "data: {\"t\":\"Hello wor\"}\n\ndata: {\"t\":\"ld\"}\n\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val want = listOf("{\"t\":\"Hello wor\"}", "{\"t\":\"ld\"}")
        for (size in 1..7) {
            val got = ArrayList<String>()
            GeminiSse.read(slow(bytes, size)) { got += it }
            assertEquals("chunk size $size", want, got)
        }
    }

    @Test fun banglaCharactersCutInTheMiddleOfTheirBytesAreJoined() {
        val bn = "\u09AC\u09BE\u0982\u09B2\u09BE \u09AD\u09BE\u09B7\u09BE \u0995\u09CD\u09B7"
        val bytes = "data: $bn\n\n".toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size > bn.length * 2)
        for (size in 1..5) {
            val got = ArrayList<String>()
            GeminiSse.read(slow(bytes, size)) { got += it }
            assertEquals("chunk size $size", listOf(bn), got)
        }
    }

    @Test fun emojiOutsideTheBasicPlaneSurvivesSplits() {
        val s = "ok \uD83D\uDE00 done"
        val bytes = "data: $s\n\n".toByteArray(Charsets.UTF_8)
        val got = ArrayList<String>()
        GeminiSse.read(slow(bytes, 1)) { got += it }
        assertEquals(listOf(s), got)
    }

    @Test fun aBrokenStreamThrowsAfterTheEventsThatCame() {
        val input = object : InputStream() {
            val data = "data: a\n\ndata: b\n\n".toByteArray()
            var pos = 0
            override fun read(): Int {
                if (pos >= data.size) throw IOException("lost")
                return data[pos++].toInt() and 0xff
            }
        }
        val got = ArrayList<String>()
        try {
            GeminiSse.read(input) { got += it }
            fail("expected an IOException")
        } catch (e: IOException) {
            assertEquals(listOf("a", "b"), got)
        }
    }

    @Test fun aHugeLineIsAnError() {
        val huge = ByteArray(GeminiSse.MAX_LINE + 10) { 'x'.code.toByte() }
        try {
            GeminiSse.read(ByteArrayInputStream("data: ".toByteArray() + huge)) { }
            fail("expected an IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    // ---- accumulator

    private fun piece(text: String, finish: String? = null): String {
        val fin = if (finish != null) ",\"finishReason\":\"$finish\"" else ""
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":${DriveJson.quote(text)}}]}$fin}]}"
    }

    @Test fun accumulatorJoinsPiecesAndKeepsTheLastFinishReason() {
        val acc = GeminiStreamAccumulator()
        assertTrue(acc.add(piece("Hel")))
        assertTrue(acc.add(piece("lo ")))
        assertTrue(acc.add(piece("world", finish = "STOP")))
        assertEquals("Hello world", acc.text)
        val a = acc.answer()
        assertEquals("Hello world", a.text)
        assertEquals("STOP", a.finishReason)
        assertEquals(GeminiOutcome.Ok, a.outcome)
    }

    @Test fun accumulatorKeepsTheLastUsage() {
        val acc = GeminiStreamAccumulator()
        acc.add("""{"candidates":[{"content":{"parts":[{"text":"a"}]}}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":1,"totalTokenCount":11}}""")
        acc.add("""{"candidates":[{"content":{"parts":[{"text":"b"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2,"totalTokenCount":12}}""")
        assertEquals(12, acc.answer().usage!!.totalTokens)
    }

    @Test fun accumulatorIgnoresEventsThatAreNotJsonOrHaveNoText() {
        val acc = GeminiStreamAccumulator()
        assertFalse(acc.add("{\"candidates\":[{\"content\":{\"par"))
        assertFalse(acc.add("not json"))
        assertFalse(acc.add("""{"candidates":[{"content":{"parts":[{"text":""}]}}]}"""))
        assertEquals("", acc.text)
    }

    @Test fun accumulatorSkipsThoughtParts() {
        val acc = GeminiStreamAccumulator()
        acc.add("""{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true},{"text":"answer"}]}}]}""")
        assertEquals("answer", acc.text)
    }

    @Test fun anErrorInsideTheStreamIsRemembered() {
        val acc = GeminiStreamAccumulator()
        acc.add(piece("partial"))
        assertFalse(acc.add("""{"error":{"code":429,"message":"secret detail"}}"""))
        assertEquals(GeminiError.Limit, acc.streamError)
        assertEquals("partial", acc.text)
        val other = GeminiStreamAccumulator()
        other.add("""{"error":{"message":"odd"}}""")
        assertEquals(GeminiError.Busy, other.streamError)
    }

    @Test fun aBlockedPromptHasNoTextAndIsBlocked() {
        val acc = GeminiStreamAccumulator()
        acc.add("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        assertNull(acc.streamError)
        assertEquals(GeminiError.Blocked, GeminiErrors.fromAnswer(acc.answer()))
    }

    @Test fun cutOffByTheLengthLimitIsPartialButStillShown() {
        val acc = GeminiStreamAccumulator()
        acc.add(piece("long answer", finish = "MAX_TOKENS"))
        assertEquals(GeminiOutcome.Partial, acc.answer().outcome)
        assertNull(GeminiErrors.fromAnswer(acc.answer()))
    }
}
