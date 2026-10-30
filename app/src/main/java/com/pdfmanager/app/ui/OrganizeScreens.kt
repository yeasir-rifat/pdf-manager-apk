package com.pdfmanager.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pdfmanager.app.data.OutputStore
import com.pdfmanager.app.data.PdfPages

// ---------------------------------------------------------------- shared pieces

/** All / None / Invert plus a "3 / 12" counter. One line, never wraps. */
@Composable
private fun SelectionChips(count: Int, total: Int, onAll: () -> Unit, onNone: () -> Unit, onInvert: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = onAll, label = { FitText("All") }, shape = RoundedCornerShape(12.dp))
        AssistChip(onClick = onNone, label = { FitText("None") }, shape = RoundedCornerShape(12.dp))
        AssistChip(onClick = onInvert, label = { FitText("Invert") }, shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.weight(1f))
        FitText("$count / $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Label on the left, - 3 + on the right. */
@Composable
internal fun StepperRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FitText(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        FilledTonalIconButton(enabled = value > range.first, onClick = { onChange(value - 1) }) {
            Icon(AppIcons.Remove, "Less")
        }
        FitText(
            "$value", Modifier.width(48.dp), style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        FilledTonalIconButton(enabled = value < range.last, onClick = { onChange(value + 1) }) {
            Icon(AppIcons.Add, "More")
        }
    }
}

/** Spinner while the page count is read; a short message when the file cannot be read. */
@Composable
internal fun PagesState(picked: Picked?, pages: Int?) {
    if (picked == null) return
    when {
        pages == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        }
        pages <= 0 -> FitText("Cannot read this PDF. It may be locked.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

private fun pluralPages(n: Int) = if (n == 1) "1 page" else "$n pages"

// ---------------------------------------------------------------- Split

@Composable
fun SplitScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    val pagesState = rememberPageCount(picked)
    val pages = pagesState.value
    var mode by remember { mutableIntStateOf(0) }
    var ranges by remember { mutableStateOf("") }
    var every by remember { mutableIntStateOf(1) }
    val job = remember { ToolJob() }

    val total = pages ?: 0
    LaunchedEffect(total) { if (total > 0) every = every.coerceIn(1, total) }
    val groups = remember(mode, ranges, every, total) {
        if (total <= 0) null
        else if (mode == 0) PdfPages.parseRanges(ranges, total)
        else PdfPages.everyN(total, every)
    }
    val rangeInvalid = mode == 0 && ranges.isNotBlank() && groups == null

    ToolScaffold("Split PDF", onBack) {
        PdfPickerButton(picked) { picked = it }
        PagesState(picked, pages)
        if (total > 0) {
            OptionCard("Split by") {
                ChipRow(listOf(0 to "Range", 1 to "Every N pages"), mode) { mode = it }
                if (mode == 0) {
                    OutlinedTextField(
                        value = ranges,
                        onValueChange = { ranges = it },
                        label = { Text("Pages") },
                        placeholder = { Text("1-3, 5, 8-10") },
                        singleLine = true,
                        isError = rangeInvalid,
                        supportingText = if (rangeInvalid) ({ FitText("Use numbers like 1-3, 5, 8-10 (max $total)") }) else null,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    StepperRow("Pages per file", every, 1..total) { every = it }
                }
            }
        }
        val files = groups?.size ?: 0
        PrimaryButton(
            if (files > 0) "Split into $files ${if (files == 1) "file" else "files"}" else "Split",
            icon = AppIcons.ContentCut,
            enabled = picked != null && files > 0 && !job.busy,
            onClick = {
                val p = picked!!
                val g = groups!!
                job.launch(scope) {
                    var last: android.net.Uri? = null
                    val n = PdfPages.split(context, p.uri, g) { _, part, write ->
                        val label = if (part.size == 1) "p${part[0] + 1}" else "p${part.first() + 1}-${part.last() + 1}"
                        last = OutputStore.savePdf(context, "${baseOf(p.name)}_$label.pdf", write)
                    }
                    "Created $n ${if (n == 1) "file" else "files"}. Saved in Documents/PDF Manager" to (if (n == 1) last else null)
                }
            }
        )
        JobStatus(job, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
        RecentResults(job.status, { Regex("_p\\d+(-\\d+)?\\.pdf$").containsMatchIn(it) }, onOpen)
    }
}

// ---------------------------------------------------------------- Extract / Delete

@Composable
fun ExtractDeleteScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    val pages = rememberPageCount(picked).value
    var keepMode by remember { mutableStateOf(true) }
    var selected by remember(picked) { mutableStateOf(emptySet<Int>()) }
    val job = remember { ToolJob() }
    val total = pages ?: 0
    val tint = if (keepMode) Accent.Teal else Accent.Red

    ToolScaffold("Extract / Delete", onBack) {
        PdfPickerButton(picked) { picked = it }
        PagesState(picked, pages)
        if (picked != null && total > 0) {
            ChipRow(listOf(true to "Keep selected", false to "Delete selected"), keepMode) { keepMode = it }
            SelectionChips(
                selected.size, total,
                onAll = { selected = (0 until total).toSet() },
                onNone = { selected = emptySet() },
                onInvert = { selected = (0 until total).filter { it !in selected }.toSet() }
            )
            PageSelectGrid(
                picked!!.uri.toString(), total, selected, tint,
                markIcon = if (keepMode) AppIcons.Check else AppIcons.Close
            ) { p -> selected = if (p in selected) selected - p else selected + p }
        }
        val n = selected.size
        val canRun = picked != null && total > 0 && n > 0 && (keepMode || n < total) && !job.busy
        PrimaryButton(
            when {
                n == 0 -> if (keepMode) "Keep pages" else "Delete pages"
                keepMode -> "Keep ${pluralPages(n)}"
                else -> "Delete ${pluralPages(n)}"
            },
            icon = if (keepMode) AppIcons.Check else AppIcons.DeleteOutline,
            enabled = canRun,
            onClick = {
                val p = picked!!
                val sel = selected
                val keep = keepMode
                job.launch(scope) {
                    var count = 0
                    val out = OutputStore.savePdf(context, outName(if (keep) "Extracted" else "Trimmed", p.name)) { os ->
                        count = if (keep) PdfPages.keepPages(context, p.uri, os, sel)
                        else PdfPages.deletePages(context, p.uri, os, sel)
                    }
                    "Saved a PDF with ${pluralPages(count)}. Saved in Documents/PDF Manager" to out
                }
            }
        )
        JobStatus(job, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
        RecentResults(job.result, { it.startsWith("Extracted_") || it.startsWith("Trimmed_") }, onOpen)
    }
}

// ---------------------------------------------------------------- Reorder

@Composable
fun ReorderScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    val pages = rememberPageCount(picked).value
    val total = pages ?: 0
    val order = remember(picked, total) { mutableStateListOf<Int>().apply { addAll(0 until total) } }
    val job = remember { ToolJob() }
    val changed = order.indices.any { order[it] != it }

    ToolScaffold("Reorder pages", onBack) {
        PdfPickerButton(picked) { picked = it }
        PagesState(picked, pages)
        if (picked != null && total > 1) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { val r = order.reversed(); order.clear(); order.addAll(r) },
                    label = { FitText("Reverse") },
                    leadingIcon = { Icon(AppIcons.SwapVert, null, Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(12.dp)
                )
                AssistChip(
                    enabled = changed,
                    onClick = { order.clear(); order.addAll(0 until total) },
                    label = { FitText("Reset") },
                    leadingIcon = { Icon(AppIcons.Refresh, null, Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
            PageReorderGrid(picked!!.uri.toString(), order)
        }
        PrimaryButton(
            "Save new order", icon = AppIcons.SwapVert,
            enabled = picked != null && total > 1 && changed && !job.busy,
            onClick = {
                val p = picked!!
                val newOrder = order.toList()
                job.launch(scope) {
                    val out = OutputStore.savePdf(context, outName("Reordered", p.name)) { os ->
                        PdfPages.reorder(context, p.uri, os, newOrder)
                    }
                    "Pages reordered. Saved in Documents/PDF Manager" to out
                }
            }
        )
        JobStatus(job, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
        RecentResults(job.result, { it.startsWith("Reordered_") }, onOpen)
    }
}

// ---------------------------------------------------------------- Add blank page

@Composable
fun BlankPageScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    val pages = rememberPageCount(picked).value
    val total = pages ?: 0
    var selected by remember(picked) { mutableStateOf(emptySet<Int>()) }
    var after by remember { mutableStateOf(true) }
    var count by remember { mutableIntStateOf(1) }
    val job = remember { ToolJob() }

    ToolScaffold("Blank page", onBack) {
        PdfPickerButton(picked) { picked = it }
        PagesState(picked, pages)
        if (picked != null && total > 0) {
            OptionCard("Insert") {
                ChipRow(listOf(true to "After page", false to "Before page"), after) { after = it }
                StepperRow("Blank pages each", count, 1..10) { count = it }
            }
            SelectionChips(
                selected.size, total,
                onAll = { selected = (0 until total).toSet() },
                onNone = { selected = emptySet() },
                onInvert = { selected = (0 until total).filter { it !in selected }.toSet() }
            )
            PageSelectGrid(picked!!.uri.toString(), total, selected, Accent.Violet, markIcon = AppIcons.Add) { p ->
                selected = if (p in selected) selected - p else selected + p
            }
        }
        val added = selected.size * count
        PrimaryButton(
            if (added > 0) "Add ${pluralPages(added).replace("page", "blank page")}" else "Add blank pages",
            icon = AppIcons.NoteAdd,
            enabled = picked != null && total > 0 && added > 0 && !job.busy,
            onClick = {
                val p = picked!!
                val anchors = selected
                val a = after
                val c = count
                job.launch(scope) {
                    var n = 0
                    val out = OutputStore.savePdf(context, outName("Blank", p.name)) { os ->
                        n = PdfPages.insertBlank(context, p.uri, os, anchors, a, c)
                    }
                    "Added ${pluralPages(n).replace("page", "blank page")}. Saved in Documents/PDF Manager" to out
                }
            }
        )
        JobStatus(job, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
        RecentResults(job.result, { it.startsWith("Blank_") }, onOpen)
    }
}
