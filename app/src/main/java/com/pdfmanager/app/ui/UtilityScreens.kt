package com.pdfmanager.app.ui

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pdfmanager.app.data.MetaFields
import com.pdfmanager.app.data.MetadataHelpers
import com.pdfmanager.app.data.MetadataSnapshot
import com.pdfmanager.app.data.OutputStore
import com.pdfmanager.app.data.PdfUtility
import com.pdfmanager.app.data.RepairHelpers
import com.pdfmanager.app.data.RepairResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- shared pieces

@Composable
private fun CheckingSpinner() {
    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

// ---------------------------------------------------------------- Edit metadata

private sealed interface MetaLoad {
    class Ok(val snap: MetadataSnapshot) : MetaLoad
    class Bad(val message: String) : MetaLoad
}

@Composable
private fun MetaField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun EditMetadataScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(ToolInput.takeFirst()) }
    var mode by remember { mutableIntStateOf(0) }
    val job = remember { ToolJob() }

    val loaded by produceState<MetaLoad?>(null, picked?.uri) {
        value = null
        value = picked?.let { p ->
            withContext(Dispatchers.IO) {
                try {
                    MetaLoad.Ok(PdfUtility.readMetadata(context, p.uri))
                } catch (e: Exception) {
                    MetaLoad.Bad(e.message ?: "Cannot read this PDF")
                }
            }
        }
    }
    val ok = loaded as? MetaLoad.Ok
    val original = ok?.snap?.fields ?: MetaFields("", "", "", "")
    var title by remember(ok) { mutableStateOf(original.title) }
    var author by remember(ok) { mutableStateOf(original.author) }
    var subject by remember(ok) { mutableStateOf(original.subject) }
    var keywords by remember(ok) { mutableStateOf(original.keywords) }
    val current = MetaFields(title, author, subject, keywords)
    val changed = ok != null && !MetadataHelpers.same(original, current)

    ToolScaffold("Edit metadata", onBack) {
        PdfPickerButton(picked) { picked = it }
        if (picked != null) {
            when (val l = loaded) {
                null -> CheckingSpinner()
                is MetaLoad.Bad -> FitText(l.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                is MetaLoad.Ok -> {
                    OptionCard("Stored in this PDF") {
                        FitText(MetadataHelpers.foundLine(l.snap.found), style = MaterialTheme.typography.bodyMedium)
                    }
                    ChipRow(listOf(0 to "Edit", 1 to "Remove all"), mode) { mode = it }
                    if (mode == 0) {
                        OptionCard("Details") {
                            MetaField("Title", title) { title = it }
                            MetaField("Author", author) { author = it }
                            MetaField("Subject", subject) { subject = it }
                            MetaField("Keywords", keywords) { keywords = it }
                        }
                    }
                }
            }
        }
        PrimaryButton(
            if (mode == 0) "Save metadata" else "Remove metadata",
            icon = if (mode == 0) AppIcons.DriveFileRenameOutline else AppIcons.DeleteOutline,
            enabled = ok != null && !job.busy && (if (mode == 0) changed else ok.snap.found.isNotEmpty()),
            onClick = {
                val p = picked!!
                val old = original
                val removeAll = mode == 1
                val edit = if (removeAll) null else current
                job.launch(scope) {
                    var names = emptyList<String>()
                    val out = OutputStore.savePdf(context, outName(if (removeAll) "Clean" else "Info", p.name)) { os ->
                        names = PdfUtility.writeMetadata(context, p.uri, os, old, edit)
                    }
                    val msg = when {
                        removeAll && names.isEmpty() -> "Removed all metadata"
                        removeAll -> "Removed all metadata (${names.first()})"
                        else -> "Changed ${names.joinToString(", ")}"
                    }
                    "$msg. Saved in Documents/PDF Manager" to out
                }
            }
        )
        JobStatus(job, onOpen)
        RecentResults(job.result, { it.startsWith("Info_") || it.startsWith("Clean_") }, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
    }
}

// ---------------------------------------------------------------- Repair PDF

private sealed interface Diagnosis {
    class Opens(val pages: Int) : Diagnosis
    object Locked : Diagnosis
    object Broken : Diagnosis
}

/** Tries to open the file with Android's own PDF reader, the one the viewer uses. */
private fun diagnose(context: Context, uri: Uri): Diagnosis = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
        PdfRenderer(fd).use { r ->
            if (r.pageCount > 0) {
                r.openPage(0).use { }
                Diagnosis.Opens(r.pageCount)
            } else {
                Diagnosis.Broken
            }
        }
    } ?: Diagnosis.Broken
} catch (e: SecurityException) {
    Diagnosis.Locked
} catch (e: Exception) {
    Diagnosis.Broken
}

@Composable
fun RepairScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(ToolInput.takeFirst()) }
    var rebuild by remember { mutableStateOf(false) }
    val job = remember { ToolJob() }

    val diagnosis by produceState<Diagnosis?>(null, picked?.uri) {
        value = null
        value = picked?.let { p -> withContext(Dispatchers.IO) { diagnose(context, p.uri) } }
    }

    ToolScaffold("Repair PDF", onBack) {
        PdfPickerButton(picked) { picked = it }
        if (picked != null) {
            when (val d = diagnosis) {
                null -> CheckingSpinner()
                is Diagnosis.Opens -> FitText(
                    "Opens in the viewer · ${d.pages} ${if (d.pages == 1) "page" else "pages"}",
                    style = MaterialTheme.typography.bodyMedium, color = Accent.Green
                )
                Diagnosis.Locked -> FitText(
                    "Locked. Unlock it first with Lock / Unlock",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error
                )
                Diagnosis.Broken -> FitText(
                    "The viewer cannot open this PDF",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error
                )
            }
            if (diagnosis != null && diagnosis != Diagnosis.Locked) {
                OptionCard("Method") {
                    ChipRow(listOf(false to "Quick fix", true to "Rebuild pages"), rebuild) { rebuild = it }
                }
            }
        }
        PrimaryButton(
            "Repair PDF", icon = AppIcons.Handyman,
            enabled = picked != null && diagnosis != null && diagnosis != Diagnosis.Locked && !job.busy,
            onClick = {
                val p = picked!!
                val pageByPage = rebuild
                job.launch(scope) {
                    var result: RepairResult? = null
                    val out = OutputStore.savePdf(context, outName("Repaired", p.name)) { os ->
                        result = PdfUtility.repair(context, p.uri, pageByPage, os) { done, n ->
                            job.progress = "Page $done of $n"
                        }
                    }
                    val r = result!!
                    RepairHelpers.summary(r.pagesKept, r.skipped, pageByPage, r.opens) to out
                }
            }
        )
        JobStatus(job, onOpen)
        RecentResults(job.result, { it.startsWith("Repaired_") }, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
    }
}
