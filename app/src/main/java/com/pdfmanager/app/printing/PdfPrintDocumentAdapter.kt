package com.pdfmanager.app.printing

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import com.pdfmanager.app.data.PdfOps
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands one PDF to the Android print system.
 *
 * The page count is already known from the Print Properties screen, so onLayout is instant.
 * If the user typed a page range, only those pages are offered to the print system.
 * onWrite runs in a background thread and never touches the original file:
 * - all pages wanted: the original bytes are copied as they are;
 * - some pages wanted: only those pages are written, using a temporary copy of the document.
 */
class PdfPrintDocumentAdapter(
    private val context: Context,
    private val uri: Uri,
    private val jobName: String,
    private val pageCount: Int,
    /**
     * Pages chosen in the Pages field (0-based, sorted), or null for the whole document.
     * The print system then sees a document made only of these pages, so its page numbers
     * (1..N) are mapped back to the original pages when writing.
     */
    selectedPages: List<Int>? = null,
    /** Temporary PDF made by PrintPreprocessor. Deleted when the print job ends. */
    private val cleanupFile: java.io.File? = null
) : PrintDocumentAdapter() {

    private val chosen: List<Int>? = selectedPages
        ?.filter { it in 0 until pageCount }
        ?.distinct()?.sorted()
    private val presentedCount: Int get() = chosen?.size ?: pageCount

    private val main = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null

    override fun onStart() {
        executor = Executors.newSingleThreadExecutor()
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        if (presentedCount <= 0) {
            callback.onLayoutFailed("This PDF has no pages to print")
            return
        }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(presentedCount)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        val cancelled = AtomicBoolean(false)
        cancellationSignal.setOnCancelListener { cancelled.set(true) }

        // Which original pages (0-based) the print system wants. The system's ranges count
        // pages of the presented document, so they are mapped back to original page numbers.
        val wanted = PageMapping.wanted(pages.map { it.start..it.end }, pageCount, chosen)
        val wantedCount = wanted.count { it }

        val worker = executor ?: Executors.newSingleThreadExecutor().also { executor = it }
        worker.execute {
            var failure: String? = null
            try {
                when {
                    wantedCount == 0 -> failure = "No pages were selected for printing"
                    wantedCount == pageCount -> copyWhole(destination, cancelled).let { failure = it }
                    else -> writeSelected(destination, wanted, cancelled).let { failure = it }
                }
            } catch (e: InvalidPasswordException) {
                failure = "This PDF is password protected. Unlock it in Tools first."
            } catch (e: OutOfMemoryError) {
                failure = "This PDF is too large to print with these pages"
            } catch (e: Exception) {
                failure = "Could not prepare the PDF for printing"
            }
            val error = failure
            main.post {
                when {
                    cancelled.get() -> callback.onWriteCancelled()
                    error != null -> callback.onWriteFailed(error)
                    else -> callback.onWriteFinished(pages)
                }
            }
        }
    }

    /** All pages wanted: copy the original bytes untouched (keeps every detail of the file). */
    private fun copyWhole(destination: ParcelFileDescriptor, cancelled: AtomicBoolean): String? {
        val input = context.contentResolver.openInputStream(uri) ?: return "The PDF is no longer available"
        input.use { src ->
            FileOutputStream(destination.fileDescriptor).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (!cancelled.get()) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.flush()
            }
        }
        return null
    }

    /**
     * Only some pages wanted: build the output from a temporary in-memory/temp-file copy of the
     * document with the other pages removed. The original file is never modified.
     */
    private fun writeSelected(
        destination: ParcelFileDescriptor,
        wanted: BooleanArray,
        cancelled: AtomicBoolean
    ): String? {
        val input = context.contentResolver.openInputStream(uri) ?: return "The PDF is no longer available"
        val doc = input.use { PdfOps.load(it) }
        try {
            if (doc.numberOfPages != pageCount) return "The PDF changed while printing. Please try again."
            if (cancelled.get()) return null
            for (i in pageCount - 1 downTo 0) {
                if (!wanted[i]) doc.removePage(i)
            }
            if (cancelled.get()) return null
            FileOutputStream(destination.fileDescriptor).use { out ->
                doc.save(out)
                out.flush()
            }
        } finally {
            doc.close()
        }
        return null
    }

    override fun onFinish() {
        executor?.shutdown()
        executor = null
        PrintPreprocessor.discard(cleanupFile)
    }
}
