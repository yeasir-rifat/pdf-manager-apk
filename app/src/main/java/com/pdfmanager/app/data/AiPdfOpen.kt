package com.pdfmanager.app.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Makes the private copy of a PDF for Ask AI: size and page count are read from the copy itself,
 * so they cannot change between the check and the send. The copy lives in cache/[AiPdfRules.TEMP_DIR].
 */
object AiPdfOpen {

    sealed interface Result {
        /** The caller owns [source] and must close it. */
        class Ready(val source: AiPdfSource) : Result
        class Failed(val error: GeminiError) : Result
    }

    /**
     * The copy for [scope]. Whole is the file as it is. A Page or a Range is cut out of the PDF at [uri] into a small
     * temp PDF, so a page of a 200 MB file still goes inline. The caller owns the source and must close it.
     * A cut Page is kept by [AiPageCache], so asking again on the same page does not cut it again.
     */
    suspend fun forScope(context: Context, uri: Uri, scope: AiScope): Result = when (scope) {
        AiScope.Whole -> open(context, uri)
        is AiScope.Page, is AiScope.Range -> cut(context, uri, scope)
    }

    /** Deletes the kept page copies. Call it when the viewer closes and from Clear AI data. */
    fun clearCache() = AiPageCache.clear()

    private suspend fun cut(context: Context, uri: Uri, scope: AiScope): Result = withContext(Dispatchers.IO) {
        val cacheKey = (scope as? AiScope.Page)?.let { AiPageCache.key(uri.toString(), it.number) }
        cacheKey?.let { k -> AiPageCache.get(k)?.let { return@withContext Result.Ready(it) } }
        val dir = File(context.cacheDir, AiPdfRules.TEMP_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext Result.Failed(GeminiError.File)
        val file = File(dir, "ask-${System.nanoTime()}.pdf")
        var keep = false
        try {
            val src = (context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open the file"))
                .use { PdfOps.load(it) }
            var total = 0
            var count = 0
            try {
                total = src.numberOfPages
                val wanted = scope.pageNumbers(total)
                if (!scope.isValidFor(total)) return@withContext Result.Failed(GeminiError.File)
                if (wanted.size > AiPdfRules.MAX_PAGES) return@withContext Result.Failed(GeminiError.Pages)
                val dst = PDDocument(PdfOps.memory())
                try {
                    for (n in wanted) {
                        currentCoroutineContext().ensureActive()
                        dst.importPage(src.getPage(n - 1))
                    }
                    // Links point at pages of the original and would pull the whole file into the copy
                    for (i in 0 until dst.numberOfPages) runCatching { dst.getPage(i).annotations = emptyList() }
                    FileOutputStream(file).use { dst.save(it) }
                    count = wanted.size
                } finally {
                    runCatching { dst.close() }
                }
            } finally {
                runCatching { src.close() }
            }
            val size = file.length()
            // A cut that is over the inline limit is uploaded by the caller, so only the Files API limit applies here
            AiPdfRules.refusal(size, count, AiPdfRules.FILES_MAX_BYTES)?.let { return@withContext Result.Failed(it) }
            keep = true
            if (cacheKey != null) {
                Result.Ready(AiPageCache.put(cacheKey, file, size, scope as AiScope.Page, total))
            } else {
                Result.Ready(AiPdfSource(file, size, count, scope, total))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Result.Failed(GeminiError.File)
        } catch (e: SecurityException) {
            Result.Failed(GeminiError.File)
        } catch (e: RuntimeException) {
            // PDFBox can throw runtime errors on a damaged PDF
            Result.Failed(GeminiError.File)
        } catch (e: OutOfMemoryError) {
            Result.Failed(GeminiError.File)
        } finally {
            if (!keep) runCatching { file.delete() }
        }
    }

    /** Name and size of the PDF, to look up an uploaded copy before anything is copied. Null when the size is unknown. */
    class Identity(val fileKey: String, val sizeBytes: Long)

    suspend fun identify(context: Context, uri: Uri): Identity? = withContext(Dispatchers.IO) {
        val size = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: return@withContext null
        if (size <= 0L) return@withContext null
        Identity(FileKey.of(PdfOps.displayName(context, uri), size), size)
    }

    /** Like [open], but allows a file up to [AiPdfRules.FILES_MAX_BYTES] and checks the free space first. */
    suspend fun openForUpload(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val known = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (known > 0 && context.cacheDir.usableSpace < known + SPACE_MARGIN) return@withContext Result.Failed(GeminiError.File)
        open(context, uri, AiPdfRules.FILES_MAX_BYTES)
    }

    private const val SPACE_MARGIN = 16L * 1024 * 1024

    suspend fun open(context: Context, uri: Uri, maxBytes: Long = AiPdfRules.INLINE_MAX_BYTES): Result = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, AiPdfRules.TEMP_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext Result.Failed(GeminiError.File)
        val file = File(dir, "ask-${System.nanoTime()}.pdf")
        var keep = false
        try {
            val size = copyCapped(context, uri, file, maxBytes) ?: return@withContext Result.Failed(GeminiError.File)
            val pages = pageCount(file)
            AiPdfRules.refusal(size, pages, maxBytes)?.let { return@withContext Result.Failed(it) }
            keep = true
            Result.Ready(AiPdfSource(file, size, pages))
        } catch (e: java.io.IOException) {
            Result.Failed(GeminiError.File)
        } catch (e: SecurityException) {
            Result.Failed(GeminiError.File)
        } finally {
            if (!keep) runCatching { file.delete() }
        }
    }

    /** Copies at most [maxBytes] bytes. Returns the size, or null when the file is bigger. */
    private suspend fun copyCapped(context: Context, uri: Uri, to: File, maxBytes: Long): Long? {
        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open the file")
        var total = 0L
        input.use { src ->
            to.outputStream().use { dst ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return null
                    dst.write(buf, 0, n)
                }
            }
        }
        return total
    }

    /** 0 when the file cannot be read as a PDF. */
    private fun pageCount(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
    }.getOrDefault(0)
}

/**
 * The last few one page copies, so a second question on the same page reuses the file.
 * At most [MAX] pages are kept. Evicted and cleared copies are deleted. Whatever is left when the app dies is removed
 * at the next start by PrivacyCleanup, because the copies live in the Ask AI temp folder.
 */
object AiPageCache {
    private const val MAX = 3
    private class Entry(val file: File, val size: Long, val total: Int, val scope: AiScope.Page)

    private val entries = LinkedHashMap<String, Entry>()

    fun key(uriString: String, page: Int): String = "$page|$uriString"

    /** A source for the kept copy, or null when there is none or the file is gone. The cache still owns the file. */
    @Synchronized
    fun get(key: String): AiPdfSource? {
        val e = entries.remove(key) ?: return null
        if (!e.file.isFile || e.file.length() != e.size) {
            runCatching { e.file.delete() }
            return null
        }
        entries[key] = e // newest last
        return AiPdfSource(e.file, e.size, 1, e.scope, e.total, cached = true)
    }

    /** Keeps [file] under [key] and returns a source that leaves the file alone when it is closed. */
    @Synchronized
    fun put(key: String, file: File, size: Long, scope: AiScope.Page, total: Int): AiPdfSource {
        entries.remove(key)?.let { if (it.file != file) runCatching { it.file.delete() } }
        entries[key] = Entry(file, size, total, scope)
        while (entries.size > MAX) {
            val oldest = entries.keys.first()
            entries.remove(oldest)?.let { runCatching { it.file.delete() } }
        }
        return AiPdfSource(file, size, 1, scope, total, cached = true)
    }

    @Synchronized
    fun clear() {
        entries.values.forEach { runCatching { it.file.delete() } }
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}
