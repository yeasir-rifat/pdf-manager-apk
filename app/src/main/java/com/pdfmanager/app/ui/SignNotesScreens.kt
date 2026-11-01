package com.pdfmanager.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pdfmanager.app.data.OutputStore
import com.pdfmanager.app.data.PageMark
import com.pdfmanager.app.data.PdfEdit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val InkColor = Color(0xFF1B2A6B)

private fun Path.addStroke(points: List<Offset>) {
    if (points.isEmpty()) return
    moveTo(points[0].x, points[0].y)
    if (points.size == 1) lineTo(points[0].x + 0.1f, points[0].y + 0.1f)
    for (i in 1 until points.size) {
        // Smooth the line: curve through the middle of each segment
        val prev = points[i - 1]
        val cur = points[i]
        quadraticBezierTo(prev.x, prev.y, (prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
    }
    lineTo(points.last().x, points.last().y)
}

/** Draws the strokes into a transparent bitmap cropped to the ink, ready to be placed on a page. */
private fun renderSignature(strokes: List<List<Offset>>, strokeWidthPx: Float): Bitmap? {
    val all = strokes.flatten()
    if (all.isEmpty()) return null
    val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
    val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }
    val pad = strokeWidthPx * 1.5f
    val bw = max(maxX - minX, 1f) + pad * 2
    val bh = max(maxY - minY, 1f) + pad * 2
    val scale = min(1f, 900f / bw)
    val bmp = Bitmap.createBitmap(max(1, (bw * scale).roundToInt()), max(1, (bh * scale).roundToInt()), Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(0x1B, 0x2A, 0x6B)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = strokeWidthPx * scale
    }
    for (s in strokes) {
        if (s.isEmpty()) continue
        val path = AndroidPath()
        fun px(o: Offset) = (o.x - minX + pad) * scale
        fun py(o: Offset) = (o.y - minY + pad) * scale
        path.moveTo(px(s[0]), py(s[0]))
        if (s.size == 1) path.lineTo(px(s[0]) + 0.1f, py(s[0]) + 0.1f)
        for (i in 1 until s.size) {
            path.quadTo(px(s[i - 1]), py(s[i - 1]), (px(s[i - 1]) + px(s[i])) / 2f, (py(s[i - 1]) + py(s[i])) / 2f)
        }
        path.lineTo(px(s.last()), py(s.last()))
        canvas.drawPath(path, paint)
    }
    return bmp
}

// ---------------------------------------------------------------- Sign PDF

@Composable
fun SignScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    val pageCount = rememberPageCount(picked).value
    val pages = pageCount ?: 0
    var page by remember(picked) { mutableIntStateOf(0) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var cx by remember { mutableFloatStateOf(0.5f) }
    var cy by remember { mutableFloatStateOf(0.82f) }
    var width by remember { mutableFloatStateOf(0.3f) }
    var allPages by remember { mutableStateOf(false) }
    val job = remember { ToolJob() }
    val strokePx = with(density) { 3.dp.toPx() }
    val sig = remember(strokes.size) { renderSignature(strokes.toList(), strokePx) }

    ToolScaffold("Sign PDF", onBack, bottomBar = {
        PrimaryButton("Sign PDF", icon = AppIcons.Edit, enabled = picked != null && sig != null && !job.busy, onClick = {
            val file = picked!!
            val art = sig!!
            val targets = if (allPages) (0 until pages).toSet() else setOf(page)
            val (x, y, w) = Triple(cx, cy, width)
            job.launch(scope) {
                var n = 0
                val out = OutputStore.savePdf(context, outName("Signed", file.name)) { os ->
                    n = PdfEdit.placeImage(context, file.uri, os, art, targets, x, y, w)
                }
                "Signed ${if (n == 1) "1 page" else "$n pages"}" to out
            }
        })
    }) {
        PdfPickerButton(picked) { picked = it }
        PagesState(picked, pageCount)
        OptionCard("Draw your signature") {
            Box(
                Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { live = listOf(it) },
                            onDrag = { change, _ -> change.consume(); live = live + change.position },
                            onDragEnd = { if (live.isNotEmpty()) strokes.add(live); live = emptyList() },
                            onDragCancel = { if (live.isNotEmpty()) strokes.add(live); live = emptyList() }
                        )
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawLine(Color(0xFFD0D0D0), Offset(24.dp.toPx(), size.height * 0.72f), Offset(size.width - 24.dp.toPx(), size.height * 0.72f), 1.dp.toPx())
                    val path = Path()
                    strokes.forEach { path.addStroke(it) }
                    path.addStroke(live)
                    drawPath(path, InkColor, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    enabled = strokes.isNotEmpty(), onClick = { strokes.removeAt(strokes.lastIndex) },
                    label = { FitText("Undo") }, leadingIcon = { Icon(AppIcons.Undo, null, Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(12.dp)
                )
                AssistChip(
                    enabled = strokes.isNotEmpty(), onClick = { strokes.clear() },
                    label = { FitText("Clear") }, leadingIcon = { Icon(AppIcons.Close, null, Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
        val p = picked
        if (p != null && sig != null && pages > 0) {
            if (pages > 1) PageNav(page, pages) { page = it }
            PageStage(p.uri, page) { stage ->
                if (stage.width > 0) {
                    val wPx = stage.width * width
                    val hPx = wPx * sig.height / sig.width
                    Box(
                        Modifier.fillMaxSize().pointerInput(stage) {
                            detectTapGestures { o -> cx = (o.x / stage.width).coerceIn(0f, 1f); cy = (o.y / stage.height).coerceIn(0f, 1f) }
                        }
                    )
                    Image(
                        sig.asImageBitmap(), null,
                        Modifier
                            .offset { IntOffset((cx * stage.width - wPx / 2f).roundToInt(), (cy * stage.height - hPx / 2f).roundToInt()) }
                            .width(with(density) { wPx.toDp() })
                            .aspectRatio(sig.width.toFloat() / sig.height)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .pointerInput(stage) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    cx = (cx + drag.x / stage.width).coerceIn(0f, 1f)
                                    cy = (cy + drag.y / stage.height).coerceIn(0f, 1f)
                                }
                            },
                        contentScale = ContentScale.Fit
                    )
                }
            }
            OptionCard("Placement") {
                LabeledSlider("Size", "${(width * 100).roundToInt()}%", width, 0.1f..0.8f) { width = it }
                ChipRow(listOf(false to "This page", true to "All pages"), allPages) { allPages = it }
            }
        }
        JobStatus(job, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
        RecentResults(job.result, { madeBy(it, "signed") }, onOpen)
    }
}

// ---------------------------------------------------------------- Highlight / Notes

private val highlightColors = listOf(
    "Yellow" to 0xFFFFEB3B.toInt(), "Green" to 0xFF69F0AE.toInt(),
    "Pink" to 0xFFFF80AB.toInt(), "Blue" to 0xFF80D8FF.toInt()
)

@Composable
fun HighlightNotesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    val pageCount = rememberPageCount(picked).value
    val pages = pageCount ?: 0
    var page by remember(picked) { mutableIntStateOf(0) }
    var noteMode by remember { mutableStateOf(false) }
    var colorIdx by remember { mutableIntStateOf(0) }
    val marks = remember(picked) { mutableStateListOf<PageMark>() }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragNow by remember { mutableStateOf<Offset?>(null) }
    var pendingNote by remember { mutableStateOf<Offset?>(null) }   // fractions of the page
    var noteText by remember { mutableStateOf("") }
    val job = remember { ToolJob() }

    if (pendingNote != null) {
        AlertDialog(
            modifier = Modifier.dialogEnter(),
            onDismissRequest = { pendingNote = null; noteText = "" },
            title = { Text("Note") },
            text = {
                OutlinedTextField(
                    value = noteText, onValueChange = { noteText = it }, maxLines = 4,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(enabled = noteText.isNotBlank(), onClick = {
                    val at = pendingNote!!
                    marks.add(PageMark.Note(page, at.x, at.y, noteText.trim()))
                    pendingNote = null; noteText = ""
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pendingNote = null; noteText = "" }) { Text("Cancel") } }
        )
    }

    ToolScaffold("Highlight / Notes", onBack, bottomBar = {
        PrimaryButton("Save marks", icon = AppIcons.StickyNote, enabled = picked != null && marks.isNotEmpty() && !job.busy, onClick = {
            val file = picked!!
            val list = marks.toList()
            job.launch(scope) {
                var n = 0
                val out = OutputStore.savePdf(context, outName("Marked", file.name)) { os ->
                    n = PdfEdit.applyMarks(context, file.uri, os, list)
                }
                "Saved $n ${if (n == 1) "mark" else "marks"}" to out
            }
        })
    }) {
        PdfPickerButton(picked) { picked = it }
        PagesState(picked, pageCount)
        val p = picked
        if (p != null && pages > 0) {
            ChipRow(listOf(false to "Highlight", true to "Note"), noteMode) { noteMode = it }
            if (!noteMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    highlightColors.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color(c.second))
                                .border(if (i == colorIdx) 3.dp else 1.dp, if (i == colorIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .pointerInput(i) { detectTapGestures { colorIdx = i } }
                        )
                    }
                }
            }
            if (pages > 1) PageNav(page, pages) { page = it }
            PageStage(p.uri, page) { stage ->
                if (stage.width > 0 && stage.height > 0) {
                    // Marks already on this page
                    Canvas(Modifier.fillMaxSize()) {
                        marks.filterIsInstance<PageMark.Highlight>().filter { it.page == page }.forEach { h ->
                            drawRect(
                                Color(h.color).copy(alpha = 0.5f),
                                Offset(min(h.left, h.right) * size.width, min(h.top, h.bottom) * size.height),
                                Size(abs(h.right - h.left) * size.width, abs(h.bottom - h.top) * size.height)
                            )
                        }
                        val a = dragStart
                        val b = dragNow
                        if (a != null && b != null) {
                            drawRect(
                                Color(highlightColors[colorIdx].second).copy(alpha = 0.5f),
                                Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(b.x - a.x), abs(b.y - a.y))
                            )
                        }
                    }
                    marks.filterIsInstance<PageMark.Note>().filter { it.page == page }.forEach { n ->
                        Icon(
                            AppIcons.StickyNote, null, tint = Accent.Amber,
                            modifier = Modifier.size(24.dp).offset { IntOffset((n.x * stage.width).roundToInt() - 12.dp.roundToPx(), (n.y * stage.height).roundToInt() - 12.dp.roundToPx()) }
                        )
                    }
                    // Touch layer
                    Box(
                        Modifier.fillMaxSize().pointerInput(noteMode, stage) {
                            if (noteMode) {
                                detectTapGestures { o -> pendingNote = Offset(o.x / stage.width, o.y / stage.height) }
                            } else {
                                detectDragGestures(
                                    onDragStart = { dragStart = it; dragNow = it },
                                    onDrag = { change, _ -> change.consume(); dragNow = change.position },
                                    onDragEnd = {
                                        val a = dragStart
                                        val b = dragNow
                                        if (a != null && b != null && abs(b.x - a.x) > 8f && abs(b.y - a.y) > 4f) {
                                            marks.add(
                                                PageMark.Highlight(
                                                    page,
                                                    (min(a.x, b.x) / stage.width).coerceIn(0f, 1f), (min(a.y, b.y) / stage.height).coerceIn(0f, 1f),
                                                    (max(a.x, b.x) / stage.width).coerceIn(0f, 1f), (max(a.y, b.y) / stage.height).coerceIn(0f, 1f),
                                                    highlightColors[colorIdx].second
                                                )
                                            )
                                        }
                                        dragStart = null; dragNow = null
                                    },
                                    onDragCancel = { dragStart = null; dragNow = null }
                                )
                            }
                        }
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    enabled = marks.isNotEmpty(), onClick = { marks.removeAt(marks.lastIndex) },
                    label = { FitText("Undo") }, leadingIcon = { Icon(AppIcons.Undo, null, Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(12.dp)
                )
                AssistChip(
                    enabled = marks.isNotEmpty(), onClick = { marks.clear() },
                    label = { FitText("Clear") }, leadingIcon = { Icon(AppIcons.Close, null, Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.weight(1f))
                FitText(
                    if (marks.size == 1) "1 mark" else "${marks.size} marks",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary
                )
            }
        }
        JobStatus(job, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
        RecentResults(job.result, { madeBy(it, "marked") }, onOpen)
    }
}
