package com.pdfmanager.app.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Where one page of a text PDF starts and ends: lines [start, endExclusive) of the laid-out text. */
data class LineSpan(val start: Int, val endExclusive: Int)

/**
 * Plain logic of the Convert / Extract tools (PDF to Text, Extract images, Text to PDF).
 * It uses no Android classes, so it is covered by ordinary unit tests.
 */
object ConvertHelpers {

    /** Longest text the app will turn into a PDF, and the biggest text file it will read. */
    const val MAX_TEXT_CHARS = 5_000_000
    const val MAX_TEXT_BYTES = 20 * 1024 * 1024

    /** Images with more pixels than this are skipped: decoding them would run the phone out of memory. */
    const val MAX_IMAGE_PIXELS = 40_000_000L

    /** Text shorter than this is also kept in memory so the Copy button works. */
    const val MAX_COPY_CHARS = 200_000

    // ------------------------------------------------------------ reading text files

    /** Reads at most [maxBytes] bytes; a bigger stream is refused instead of filling the memory. */
    fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IllegalStateException("This file is too large (over ${maxBytes / (1024 * 1024)} MB)")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Turns the bytes of a text file into a String. A byte-order mark decides first (UTF-8, UTF-16).
     * Otherwise strict UTF-8 is tried, and a file that is not valid UTF-8 is read as Windows-1252.
     */
    fun decodeText(bytes: ByteArray): String {
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        if (bytes.size >= 3 && b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && b(0) == 0xFF && b(1) == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && b(0) == 0xFE && b(1) == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    /**
     * One kind of line break (\n), tabs as four spaces, and no control characters.
     * Zero-width joiners (U+200C, U+200D) stay: Bangla conjuncts need them.
     */
    fun normalizeText(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\r' -> {
                    sb.append('\n')
                    if (i + 1 < raw.length && raw[i + 1] == '\n') i++
                }
                c == '\n' || c == ' ' || c == ' ' || c == '\u000C' -> sb.append('\n')
                c == '\t' -> sb.append("    ")
                c == '﻿' -> {}
                c < ' ' || c == '\u007F' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ PDF to Text

    fun pageMarker(page: Int): String = "--- Page $page ---"

    /**
     * Joins the text of the pages ([parts]: 1-based page number and its text) into one document.
     * Pages are separated by a blank line. With [marks] every page starts with "--- Page N ---", even an
     * empty one; without it, pages with no text are left out.
     */
    fun assembleText(parts: List<Pair<Int, String>>, marks: Boolean): String {
        val sb = StringBuilder()
        for ((page, raw) in parts) {
            val body = raw.trimEnd().trimStart('\n', '\r')
            if (!marks && body.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            if (marks) {
                sb.append(pageMarker(page))
                if (body.isNotEmpty()) sb.append("\n\n").append(body)
            } else {
                sb.append(body)
            }
        }
        return sb.toString()
    }

    /** Number of words: groups of characters between white space. */
    fun wordCount(text: String): Int {
        var words = 0
        var inWord = false
        for (c in text) {
            if (Character.isWhitespace(c)) inWord = false
            else if (!inWord) { inWord = true; words++ }
        }
        return words
    }

    /** The first [maxLines] lines, at most [maxChars] characters, with "..." added when something was cut. */
    fun previewOf(text: String, maxChars: Int, maxLines: Int): String {
        var end = text.length
        var cut = false
        var lines = 1
        for (i in text.indices) {
            if (text[i] == '\n') {
                lines++
                if (lines > maxLines) { end = i; cut = true; break }
            }
        }
        if (end > maxChars) { end = maxChars; cut = true }
        // Never cut a surrogate pair in half
        if (cut && end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        val shown = text.substring(0, end).trimEnd()
        return if (cut) "$shown…" else shown
    }

    /**
     * Name of the text file: "Report.txt", or "Report_p1-3_5.txt" when only some pages were read.
     * [rangeText] is what the person typed in the Pages field.
     */
    fun textFileName(source: String?, rangeText: String, isAll: Boolean): String {
        val base = (source ?: "").substringBeforeLast('.').trim().ifEmpty { "Text" }
        val range = rangeText.filter { !it.isWhitespace() }.replace(',', '_').take(40)
        return if (isAll || range.isEmpty()) "$base.txt" else "${base}_p$range.txt"
    }

    // ------------------------------------------------------------ Extract images

    /** "Img_Report_p003_01.jpg": page and image number always use Western digits, whatever the phone language. */
    fun imageFileName(source: String?, page: Int, index: Int, extension: String): String {
        val base = (source ?: "").substringBeforeLast('.').trim().ifEmpty { "PDF" }
        return String.format(Locale.US, "Img_%s_p%03d_%02d.%s", base, page, index, extension)
    }

    fun tooManyPixels(width: Int, height: Int): Boolean = width.toLong() * height.toLong() > MAX_IMAGE_PIXELS

    /** An image is skipped as "small" when both sides are shorter than [minPx] (0 keeps every image). */
    fun isSmall(width: Int, height: Int, minPx: Int): Boolean = maxOf(width, height) < minPx

    // ------------------------------------------------------------ Text to PDF

    /**
     * Cuts laid-out text into pages. [tops] and [bottoms] hold the top and bottom of every line (same
     * length, increasing); [contentH] is the height of the text area of one page. A page takes as many
     * whole lines as fit; a line taller than the page still gets a page of its own.
     */
    fun pageBreaks(tops: IntArray, bottoms: IntArray, contentH: Int): List<LineSpan> {
        require(tops.size == bottoms.size) { "Line lists differ in length" }
        val n = tops.size
        val spans = ArrayList<LineSpan>()
        var start = 0
        while (start < n) {
            var end = start + 1
            while (end < n && bottoms[end] - tops[start] <= contentH) end++
            spans += LineSpan(start, end)
            start = end
        }
        return spans
    }

    // ------------------------------------------------------------ messages

    /** 12345 -> "12,345" with Western digits. */
    fun count(n: Int): String = String.format(Locale.US, "%,d", n)

    fun plural(n: Int, one: String, many: String = one + "s"): String = "${count(n)} ${if (n == 1) one else many}"
}
