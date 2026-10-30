package com.pdfmanager.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.pdfmanager.app.data.OutputStore

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Keeps the screen on while a long job is running. */
@Composable
private fun KeepScreenOn(active: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(active) {
        if (active) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

// ---------------------------------------------------------------- Scan to PDF

@Composable
fun ScanScreen(onBack: (() -> Unit)?, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = remember { ToolJob() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf
            ?: return@rememberLauncherForActivityResult
        job.launch(scope) {
            val out = OutputStore.savePdf(context, outName("Scan", null)) { os ->
                (context.contentResolver.openInputStream(pdf.uri) ?: error("Cannot read the scan"))
                    .use { it.copyTo(os) }
            }
            "Saved ${pdf.pageCount} scanned pages in Documents/PDF Manager" to out
        }
    }

    ToolScaffold("Scan to PDF", onBack) {
        PrimaryButton("Start scanning", icon = AppIcons.DocumentScanner, enabled = !job.busy, onClick = {
            val activity = context.findActivity()
            if (activity == null) {
                job.fail("Could not start the scanner")
                return@PrimaryButton
            }
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(50)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(activity)
                .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { job.fail("Scanner unavailable: ${it.message}") }
        })
        JobStatus(job, onOpen)
        RecentResults(job.result, { it.startsWith("Scan_") }, onOpen)
    }
}
