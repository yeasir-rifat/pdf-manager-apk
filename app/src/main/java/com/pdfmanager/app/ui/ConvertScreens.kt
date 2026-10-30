package com.pdfmanager.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfmanager.app.data.ConvertHelpers
import com.pdfmanager.app.data.OutputStore
import com.pdfmanager.app.data.PdfConvert
import com.pdfmanager.app.data.PdfOps
import com.pdfmanager.app.printing.PageRangeParser
import com.pdfmanager.app.printing.PageRangeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- shared pieces

/** "Pages" field of the PDF tools: empty means every page; a wrong entry shows its reason below the field. */
@Composable
private fun PageRangeField(value: String, onChange: (String) -> Unit, total: Int) {
    val result = remember(value, total) { PageRangeParser.parse(value, total) }
    val error = (result as? PageRangeResult.Invalid)?.message
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Pages") },
        placeholder = { Text("All pages (or 1-3, 5, 8-10)") },
        singleLine = true,
        isError = error != null,
        supportingText = if (error != null) ({ FitText(error) }) else null,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = Modifier.fillMaxWidth()
    )
}

/** The pages chosen in a Pages field, or null while the field is wrong or the page count is unknown. */
@Composable
private fun rememberChosenPages(ranges: String, total: Int): PageRangeResult.Valid? =
    remember(ranges, total) {
        if (total > 0) PageRangeParser.parse(ranges, total) as? PageRangeResult.Valid else null
    }

// ---------------------------------------------------------------- PDF to Text

private class TextPreview(val shown: String, val full: String?)

@Composable
private fun TextPreviewCard(p: TextPreview) {
    val context = LocalContext.current
    OptionCard("Preview") {
        Text(p.shown, style = MaterialTheme.typography.bodySmall, maxLines = 14, overflow = TextOverflow.Ellipsis)
        if (p.full != null) {
            SecondaryButton("Copy all text", icon = AppIcons.ContentCopy, onClick = { Share.copyText(context, p.full) })
        }
    }
}

@Composable
fun PdfToTextScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    val pages = rememberPageCount(picked).value
    val total = pages ?: 0
    var ranges by remember(picked) { mutableStateOf("") }
    var byPosition by remember { mutableStateOf(false) }
    var marks by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<TextPreview?>(null) }
    val job = remember { ToolJob() }
    val chosen = rememberChosenPages(ranges, total)

    ToolScaffold("PDF to Text", onBack) {
        PdfPickerButton(picked) { picked = it; preview = null }
        PagesState(picked, pages)
        if (picked != null && total > 0) {
            OptionCard("Pages") { PageRangeField(ranges, { ranges = it }, total) }
            OptionCard("Reading order") {
                ChipRow(listOf(false to "Normal", true to "By position"), byPosition) { byPosition = it }
            }
            OptionCard("Page marks") {
                ChipRow(listOf(false to "Off", true to "On"), marks) { marks = it }
            }
        }
        PrimaryButton(
            "Extract text", icon = AppIcons.Description,
            enabled = picked != null && chosen != null && chosen.count > 0 && !job.busy,
            onClick = {
                val p = picked!!
                val sel = chosen!!.pages
                val isAll = chosen.isAll
                val rangeText = ranges
                val pos = byPosition
                val mk = marks
                preview = null
                job.launch(scope) {
                    val r = PdfConvert.pdfToText(context, p.uri, sel, pos, mk) { done, n ->
                        job.progress = "Reading page $done of $n"
                    }
                    val bytes = r.text.toByteArray(Charsets.UTF_8)
                    val out = OutputStore.saveText(context, ConvertHelpers.textFileName(p.name, rangeText, isAll)) { os ->
                        os.write(bytes)
                    }
                    preview = TextPreview(
                        ConvertHelpers.previewOf(r.text, 1500, 14),
                        if (r.text.length <= ConvertHelpers.MAX_COPY_CHARS) r.text else null
                    )
                    val words = ConvertHelpers.wordCount(r.text)
                    var msg = "Text of ${ConvertHelpers.plural(r.pagesRead, "page")} " +
                        "(${ConvertHelpers.plural(words, "word")})"
                    if (r.emptyPages > 0) msg += ". ${ConvertHelpers.plural(r.emptyPages, "page")} had no text"
                    "$msg. Saved in Documents/PDF Manager" to out
                }
            }
        )
        JobStatus(job, onOpen = { Share.view(context, it, "text/plain") }, mime = "text/plain", openIcon = AppIcons.Description)
        if (!job.busy && !job.failed && job.result != null) preview?.let { TextPreviewCard(it) }
        if (picked == null) RecentPdfsStrip { picked = it }
    }
}

// ---------------------------------------------------------------- Extract images

