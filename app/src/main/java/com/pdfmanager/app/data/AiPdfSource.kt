package com.pdfmanager.app.data

import java.io.Closeable
import java.io.File
import java.io.FileInputStream

/**
 * The limits for sending a PDF to Gemini. Plain Kotlin, so the rules run in unit tests.
 *
 * The whole request may be about 20 MB and base64 adds a third, so inline sending stops at 10 MB.
 * Gemini reads up to 1000 pages of one PDF.
 */
object AiPdfRules {
    /** A PDF of exactly this many bytes still goes inline. One byte more does not. */
    const val INLINE_MAX_BYTES = 10L * 1024 * 1024
    const val MAX_PAGES = 1000

    /** The Files API takes up to 2 GB per file. Over [INLINE_MAX_BYTES] a PDF is uploaded instead of sent inline. */
    const val FILES_MAX_BYTES = 2L * 1024 * 1024 * 1024

    /** Folder inside the cache for the copies made for Ask AI. */
    const val TEMP_DIR = "ai-tmp"

    /**
     * Why a PDF of [sizeBytes] with [pages] pages cannot be sent, or null when it can. Pages is checked first.
     * [maxBytes] is [INLINE_MAX_BYTES] for an inline request and [FILES_MAX_BYTES] for an upload.
     */
    fun refusal(sizeBytes: Long, pages: Int, maxBytes: Long = INLINE_MAX_BYTES): GeminiError? = when {
        sizeBytes <= 0L || pages <= 0 -> GeminiError.File
        pages > MAX_PAGES -> GeminiError.Pages
        sizeBytes > maxBytes -> GeminiError.File
        else -> null
    }
}

/**
 * A private copy of the PDF that is about to be sent. Close it when the request is over, and the copy is deleted.
 * Use it as `source.use { ... }`, so the file goes away on success, on an error and on a cancel.
 * A cut page that [AiPageCache] keeps is the one exception: the cache deletes it.
 */
class AiPdfSource(
    val file: File,
    val sizeBytes: Long,
    /** Pages inside [file]. */
    val pages: Int,
    /** What [file] holds: the whole PDF, one page cut out, or a range cut out. */
    val scope: AiScope = AiScope.Whole,
    /** Pages in the original PDF. For a whole PDF this is [pages]. */
    val totalPages: Int = pages,
    /** True for a cut page kept by [AiPageCache]: closing then leaves the file for the next question. */
    private val cached: Boolean = false,
) : Closeable {

    /** The file as a part of a request. */
    fun part() = GeminiRequest.PdfPart { FileInputStream(file) }

    override fun close() {
        if (!cached) runCatching { file.delete() }
    }
}
