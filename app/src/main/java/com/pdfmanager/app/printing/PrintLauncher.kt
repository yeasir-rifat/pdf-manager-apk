package com.pdfmanager.app.printing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintJob
import android.print.PrintManager

/** Result of asking Android to open its print screen. */
sealed interface PrintLaunch {
    /** [job] can be watched with [PrintJobWatcher]. It may be null if Android gave none. */
    data class Started(val job: PrintJob?) : PrintLaunch
    data class Failed(val message: String) : PrintLaunch
}

/**
 * Opens the Android system print screen for a PDF. The system screen handles choosing the
 * printer, and shows job progress and errors. "Started" only means the print screen opened,
 * not that anything was printed.
 */
object PrintLauncher {

    fun start(
        context: Context,
        uri: Uri,
        displayName: String,
        pageCount: Int,
        attributes: PrintAttributes? = null,
        selectedPages: List<Int>? = null,
        cleanupFile: java.io.File? = null
    ): PrintLaunch {
        val activity = context.findActivity()
        if (activity == null) {
            PrintPreprocessor.discard(cleanupFile)
            return PrintLaunch.Failed("Can't open printing from here")
        }
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            PrintPreprocessor.discard(cleanupFile)
            return PrintLaunch.Failed("Printing is not available on this device")
        }
        val jobName = displayName.ifBlank { "PDF document" }
        return try {
            val job = printManager.print(jobName, PdfPrintDocumentAdapter(activity.applicationContext, uri, jobName, pageCount, selectedPages, cleanupFile), attributes)
            PrintLaunch.Started(job)
        } catch (e: Exception) {
            PrintPreprocessor.discard(cleanupFile)
            PrintLaunch.Failed("Could not start printing")
        }
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