@Composable
fun ExtractImagesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    val pages = rememberPageCount(picked).value
    val total = pages ?: 0
    var ranges by remember(picked) { mutableStateOf("") }
    var png by remember { mutableStateOf(false) }
    var minPx by remember { mutableIntStateOf(64) }
    val job = remember { ToolJob() }
    val chosen = rememberChosenPages(ranges, total)

    ToolScaffold("Extract images", onBack) {
        PdfPickerButton(picked) { picked = it }
        PagesState(picked, pages)
        if (picked != null && total > 0) {
            OptionCard("Pages") { PageRangeField(ranges, { ranges = it }, total) }
            OptionCard("Format") {
                ChipRow(listOf(false to "JPG", true to "PNG"), png) { png = it }
            }
            OptionCard("Smallest image") {
                ChipRow(listOf(0 to "Any", 64 to "64 px", 128 to "128 px", 256 to "256 px"), minPx) { minPx = it }
            }
        }
        PrimaryButton(
            "Extract images", icon = AppIcons.ExtractImages,
            enabled = picked != null && chosen != null && chosen.count > 0 && !job.busy,
            onClick = {
                val p = picked!!
                val sel = chosen!!.pages
                val asPng = png
                val min = minPx
                job.launch(scope) {
                    val r = PdfConvert.extractImages(context, p.uri, sel, asPng, min, p.name) { done, n, saved ->
                        job.progress = "Page $done of $n · ${ConvertHelpers.plural(saved, "image")}"
                    }
                    var msg = "Saved ${ConvertHelpers.plural(r.saved, "image")} from ${ConvertHelpers.plural(r.pagesRead, "page")}"
                    val notes = listOfNotNull(
                        if (r.skippedSmall > 0) "${ConvertHelpers.count(r.skippedSmall)} small skipped" else null,
                        if (r.skippedFailed > 0) "${ConvertHelpers.count(r.skippedFailed)} could not be read" else null
                    )
                    if (notes.isNotEmpty()) msg += " (${notes.joinToString(", ")})"
                    "$msg. Saved in Pictures/PDF Manager" to null
                }
            }
        )
        JobStatus(job)
        if (picked == null) RecentPdfsStrip { picked = it }
    }
}

// ---------------------------------------------------------------- Text to PDF

private sealed interface FileText {
    class Ok(val text: String, val chars: Int, val words: Int) : FileText
    class Bad(val message: String) : FileText
}

@Composable
fun TextToPdfScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Picked?>(null) }
    var paper by remember { mutableIntStateOf(0) }
    var margin by remember { mutableIntStateOf(54) }
    var family by remember { mutableIntStateOf(PdfConvert.FONT_SANS) }
    var fontSize by remember { mutableIntStateOf(12) }
    val job = remember { ToolJob() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) file = Picked(uri, PdfOps.displayName(context, uri))
    }
    val loaded by produceState<FileText?>(null, file?.uri) {
        value = null
        value = file?.let { f ->
            withContext(Dispatchers.IO) {
                try {
                    val t = PdfConvert.readTextFile(context, f.uri)
                    FileText.Ok(t, t.length, ConvertHelpers.wordCount(t))
                } catch (e: Exception) {
                    FileText.Bad(e.message ?: "Cannot read this file")
                }
            }
        }
    }
    val fileState = loaded
    val source: String = if (mode == 0) typed else (fileState as? FileText.Ok)?.text ?: ""

    ToolScaffold("Text to PDF", onBack) {
        ChipRow(listOf(0 to "Type or paste", 1 to "Text file"), mode) { mode = it }
        if (mode == 0) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Text") },
                minLines = 6,
                maxLines = 14,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            val detail = (fileState as? FileText.Ok)?.let {
                "${ConvertHelpers.plural(it.chars, "character")} · ${ConvertHelpers.plural(it.words, "word")}"
            }
            FilePickCard("Text file", file?.name, AppIcons.Description, Accent.Teal, detail = detail) {
                picker.launch(arrayOf("text/*"))
            }
            if (fileState is FileText.Bad) {
                FitText(fileState.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        OptionCard("Page") {
            ChipRow(PdfConvert.papers.mapIndexed { i, p -> i to p.label }, paper) { paper = it }
            ChipRow(listOf(36 to "Narrow", 54 to "Normal", 72 to "Wide"), margin) { margin = it }
        }
        OptionCard("Text") {
            ChipRow(
                listOf(PdfConvert.FONT_SANS to "Sans", PdfConvert.FONT_SERIF to "Serif", PdfConvert.FONT_MONO to "Mono"),
                family
            ) { family = it }
            StepperRow("Font size", fontSize, 8..32) { fontSize = it }
        }
        PrimaryButton(
            "Create PDF", icon = AppIcons.PictureAsPdf,
            enabled = source.isNotBlank() && !job.busy,
            onClick = {
                val text = source
                val sheet = PdfConvert.papers[paper]
                val m = margin
                val f = family
                val size = fontSize
                val name = outName("Text", if (mode == 1) file?.name else null)
                job.launch(scope) {
                    var count = 0
                    val out = OutputStore.savePdf(context, name) { os ->
                        count = PdfConvert.textToPdf(text, os, sheet, size, m, f) { done, n ->
                            job.progress = "Page $done of $n"
                        }
                    }
                    "Created a PDF with ${ConvertHelpers.plural(count, "page")}. Saved in Documents/PDF Manager" to out
                }
            }
        )
        JobStatus(job, onOpen)
        RecentResults(job.result, { it.startsWith("Text_") }, onOpen)
    }
}
