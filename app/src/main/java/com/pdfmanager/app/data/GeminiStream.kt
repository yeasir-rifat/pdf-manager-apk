package com.pdfmanager.app.data

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Reads Server-Sent Events (what `streamGenerateContent?alt=sse` sends). Plain Kotlin, so it runs in unit tests.
 * The bytes are decoded as UTF-8 by one reader over the whole stream, so a Bangla character that is cut in two by
 * the network is joined again before it becomes text. A line longer than [MAX_LINE] is an error, so a broken
 * server cannot fill the memory.
 */
object GeminiSse {
    const val MAX_LINE = 4 * 1024 * 1024

    /**
     * Calls [onData] with the data of every event, in order, while the bytes arrive. An event ends with an empty line.
     * Several `data:` lines of one event are joined with a newline. Comment lines (starting with `:`) and other fields
     * are ignored. A last event that is not closed by an empty line is still delivered.
     * Throws the IOException of [input] when the stream breaks.
     */
    fun read(input: InputStream, onData: (String) -> Unit) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 8192)
        val data = StringBuilder()
        var has = false
        val line = StringBuilder()
        while (true) {
            if (!readLine(reader, line)) break
            if (line.isEmpty()) {
                if (has) { onData(data.toString()); data.setLength(0); has = false }
                continue
            }
            if (line[0] == ':') continue
            if (line.startsWith("data:")) {
                var start = 5
                if (start < line.length && line[start] == ' ') start++
                if (has) data.append('\n')
                data.append(line, start, line.length)
                has = true
            }
        }
        if (has) onData(data.toString())
    }

    /** Fills [out] with the next line without its line break. False at the end of the stream when nothing was read. */
    private fun readLine(r: BufferedReader, out: StringBuilder): Boolean {
        out.setLength(0)
        var any = false
        while (true) {
            val c = r.read()
            if (c < 0) return any
            any = true
            if (c == '\n'.code) return true
            if (c == '\r'.code) {
                r.mark(1)
                val next = r.read()
                if (next != '\n'.code && next >= 0) r.reset()
                return true
            }
            if (out.length >= MAX_LINE) throw IOException("Line too long")
            out.append(c.toChar())
        }
    }
}

/**
 * Collects the events of one streamed answer. Each event is a small generateContent answer holding the next piece of
 * text. [add] returns true when the text grew, so the caller can show it.
 */
class GeminiStreamAccumulator {
    private val buffer = StringBuilder()
    private var finishReason: String? = null
    private var blockReason: String? = null
    private var usage: GeminiUsage? = null

    /** The first error Google sent inside the stream, or null. */
    var streamError: GeminiError? = null
        private set

    /** Everything received so far. */
    val text: String get() = buffer.toString()

    /** Reads one event. An event that is not JSON (a cut off last line) is ignored. */
    fun add(json: String): Boolean {
        val root = DriveJson.parseObject(json) ?: return false
        (root["error"] as? Map<*, *>)?.let { e ->
            if (streamError == null) {
                val code = (e["code"] as? Double)?.toInt() ?: 0
                streamError = GeminiErrors.fromHttp(code) ?: GeminiError.Busy
            }
            return false
        }
        val piece = GeminiResponse.fromRoot(root)
        piece.finishReason?.let { finishReason = it }
        piece.blockReason?.let { blockReason = it }
        piece.usage?.let { usage = it }
        if (piece.text.isEmpty()) return false
        buffer.append(piece.text)
        return true
    }

    /** The answer as far as it got, with the last finish reason, block reason and token counts that came. */
    fun answer(): GeminiAnswer = GeminiAnswer(buffer.toString(), finishReason, blockReason, usage)
}
