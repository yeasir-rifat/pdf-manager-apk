package com.pdfmanager.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    val job = remember { ToolJob().also { it.allowRename = false } }
    // Blocks a second tap while the scanner is starting, which is what made the screen flip/jump
    var launching by remember { mutableStateOf(false) }

    // The scan waits here until the person has named it
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingPages by remember { mutableStateOf(0) }
    var nameInput by remember { mutableStateOf("") }
    // Name used when the dialog is closed without a choice (tap outside or Back), so a scan is never lost
    var defaultName by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        launching = false
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf
            ?: return@rememberLauncherForActivityResult
        pendingUri = pdf.uri
        pendingPages = pdf.pageCount
        defaultName = stamp()
        nameInput = defaultName
    }

    pendingUri?.let { uri ->
        val pages = pendingPages
        val cleaned = nameInput.trim().removeSuffix(".pdf").replace(Regex("[\\\\/:*?\"<>|]"), "_")

        // One place that saves the scan; the Save button and closing the dialog both use it
        fun saveScan(name: String) {
            val finalName = name.ifBlank { defaultName }
            pendingUri = null
            job.launch(scope) {
                val out = OutputStore.savePdf(context, "${finalName}_scanned.pdf") { os ->
                    (context.contentResolver.openInputStream(uri) ?: error("Cannot read the scan"))
                        .use { it.copyTo(os) }
                }
                "Saved $pages ${if (pages == 1) "page" else "pages"}" to out
            }
        }

        AlertDialog(
            modifier = Modifier.dialogEnter(),
            // Tap outside or Back keeps the scan under the typed (or default) name instead of trapping the person
            onDismissRequest = { saveScan(cleaned) },
            title = { Text("Name your scan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        singleLine = true,
                        label = { Text("File name") },
                        suffix = { Text("_scanned.pdf") }
                    )
                    Text(
                        "$pages page${if (pages == 1) "" else "s"} will be saved as ${cleaned.ifBlank { defaultName }}_scanned.pdf",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "If you close this window, the scan is saved with this name. Nothing is lost.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { saveScan(cleaned) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pendingUri = null }) { Text("Discard") } }
        )
    }

    val startScan: () -> Unit = start@{
        if (launching || job.busy) return@start
        val activity = context.findActivity()
        if (activity == null) {
            job.fail("Scanner could not start")
            return@start
        }
        launching = true
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { launching = false; job.fail("Scanner unavailable") }
    }

    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = "Back") }
            }
            Column {
                Text("Scan to PDF", style = MaterialTheme.typography.headlineMedium)
            }
        }

        // Hero card: the one big action on this screen
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(scheme.primaryContainer.copy(alpha = 0.55f), scheme.surfaceContainerLow)
                    )
                )
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier.size(112.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(80.dp).clip(CircleShape).background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        AppIcons.DocumentScanner, null,
                        tint = scheme.onPrimaryContainer, modifier = Modifier.size(40.dp)
                    )
                }
            }
            Text("Scan documents", style = MaterialTheme.typography.titleLarge)
            Text(
                "Point the camera at a page. Edges are found and the image is cleaned up for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                if (launching) "Opening scanner…" else "Start scanning",
                icon = AppIcons.DocumentScanner,
                enabled = !job.busy && !launching,
                onClick = startScan
            )
        }

        // Feature highlights
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ScanFeature(AppIcons.FitScreen, "Auto crop", Modifier.weight(1f))
            ScanFeature(AppIcons.NoteAdd, "Up to 50 pages", Modifier.weight(1f))
            ScanFeature(AppIcons.PhotoLibrary, "From gallery", Modifier.weight(1f))
        }

        JobStatus(job, onOpen)
        RecentResults(job.result, { madeBy(it, "scanned", "scan") }, onOpen)
    }
}

@Composable
private fun ScanFeature(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerLow)
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = scheme.primary, modifier = Modifier.size(26.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}
