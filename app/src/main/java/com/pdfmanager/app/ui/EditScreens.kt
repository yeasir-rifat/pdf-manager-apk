package com.pdfmanager.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfmanager.app.data.MarkArt
import com.pdfmanager.app.data.OutputStore
import com.pdfmanager.app.data.PdfEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Watermark

private val watermarkColors = listOf(
    "Gray" to 0xFF7A7A7A.toInt(), "Red" to 0xFFD92D20.toInt(),
    "Blue" to 0xFF1D4ED8.toInt(), "Black" to 0xFF111111.toInt()
)

@Composable
fun WatermarkScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    var imageMode by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("DRAFT") }
    var color by remember { mutableIntStateOf(0) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var imageName by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableFloatStateOf(0.6f) }
    var opacity by remember { mutableFloatStateOf(0.3f) }
    var angle by remember { mutableIntStateOf(45) }
    val job = remember { ToolJob() }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { imageUri = uri; imageName = com.pdfmanager.app.data.PdfOps.displayName(context, uri) }
    }
    val art by produceState<Bitmap?>(null, imageMode, text, color, imageUri) {
        value = if (imageMode) {
            imageUri?.let { u -> withContext(Dispatchers.IO) { MarkArt.image(context, u) } }
        } else if (text.isBlank()) null
        else MarkArt.text(text.trim(), watermarkColors[color].second)
    }

    ToolScaffold("Watermark", onBack, bottomBar = {
        PrimaryButton("Add watermark", icon = AppIcons.Watermark, enabled = picked != null && art != null && !job.busy, onClick = {
            val p = picked!!
            val a = art!!
            val (s, o, ang) = Triple(size, opacity, angle.toFloat())
            job.launch(scope) {
                var n = 0
                val out = OutputStore.savePdf(context, outName("Watermarked", p.name)) { os ->
                    n = PdfEdit.watermark(context, p.uri, os, a, s, o, ang)
                }
                "Watermark added" to out
            }
        })
    }) {
        PdfPickerButton(picked) { picked = it }
        OptionCard("Watermark") {
            ChipRow(listOf(false to "Text", true to "Image"), imageMode) { imageMode = it }
            if (!imageMode) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text("Text") },
                    singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                )
                ChipRow(watermarkColors.mapIndexed { i, c -> i to c.first }, color) { color = it }
            } else {
                FilePickCard("Image", imageName, AppIcons.Image, Accent.Violet) { imagePicker.launch(arrayOf("image/*")) }
            }
        }
        OptionCard("Look") {
            LabeledSlider("Size", "${(size * 100).roundToInt()}%", size, 0.15f..1f) { size = it }
            LabeledSlider("Opacity", "${(opacity * 100).roundToInt()}%", opacity, 0.05f..1f) { opacity = it }
            ChipRow(listOf(0 to "0°", 45 to "45°", 90 to "90°", -45 to "-45°"), angle) { angle = it }
        }
        picked?.let { p ->
            PageStage(p.uri, 0) { stage ->
                val bmp = art
                if (bmp != null && stage.width > 0) {
                    val w = with(LocalDensity.current) { (stage.width * size).toDp() }
                    Image(
                        bmp.asImageBitmap(), null,
                        Modifier.align(Alignment.Center).width(w).aspectRatio(bmp.width.toFloat() / bmp.height)
                            .graphicsLayer { rotationZ = -angle.toFloat(); alpha = opacity },
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
        JobStatus(job, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
        RecentResults(job.result, { madeBy(it, "watermarked") }, onOpen)
    }
}

// ---------------------------------------------------------------- Page numbers

/** A small page with six spots; tap one to choose where the number goes. */
@Composable
private fun PositionPicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(6.dp)
    ) {
        for (row in 0..1) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0..2) {
                    val i = row * 3 + col
                    val on = i == selected
                    Box(
                        Modifier.weight(1f).fillMaxHeight().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .border(1.dp, if (on) Color.Transparent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .clickable { onSelect(i) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (on) FitText("1", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
fun PageNumbersScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    var pos by remember { mutableIntStateOf(4) }
    var format by remember { mutableIntStateOf(0) }
    var startText by remember { mutableStateOf("1") }
    var sizePt by remember { mutableFloatStateOf(12f) }
    val job = remember { ToolJob() }
    val start = startText.toIntOrNull()?.coerceIn(0, 99999)

    ToolScaffold("Page numbers", onBack, bottomBar = {
        PrimaryButton("Add numbers", icon = AppIcons.Numbers, enabled = picked != null && start != null && !job.busy, onClick = {
            val p = picked!!
            val (ps, st, fm, sz) = listOf(pos, start!!, format, sizePt.roundToInt())
            job.launch(scope) {
                var n = 0
                val out = OutputStore.savePdf(context, outName("Numbered", p.name)) { os ->
                    n = PdfEdit.pageNumbers(context, p.uri, os, ps, st, fm, sz.toFloat())
                }
                "Pages numbered" to out
            }
        })
    }) {
        PdfPickerButton(picked) { picked = it }
        OptionCard("Position") { PositionPicker(pos) { pos = it } }
        OptionCard("Style") {
            ChipRow(listOf(0 to "5", 1 to "Page 5", 2 to "5 / 12"), format) { format = it }
            OutlinedTextField(
                value = startText, onValueChange = { v -> startText = v.filter { it.isDigit() }.take(5) },
                label = { Text("Start at") }, singleLine = true, isError = start == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
            )
            LabeledSlider("Size", "${sizePt.roundToInt()} pt", sizePt, 8f..28f) { sizePt = it }
        }
        picked?.let { p ->
            PageStage(p.uri, 0) { stage ->
                if (stage.width > 0) {
                    val s = start ?: 1
                    val label = when (format) { 1 -> "Page $s"; 2 -> "$s / $s"; else -> "$s" }
                    val px = stage.width / 595f      // preview scale: 1 pt on an A4-wide page
                    val fontSp = with(LocalDensity.current) { (sizePt * px).toSp() }
                    val margin = with(LocalDensity.current) { (28f * px).toDp() }
                    val align = when (pos) {
                        0 -> Alignment.TopStart; 1 -> Alignment.TopCenter; 2 -> Alignment.TopEnd
                        3 -> Alignment.BottomStart; 4 -> Alignment.BottomCenter; else -> Alignment.BottomEnd
                    }
                    Text(
                        label, Modifier.align(align).padding(horizontal = margin, vertical = margin * 0.7f),
                        color = Color(0xFF333333), fontSize = fontSp, maxLines = 1, softWrap = false
                    )
                }
            }
        }
        JobStatus(job, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
        RecentResults(job.result, { madeBy(it, "numbered") }, onOpen)
    }
}

// ---------------------------------------------------------------- Resize paper

@Composable
fun ResizePaperScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    var size by remember { mutableIntStateOf(1) }
    var fill by remember { mutableStateOf(false) }
    val job = remember { ToolJob() }
    val target = PdfEdit.paperSizes[size]

    ToolScaffold("Resize paper", onBack, bottomBar = {
        PrimaryButton("Resize to ${target.label}", icon = AppIcons.AspectRatio, enabled = picked != null && !job.busy, onClick = {
            val p = picked!!
            val t = target
            val f = fill
            job.launch(scope) {
                var n = 0
                val out = OutputStore.savePdf(context, outName(t.label, p.name)) { os ->
                    n = PdfEdit.resize(context, p.uri, os, t.rect, f)
                }
                "Resized to ${t.label}" to out
            }
        })
    }) {
        PdfPickerButton(picked) { picked = it }
        OptionCard("Paper size") {
            ChipRow(PdfEdit.paperSizes.mapIndexed { i, s -> i to s.label }, size) { size = it }
            ChipRow(listOf(false to "Fit page", true to "Fill page"), fill) { fill = it }
        }
        JobStatus(job, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
        RecentResults(job.result, { madeBy(it, "A3", "A4", "A5", "Letter", "Legal") }, onOpen)
    }
}

// ---------------------------------------------------------------- Poster print

@Composable
fun PosterScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    val pageCount = rememberPageCount(picked).value
    val pages = pageCount ?: 0
    var page by remember(picked) { mutableIntStateOf(0) }
    var cols by remember { mutableIntStateOf(2) }
    var rows by remember { mutableIntStateOf(2) }
    var overlapMm by remember { mutableIntStateOf(10) }
    val job = remember { ToolJob() }
    val sheets = cols * rows

    ToolScaffold("Poster print", onBack, bottomBar = {
        PrimaryButton(
            "Create $sheets ${if (sheets == 1) "sheet" else "sheets"}", icon = AppIcons.Poster,
            enabled = picked != null && pages > 0 && !job.busy, onClick = {
                val p = picked!!
                val (pg, c, r) = Triple(page, cols, rows)
                val ov = overlapMm * 72f / 25.4f
                job.launch(scope) {
                    var n = 0
                    val out = OutputStore.savePdf(context, outName("Poster", p.name)) { os ->
                        n = PdfEdit.poster(context, p.uri, os, pg, c, r, ov)
                    }
                    "Poster: $n A4 sheets" to out
                }
            }
        )
    }) {
        PdfPickerButton(picked) { picked = it }
        PagesState(picked, pageCount)
        picked?.let { p ->
            if (pages > 1) PageNav(page, pages) { page = it }
            PageStage(p.uri, page) { stage ->
                if (stage.width > 0) {
                    val line = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxSize()) {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                        for (c in 1 until cols) {
                            val x = size.width * c / cols
                            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx(), pathEffect = dash)
                        }
                        for (r in 1 until rows) {
                            val y = size.height * r / rows
                            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), pathEffect = dash)
                        }
                    }
                }
            }
        }
        OptionCard("Sheets (A4)") {
            StepperRow("Columns", cols, 1..8) { cols = it }
            StepperRow("Rows", rows, 1..8) { rows = it }
            ChipRow(listOf(0 to "No overlap", 5 to "5 mm", 10 to "10 mm", 20 to "20 mm"), overlapMm) { overlapMm = it }
        }
        JobStatus(job, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
        RecentResults(job.result, { madeBy(it, "poster") }, onOpen)
    }
}
