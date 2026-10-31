package com.pdfmanager.app.data

import android.content.Context
import com.pdfmanager.app.printing.PrintPreprocessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Step 11. Removes copies of the user's PDF content that the app keeps in its cache folder.
 *
 * - print-tmp: temporary PDFs made for printing. A print job cannot outlive the app process, so
 *   on the first start of a process everything left in this folder is an orphan and is deleted.
 * - office-tmp: PDFs that came back from Google Drive and were not yet saved (Office to PDF). Same rule as print-tmp.
 * - Drive leftovers: temporary Drive files whose deletion failed earlier are deleted now, but only when the journal
 *   lists some and Google already agreed once (a silent token); otherwise there is no network use and no screen.
 * - thumbs: page previews. They are only a speed-up, so previews not used for [THUMB_MAX_AGE_MS]
 *   are deleted and made again when needed.
 *
 * Nothing here touches the user's own PDF files. Runs once per process, off the main thread.
 */
object PrivacyCleanup {

    private const val THUMB_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    private val started = AtomicBoolean(false)

    fun runOnce(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val now = System.currentTimeMillis()
            runCatching { deleteOlderThan(File(appContext.cacheDir, PrintPreprocessor.TEMP_DIR), now + 1) }
            runCatching { deleteOlderThan(File(appContext.cacheDir, OfficeConvert.TEMP_DIR), now + 1) }
            runCatching { OfficeConvert.retryLeftovers(appContext) }
            runCatching { deleteOlderThan(File(appContext.cacheDir, "thumbs"), now - THUMB_MAX_AGE_MS) }
        }
    }

    /**
     * Deletes plain files in [dir] last changed before [cutoffMillis]. Returns how many were
     * deleted. Does not go into sub-folders and does nothing if [dir] does not exist.
     */
    fun deleteOlderThan(dir: File, cutoffMillis: Long): Int {
        var deleted = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoffMillis && runCatching { f.delete() }.getOrDefault(false)) deleted++
        }
        return deleted
    }
}
