package com.pdfmanager.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * A PDF handed to the app by another app ("Open with" / "Share").
 *
 * The file is copied into the app's own cache. That way it does not depend on the other app's
 * temporary read permission (which ends when the process dies), and its address contains only safe
 * characters, so it survives being put into a navigation route.
 */
object IncomingPdf {
    private const val DIR = "incoming"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Copies [uri] to the cache and returns the address of the copy. Throws if it cannot be read or is not a PDF. */
    fun copyToCache(context: Context, uri: Uri): String {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        cleanup(context)
        val target = File(dir, "${System.currentTimeMillis()}-${safeName(displayName(context, uri))}")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open the file")
            input.use { ins -> target.outputStream().use { ins.copyTo(it) } }
            require(looksLikePdf(target)) { "Not a PDF file" }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        return Uri.fromFile(target).toString()
    }

    /** Removes copies older than a day. */
    fun cleanup(context: Context) {
        val limit = System.currentTimeMillis() - MAX_AGE_MS
        File(context.cacheDir, DIR).listFiles()?.forEach { if (it.lastModified() < limit) runCatching { it.delete() } }
        PdfOps.cleanupUnlocked(context)
    }

    /** True when the first bytes hold the "%PDF" marker (some files have a few bytes of junk before it). */
    fun looksLikePdf(file: File): Boolean = runCatching {
        file.inputStream().use { ins ->
            val buf = ByteArray(1024)
            val n = ins.read(buf)
            n >= 4 && String(buf, 0, n, Charsets.ISO_8859_1).contains("%PDF")
        }
    }.getOrDefault(false)

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "shared.pdf"
    }

    /** Keeps letters (any script) and digits; everything else, including '%', becomes '_'. Always ends in .pdf. */
    fun safeName(name: String): String {
        val base = name.substringBeforeLast('.', name)
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ') it else '_' }
            .joinToString("").trim().take(60).ifEmpty { "shared" }
        return "$base.pdf"
    }
}
