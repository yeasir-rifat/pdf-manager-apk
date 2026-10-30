package com.pdfmanager.app.ui

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfmanager.app.data.AppDatabase
import com.pdfmanager.app.data.OutputFile
import com.pdfmanager.app.data.OutputStore
import com.pdfmanager.app.data.PdfLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extra, related content for the tool screens: recent files, earlier results and layout previews. */

/** A row of the newest PDFs from the library. One tap picks a file. */
@Composable
fun RecentPdfsStrip(exclude: Set<String> = emptySet(), onPick: (Picked) -> Unit) {
    val context = LocalContext.current
    val recents by remember { AppDatabase.get(context).dao().observeNewest(10) }
        .collectAsState(initial = emptyList())
    val shown = recents.filter { it.uri !in exclude }
    if (shown.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Recent PDFs")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.uri }) { pdf ->
                AppCard(Modifier.width(112.dp), onClick = { onPick(Picked(Uri.parse(pdf.uri), pdf.name)) }) {
                    PdfThumb(
                        pdf.uri, Modifier.fillMaxWidth().height(128.dp),
                        stamp = "${pdf.modifiedSec}-${pdf.sizeBytes}",
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    Text(
                        pdf.name, Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Files this tool made before, newest first, with Open and Share. [refresh] reloads the list after a new result. */
@Composable
fun RecentResults(refresh: Any?, matches: (String) -> Boolean, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val loaded by produceState<List<OutputFile>?>(null, refresh, ResultsVersion.tick) {
        value = withContext(Dispatchers.IO) {
            runCatching { OutputStore.recentPdfs(context, 200).filter { matches(it.name) }.take(30) }
                .getOrDefault(emptyList())
        }
    }
    // Nothing is drawn until the list has loaded, so the page does not jump
    val files = loaded ?: return
    if (files.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(AppIcons.PictureAsPdf, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
            Text(
                "Processed files will appear here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Processed files")
        files.forEach { f ->
            AppCard(Modifier.fillMaxWidth(), onClick = { onOpen(f.uri.toString()) }) {
                Row(
                    Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PdfThumb(f.uri.toString(), Modifier.size(width = 36.dp, height = 48.dp), stamp = "${f.modifiedSec}-${f.size}")
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val whenMade = DateUtils.getRelativeTimeSpanString(
                            f.modifiedSec * 1000, System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
                        ).toString()
                        Text(
                            whenMade + " \u00B7 " + Formatter.formatShortFileSize(context, f.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { Share.pdfs(context, listOf(f.uri.toString())) }) {
                        Icon(AppIcons.Share, contentDescription = "Share")
                    }
                }
            }
        }
    }
}

/** Page count of the picked PDF, or null while unknown. */
@Composable
fun rememberPageCount(picked: Picked?): State<Int?> {
    val context = LocalContext.current
    return produceState<Int?>(null, picked?.uri) {
        value = picked?.let { p ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openFileDescriptor(p.uri, "r")?.use { fd ->
                        PdfRenderer(fd).use { it.pageCount }
                    }
                }.getOrNull()
            }
        }
    }
}

private val paperText = Color(0xFF5F6368)
private val paperLine = Color(0xFF9AA0A6)
private val paperFill = Color(0xFFEDEFF2)

/** A small drawing of one sheet with the chosen number of pages on it. */
@Composable
fun NupPreview(layout: PdfLayout.NupLayout, border: Boolean, pages: Int?) {
    OptionCard("Preview") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            val sheetW = if (layout.landscape) 150.dp else 106.dp
            val sheetH = if (layout.landscape) 106.dp else 150.dp
            Column(
                Modifier.size(sheetW, sheetH).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                for (r in 0 until layout.rows) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (c in 0 until layout.cols) {
                            val cell = Modifier.weight(1f).fillMaxHeight().let {
                                if (border) it.border(1.dp, paperLine) else it.background(paperFill)
                            }
                            Box(cell, contentAlignment = Alignment.Center) {
                                Text("${r * layout.cols + c + 1}", color = paperText, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${layout.perSheet} pages on each sheet", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (layout.landscape) "A4 landscape" else "A4 portrait",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (pages != null) "$pages pages make ${(pages + layout.perSheet - 1) / layout.perSheet} sheets"
                    else "Choose a PDF to see how many sheets you need",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Shows which pages land on the first sheet of a booklet, front and back. */
@Composable
fun BookletPreview(pages: Int?) {
    val n = pages ?: 8
    val padded = ((n + 3) / 4) * 4
    fun label(i: Int) = if (i < n) "${i + 1}" else "blank"
    OptionCard("How it will look") {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                "Front" to listOf(label(padded - 1), label(0)),
                "Back" to listOf(label(1), label(padded - 2))
            ).forEach { (name, cells) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.fillMaxWidth().height(86.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        cells.forEach { t ->
                            Box(
                                Modifier.weight(1f).fillMaxHeight().background(paperFill),
                                contentAlignment = Alignment.Center
                            ) { Text(t, color = paperText, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
            }
        }
        val blanks = padded - n
        Text(
            if (pages == null) "Example with 8 pages. Choose a PDF to see yours."
            else "$n pages" + (if (blanks > 0) " + $blanks blank" else "") + " \u00B7 ${padded / 4} sheets (${padded / 2} sides)",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Print double-sided, flip on the short edge, then fold the stack in half.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
