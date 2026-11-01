package com.pdfmanager.app.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Page counts for the Recent files cards. The count is not stored in the database, so it is read
 * once per file version with [PdfRenderer] and remembered in memory. [stamp] changes whenever the
 * file changes, so an edited file is read again.
 */
object PageCountCache {
    private val counts = ConcurrentHashMap<String, Int>()

    // Two reads at a time at most, so a long list never opens many files at once
    private val gate = Semaphore(2)

    private fun key(uri: String, stamp: String) = "$uri|$stamp"

    /** The remembered count, or null when it has not been read yet. 0 means the file could not be read. */
    fun peek(uri: String, stamp: String): Int? = counts[key(uri, stamp)]

    /** Reads the page count off the main thread. Returns 0 when the file is locked or damaged. */
    suspend fun load(context: Context, uri: String, stamp: String): Int {
        val k = key(uri, stamp)
        counts[k]?.let { return it }
        return gate.withPermit {
            counts[k] ?: withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { fd ->
                        PdfRenderer(fd).use { it.pageCount }
                    }
                }.getOrNull() ?: 0
            }.also { counts[k] = it }
        }
    }
}
