package com.pdfmanager.app.data

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/**
 * Builds the JSON body for Gemini generateContent. Plain Kotlin, no Android classes, so it runs in unit tests.
 * Strings go through [DriveJson.quote], so quotes, newlines and Bangla text are written safely.
 */
object GeminiRequest {

    /**
     * A PDF that goes with a turn. Either its bytes ([open] gives a fresh stream each time, and the caller closes nothing),
     * or a file Google already holds ([fileUri] from the Files API, see [remote]), which sends no bytes.
     */
    class PdfPart(val fileUri: String? = null, val open: () -> InputStream) {
        companion object {
            /** A file already uploaded to the Files API. The request carries only its address. */
            fun remote(fileUri: String) = PdfPart(fileUri) { throw IOException("Remote file") }
        }
    }

    /**
     * One turn of the conversation. [role] is [USER] or [MODEL].
     * A [pdf] goes in front of the text part of the same turn.
     */
    class Turn(val role: String, val text: String, val pdf: PdfPart? = null)

    private const val READ_BUFFER = 48 * 1024

    const val USER = "user"
    const val MODEL = "model"

    const val TEMPERATURE = 0.4

    /** The role line, the language rule and the length rule, as one system instruction text. */
    fun systemText(language: String, length: String, context: String? = null): String {
        val role = "You are a careful reading assistant inside a PDF viewer. Answer from the document and the question."
        val lang = when (language) {
            AiPrefs.LANG_EN -> "Always answer in English."
            AiPrefs.LANG_BN -> "Always answer in Bangla."
            else -> "Answer in the language of the question."
        }
        val size = when (length) {
            AiPrefs.LEN_SHORT -> "Keep the answer short: a few sentences at most."
            AiPrefs.LEN_LONG -> "Give a detailed answer with all the steps and reasons."
            else -> "Keep the answer a normal length."
        }
        val math = "Write formulas and equations in plain text with Unicode symbols, never LaTeX or dollar signs."
        val base = "$role $lang $size $math"
        return if (context.isNullOrBlank()) base else "$base $context"
    }

    /** Body for a single prompt. */
    fun body(prompt: String, language: String, length: String): String =
        body(listOf(Turn(USER, prompt)), language, length)

    /**
     * Body for a conversation as one string. Meant for small bodies and for tests.
     * A turn with a PDF should go through [write] instead, so the file is never held as one giant string.
     */
    fun body(turns: List<Turn>, language: String, length: String): String {
        val out = ByteArrayOutputStream()
        write(out, turns, language, length)
        return out.toString("UTF-8")
    }

    /**
     * Writes the body to [out] piece by piece. A PDF is read in small blocks and base64 encoded while it is
     * written, so a 10 MB file never turns into one big string in memory. [out] is flushed but not closed.
     * [context] is one extra line for the system instruction (see [AiScope.contextLine]).
     * Throws [GeminiFileException] when the PDF cannot be read. Errors from [out] pass through as IOException.
     */
    fun write(out: OutputStream, turns: List<Turn>, language: String, length: String, context: String? = null) {
        require(turns.isNotEmpty()) { "At least one turn is needed" }
        out.text("{\"system_instruction\":{\"parts\":[{\"text\":")
        out.text(DriveJson.quote(systemText(language, length, context)))
        out.text("}]},\"contents\":[")
        turns.forEachIndexed { i, t ->
            if (i > 0) out.text(",")
            val role = if (t.role == MODEL) MODEL else USER
            out.text("{\"role\":" + DriveJson.quote(role) + ",\"parts\":[")
            t.pdf?.let { writePdfPart(out, it) }
            out.text("{\"text\":" + DriveJson.quote(t.text) + "}]}")
        }
        out.text("],\"generationConfig\":{\"temperature\":$TEMPERATURE}}")
        out.flush()
    }

    /** `{"file_data":{"mime_type":"application/pdf","file_uri":"<uri>"}},` for an uploaded file, or
     * `{"inline_data":{"mime_type":"application/pdf","data":"<base64>"}},` placed before the text part. */
    private fun writePdfPart(out: OutputStream, pdf: PdfPart) {
        pdf.fileUri?.let { uri ->
            out.text("{\"file_data\":{\"mime_type\":\"application/pdf\",\"file_uri\":" + DriveJson.quote(uri) + "}},")
            return
        }
        out.text("{\"inline_data\":{\"mime_type\":\"application/pdf\",\"data\":\"")
        val input = try { pdf.open() } catch (e: IOException) { throw GeminiFileException() }
        input.use {
            val enc = Base64.getEncoder().wrap(KeepOpen(out))
            val buf = ByteArray(READ_BUFFER)
            while (true) {
                val n = try { it.read(buf) } catch (e: IOException) { throw GeminiFileException() }
                if (n < 0) break
                enc.write(buf, 0, n)
            }
            enc.close() // writes the last base64 characters and padding, [out] stays open
        }
        out.text("\"}},")
    }

    private fun OutputStream.text(s: String) = write(s.toByteArray(Charsets.UTF_8))

    /** Lets the base64 stream finish without closing the connection stream under it. */
    private class KeepOpen(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
        override fun close() { flush() }
    }
}

/**
 * The PDF to send could not be read from the phone. Not a network problem, so it is not an IOException,
 * and it passes through [GeminiClient] untouched. The message is fixed text and holds no path.
 */
class GeminiFileException : RuntimeException("Could not read PDF")
