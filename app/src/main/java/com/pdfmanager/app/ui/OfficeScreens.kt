package com.pdfmanager.app.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.pdfmanager.app.data.OfficeConvert
import com.pdfmanager.app.data.OfficeHelpers
import com.pdfmanager.app.data.OfficeKind
import com.pdfmanager.app.data.PdfOps

/** Remembers that the person has read the Google Drive notice once. It holds no account information. */
object OfficeConsentPref {
    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)
    fun accepted(c: Context): Boolean = prefs(c).getBoolean("office_drive_notice", false)
    fun set(c: Context) = prefs(c).edit().putBoolean("office_drive_notice", true).apply()
}

private fun officeTitle(kind: OfficeKind): String = "${kind.label} to PDF"

private fun officeIcon(kind: OfficeKind): ImageVector = when (kind) {
    OfficeKind.WORD -> AppIcons.WordFile
    OfficeKind.EXCEL -> AppIcons.SheetFile
    OfficeKind.POWERPOINT -> AppIcons.SlidesFile
}

private fun officeAccent(kind: OfficeKind): Color = when (kind) {
    OfficeKind.WORD -> Accent.Indigo
    OfficeKind.EXCEL -> Accent.Green
    OfficeKind.POWERPOINT -> Accent.Orange
}

/**
 * One screen for Word, Excel and PowerPoint to PDF (three routes, one [kind] each). The same building blocks as
 * Text to PDF. Order of events: the button asks for Google permission here on the main thread (and shows a
 * one-time notice first); only when a token is in hand does the background job start.
 */
@Composable
fun OfficeToPdfScreen(kind: OfficeKind, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf<Picked?>(null) }
    var showNotice by remember { mutableStateOf(false) }
    var authorizing by remember { mutableStateOf(false) }
    val job = remember { ToolJob() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) file = Picked(uri, PdfOps.displayName(context, uri))
    }

    val authorize = rememberDriveAuthorizer(
        onToken = { token ->
            authorizing = false
            val f = file
            if (f != null) {
                job.launch(scope) {
                    val r = OfficeConvert.toPdf(
                        context, f.uri, kind, f.name, token,
                        onProgress = { job.progress = it }
                    )
                    r.message() to r.uri
                }
            }
        },
        onMessage = { message ->
            authorizing = false
            job.fail(message)
        }
    )

    val start = {
        job.status = null
        job.failed = false
        job.result = null
        authorizing = true
        authorize()
    }

    // The name is checked at once; size and file content are checked by the engine before anything is uploaded
    val nameProblem = file?.let {
        if (OfficeHelpers.extensionOf(it.name) in kind.allExt) null else "This is not a valid ${kind.label} file"
    }

    ToolScaffold(officeTitle(kind), onBack, bottomBar = {
        PrimaryButton(
            "Convert to PDF", icon = AppIcons.PictureAsPdf,
            enabled = file != null && nameProblem == null && !job.busy && !authorizing,
            onClick = {
                if (OfficeConsentPref.accepted(context)) start() else showNotice = true
            }
        )
    }) {
        FilePickCard("${kind.label} file", file?.name, officeIcon(kind), officeAccent(kind)) {
            picker.launch(OfficeHelpers.pickerMimes(kind))
        }
        if (nameProblem != null) {
            FitText(nameProblem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        JobStatus(job, onOpen)
        RecentResults(job.result, { madeBy(it, kind.prefix) }, onOpen)
    }

    if (showNotice) {
        AlertDialog(
            modifier = androidx.compose.ui.Modifier.dialogEnter(),
            onDismissRequest = { showNotice = false },
            title = { Text("Use your Google Drive?") },
            text = {
                Text(
                    "To make the PDF, this file is sent over the internet to your own Google Drive, " +
                        "converted there, and deleted right after. The app can only see files it makes itself. " +
                        "Use a personal Google account for private files."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    OfficeConsentPref.set(context)
                    showNotice = false
                    start()
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showNotice = false }) { Text("Cancel") } }
        )
    }
}
