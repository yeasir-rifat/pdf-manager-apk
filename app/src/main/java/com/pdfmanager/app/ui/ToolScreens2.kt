package com.pdfmanager.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.text.format.Formatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.pdfmanager.app.data.OutputStore
import com.pdfmanager.app.data.PdfCompressor
import com.pdfmanager.app.data.PdfLayout

// ---------------------------------------------------------------- Compress

@Composable
fun CompressScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    var strength by remember { mutableIntStateOf(50) }
    // The estimate is stored together with the file it belongs to, so a stale result is never shown
    var analysis by remember { mutableStateOf<Pair<Picked, PdfCompressor.Estimate?>?>(null) }
    val job = remember { ToolJob() }

    LaunchedEffect(picked) {
        val p = picked ?: return@LaunchedEffect
        val est = withContext(Dispatchers.Default) {
            runCatching { PdfCompressor.analyze(context, p.uri) { !isActive } }.getOrNull()
        }
        analysis = p to est
    }
    val analyzing = picked != null && analysis?.first != picked
    val estimate = analysis?.takeIf { it.first == picked }?.second

    ToolScaffold("Compress PDF", onBack) {
        PdfPickerButton(picked) { picked = it }
        OptionCard("Compression strength") {
            Text("$strength%", style = MaterialTheme.typography.headlineMedium)
            Slider(
                value = strength.toFloat(),
                onValueChange = { strength = it.roundToInt() },
                valueRange = 0f..100f,
                enabled = !job.busy,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0% best quality", style = MaterialTheme.typography.labelSmall)
                Text("100% smallest file", style = MaterialTheme.typography.labelSmall)
            }
            ChipRow(listOf(25 to "Light", 50 to "Medium", 75 to "Strong", 100 to "Maximum"), strength, enabled = !job.busy) {
                strength = it
            }
        }
        if (picked != null) {
            OptionCard("Estimated size") {
                when {
                    analyzing -> {
                        Text("Measuring this PDF...", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    estimate == null -> Text(
                        "Could not estimate the size. You can still compress; the real result is shown afterwards.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    !estimate.reducible -> Text(
                        "This PDF has no images that can be shrunk (it is mostly text), so the size will barely change.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> {
                        val original = estimate.originalSize
                        val expected = estimate.sizeAt(strength)
                        val saved = if (original > 0) ((1 - expected.toDouble() / original) * 100).roundToInt() else 0
                        Text(
                            "About ${Formatter.formatFileSize(context, expected)}",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            "Now ${Formatter.formatFileSize(context, original)}  ->  about $saved% smaller",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "This is an estimate. The real size is shown after compressing.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
        PrimaryButton("Compress", icon = AppIcons.Compress, enabled = picked != null && !job.busy && !analyzing, onClick = {
            val p = picked!!
            val st = strength
            job.launch(scope) {
                var summary = ""
                val out = OutputStore.savePdf(context, outName("Compressed", p.name)) { os ->
                    summary = PdfCompressor.compressAtStrength(context, p.uri, os, st)
                }
                "$summary Saved in Documents/PDF Manager" to out
            }
        })
        JobStatus(job, onOpen)
        RecentResults(job.result, { it.startsWith("Compressed_") }, onOpen)
    }
}

// ---------------------------------------------------------------- Pages per sheet

@Composable
fun NupScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    var perSheet by rememberSaveable { mutableIntStateOf(4) }
    var landscape by rememberSaveable { mutableStateOf(PdfLayout.defaultLandscape(4)) }
    val layout = remember(perSheet, landscape) { PdfLayout.nupLayout(perSheet, landscape) }
    var border by remember { mutableStateOf(true) }
    val job = remember { ToolJob() }
    val pageCount by rememberPageCount(picked)
    val pages = pageCount?.takeIf { it > 0 }
    ToolScaffold("Pages per sheet", onBack) {
        PdfPickerButton(picked) { picked = it }
        OptionCard("Pages per sheet") {
            ChipRow(PdfLayout.nupCounts.map { it to "$it" }, perSheet) {
                perSheet = it
                landscape = PdfLayout.defaultLandscape(it)
            }
            Text("Page orientation", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChipRow(listOf(false to "Portrait", true to "Landscape"), landscape) { landscape = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Draw a border around each page", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(border, { border = it })
            }
        }
        NupPreview(layout, border, pages, picked)
        PrimaryButton("Create", icon = AppIcons.GridView, enabled = picked != null && !job.busy, onClick = {
            val p = picked!!
            val l = layout
            val b = border
            job.launch(scope) {
                var sheets = 0
                val out = OutputStore.savePdf(context, outName("${l.perSheet}up", p.name)) { os ->
                    sheets = PdfLayout.nup(context, p.uri, os, l, b)
                }
                "Created $sheets sheets. Saved in Documents/PDF Manager" to out
            }
        })
        JobStatus(job, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
        RecentResults(job.result, { Regex("^\\d+up_").containsMatchIn(it) }, onOpen)
    }
}

// ---------------------------------------------------------------- Booklet

@Composable
fun BookletScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(ToolInput.takeFirst()) }
    val job = remember { ToolJob() }
    val pageCount by rememberPageCount(picked)
    val pages = pageCount?.takeIf { it > 0 }
    ToolScaffold("Booklet", onBack) {
        PdfPickerButton(picked) { picked = it }
        BookletPreview(pages)
        PrimaryButton("Create booklet", icon = AppIcons.AutoStories, enabled = picked != null && !job.busy, onClick = {
            val p = picked!!
            job.launch(scope) {
                var sides = 0
                val out = OutputStore.savePdf(context, outName("Booklet", p.name)) { os ->
                    sides = PdfLayout.booklet(context, p.uri, os)
                }
                "Created $sides sheet sides (${sides / 2} sheets). Saved in Documents/PDF Manager" to out
            }
        })
        JobStatus(job, onOpen)
        if (picked == null) RecentPdfsStrip { picked = it }
        RecentResults(job.result, { it.startsWith("Booklet_") }, onOpen)
    }
}
