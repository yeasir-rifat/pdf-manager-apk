package com.pdfmanager.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfmanager.app.data.OutputStore
import com.pdfmanager.app.data.PdfOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Picked(val uri: Uri, val name: String)

/** Files handed over from the library to a tool screen. Each screen takes them once when it opens. */
object ToolInput {
    private var pending: List<Picked> = emptyList()
    fun set(files: List<Picked>) { pending = files }
    fun take(): List<Picked> { val f = pending; pending = emptyList(); return f }
    fun takeFirst(): Picked? = take().firstOrNull()
}

fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
fun baseOf(name: String) = name.substringBeforeLast('.')

/** Output file name: "<Tool>_<original name>.pdf", e.g. Merged_Plant Pathology Sheet.pdf. Falls back to a timestamp when there is no source name. */
fun outName(prefix: String, source: String?): String =
    "${prefix}_${source?.takeIf { it.isNotBlank() }?.let { baseOf(it) } ?: stamp()}.pdf"

/** Settings switch: ask for the file name after a tool has made a file. */
object NamePref {
    private fun prefs(c: android.content.Context) = c.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    fun enabled(c: android.content.Context) = prefs(c).getBoolean("ask_name", false)
    fun set(c: android.content.Context, on: Boolean) = prefs(c).edit().putBoolean("ask_name", on).apply()
}

/** Bumped after a rename so every "Processed files" list reloads. */
object ResultsVersion { var tick by mutableStateOf(0) }

/** Runs one background operation and exposes its state to the UI. */
class ToolJob {
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
    var result by mutableStateOf<String?>(null)
    var progress by mutableStateOf<String?>(null)
    /** The file just made, offered for renaming when "Ask for file name" is on in Settings. */
    var renameCandidate by mutableStateOf<Uri?>(null)
    var allowRename = true

    fun fail(message: String) {
        status = message; failed = true; result = null
    }

    fun launch(scope: CoroutineScope, work: () -> Pair<String, Uri?>) {
        if (busy) return
        busy = true; status = null; failed = false; result = null; progress = null; renameCandidate = null
        scope.launch {
            try {
                val (msg, uri) = withContext(Dispatchers.IO) { work() }
                status = msg; result = uri?.toString()
                if (allowRename) renameCandidate = uri
            } catch (e: Exception) {
                fail("Failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false; progress = null
            }
        }
    }
}

// ---------------------------------------------------------------- Shared tool UI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    // Large title that shrinks into a normal app bar while scrolling
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    // A main-tab screen (Scanner, Settings) has no Back arrow
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(AppIcons.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            content()
        }
    }
}

/** A titled card that groups related options. */
@Composable
fun OptionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    AppCard(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel(title)
            content()
        }
    }
}

@Composable
fun JobStatus(
    job: ToolJob,
    onOpen: ((String) -> Unit)? = null,
    mime: String = "application/pdf",
    openIcon: ImageVector = AppIcons.PictureAsPdf
) {
    val context = LocalContext.current
    RenameDialog(job)
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (job.busy) {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(job.progress ?: "Working…", style = MaterialTheme.typography.titleSmall)
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                }
            }
        }
        job.status?.let { msg ->
            val ok = !job.failed
            val tint = if (ok) Accent.Green else MaterialTheme.colorScheme.error
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f))
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (ok) AppIcons.Check else AppIcons.ErrorOutline, null,
                                tint = tint, modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (ok) "Done" else "Something went wrong", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val r = job.result
                    if (r != null && onOpen != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { onOpen(r) }, shape = RoundedCornerShape(20.dp)) {
                                Icon(openIcon, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open")
                            }
                            FilledTonalButton(
                                onClick = { Share.files(context, listOf(r), mime, if (mime == "application/pdf") "Share PDF" else "Share file") },
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(AppIcons.Share, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Share")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PickedList(items: SnapshotStateList<Picked>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, p ->
            AppCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${i + 1}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    PdfThumb(
                        p.uri.toString(), Modifier.size(width = 36.dp, height = 48.dp),
                        isPdf = p.name.endsWith(".pdf", ignoreCase = true), widthPx = 160
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    IconButton(enabled = i > 0, onClick = { items.add(i - 1, items.removeAt(i)) }) {
                        Icon(AppIcons.KeyboardArrowUp, "Move up")
                    }
                    IconButton(enabled = i < items.lastIndex, onClick = { items.add(i + 1, items.removeAt(i)) }) {
                        Icon(AppIcons.KeyboardArrowDown, "Move down")
                    }
                    IconButton(onClick = { items.removeAt(i) }) { Icon(AppIcons.Close, "Remove") }
                }
            }
        }
    }
}

@Composable
fun PdfPickerButton(picked: Picked?, onPicked: (Picked) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(Picked(uri, PdfOps.displayName(context, uri)))
    }
    val info by produceState<String?>(null, picked) {
        value = picked?.let { p ->
            withContext(Dispatchers.IO) { runCatching { PdfOps.describe(context, p.uri) }.getOrNull() }
        }
    }
    FilePickCard(
        "PDF file", picked?.name, AppIcons.PictureAsPdf, Accent.Red, detail = info,
        leading = picked?.let { p -> { PdfThumb(p.uri.toString(), Modifier.size(width = 46.dp, height = 62.dp)) } }
    ) {
        launcher.launch(arrayOf("application/pdf"))
    }
}

// ---------------------------------------------------------------- Merge

@Composable
fun MergeScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<Picked>().apply { addAll(ToolInput.take()) } }
    val job = remember { ToolJob() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { items.add(Picked(it, PdfOps.displayName(context, it))) }
    }
    ToolScaffold("Merge PDF", onBack) {
        FilePickCard(
            "PDF files",
            if (items.isEmpty()) null else "${items.size} selected",
            AppIcons.PictureAsPdf, Accent.Indigo
        ) { picker.launch(arrayOf("application/pdf")) }
        PickedList(items)
        PrimaryButton("Merge PDFs", icon = AppIcons.MergeType, enabled = items.size >= 2 && !job.busy, onClick = {
            val uris = items.map { it.uri }
            job.launch(scope) {
                val out = OutputStore.savePdf(context, outName("Merged", items.firstOrNull()?.name)) { PdfOps.merge(context, uris, it) }
                "Merged ${uris.size} files. Saved in Documents/PDF Manager" to out
            }
        })
        JobStatus(job, onOpen)
        RecentPdfsStrip(exclude = items.map { it.uri.toString() }.toSet()) { items.add(it) }
        RecentResults(job.result, { it.startsWith("Merged_") }, onOpen)
    }
}

// ---------------------------------------------------------------- Image to PDF

@Composable
fun ImagesToPdfScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<Picked>() }
    val job = remember { ToolJob() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { items.add(Picked(it, PdfOps.displayName(context, it))) }
    }
    ToolScaffold("Image to PDF", onBack) {
        FilePickCard(
            "Images",
            if (items.isEmpty()) null else "${items.size} selected",
            AppIcons.Image, Accent.Teal
        ) { picker.launch(arrayOf("image/*")) }
        PickedList(items)
        PrimaryButton("Create PDF", icon = AppIcons.PictureAsPdf, enabled = items.isNotEmpty() && !job.busy, onClick = {
            val uris = items.map { it.uri }
            job.launch(scope) {
                var pages = 0
                val out = OutputStore.savePdf(context, outName("Images", items.firstOrNull()?.name)) { pages = PdfOps.imagesToPdf(context, uris, it) }
                "Created a PDF with $pages pages. Saved in Documents/PDF Manager" to out
            }
        })
        JobStatus(job, onOpen)
        RecentResults(job.result, { it.startsWith("Images_") }, onOpen)
    }
}

// ---------------------------------------------------------------- PDF to Image

@Composable
fun PdfToImagesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(ToolInput.takeFirst()) }
    val job = remember { ToolJob() }
    ToolScaffold("PDF to Image", onBack) {
        PdfPickerButton(picked) { picked = it }
        PrimaryButton("Convert to JPG", icon = AppIcons.PhotoLibrary, enabled = picked != null && !job.busy, onClick = {
            val p = picked!!
            job.launch(scope) {
                val n = PdfOps.pdfToImages(context, p.uri, "Image_${baseOf(p.name)}")
                "Saved $n images in Pictures/PDF Manager" to null
            }
        })
        JobStatus(job)
    }
}

// ---------------------------------------------------------------- Crop & Rotate

@Composable
private fun MarginSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${(value * 100).toInt()}%", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(value, onChange, valueRange = 0f..0.4f)
    }
}

private sealed interface PreviewState {
    object Loading : PreviewState
    object Failed : PreviewState
    class Ready(val bitmap: Bitmap, val pages: Int) : PreviewState
}

/** Shows a page with the area that will be cut dimmed, turned the way the result will be. */
@Composable
private fun CropPreview(file: Picked, rotation: Int, top: Float, bottom: Float, left: Float, right: Float) {
    val context = LocalContext.current
    var page by remember(file.uri) { mutableIntStateOf(0) }
    val state by produceState<PreviewState>(PreviewState.Loading, file.uri, page) {
        value = PreviewState.Loading
        val result = withContext(Dispatchers.IO) { PdfOps.renderPreview(context, file.uri, page, 700) }
        value = if (result == null) PreviewState.Failed else PreviewState.Ready(result.first, result.second)
    }

    OptionCard("Preview") {
        when (val s = state) {
            PreviewState.Loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
            PreviewState.Failed -> Text(
                "Preview is not available for this file. It may be locked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is PreviewState.Ready -> {
                val shown = remember(s.bitmap, rotation) {
                    if (rotation == 0) s.bitmap
                    else Bitmap.createBitmap(
                        s.bitmap, 0, 0, s.bitmap.width, s.bitmap.height,
                        Matrix().apply { postRotate(rotation.toFloat()) }, true
                    )
                }
                // Margins follow their edges when the page is turned
                val (mt, mb, ml, mr) = when (rotation) {
                    90 -> listOf(left, right, bottom, top)
                    180 -> listOf(bottom, top, right, left)
                    270 -> listOf(right, left, top, bottom)
                    else -> listOf(top, bottom, left, right)
                }
                val outline = MaterialTheme.colorScheme.primary
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val ratio = shown.width.toFloat() / shown.height
                    val maxHeight = 340.dp
                    val (w, h) = if (maxWidth / ratio <= maxHeight) maxWidth to maxWidth / ratio
                    else maxHeight * ratio to maxHeight
                    Box(Modifier.size(w, h).clip(RoundedCornerShape(6.dp)).background(Color.White)) {
                        Image(
                            shown.asImageBitmap(), contentDescription = "Page preview",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds
                        )
                        Canvas(Modifier.fillMaxSize()) {
                            val l = size.width * ml
                            val t = size.height * mt
                            val r = size.width * (1f - mr)
                            val b = size.height * (1f - mb)
                            val dim = Color.Black.copy(alpha = 0.5f)
                            drawRect(dim, Offset(0f, 0f), Size(size.width, t))
                            drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
                            drawRect(dim, Offset(0f, t), Size(l, b - t))
                            drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
                            drawRect(outline, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                }
                if (s.pages > 1) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(enabled = page > 0, onClick = { page -= 1 }) {
                            Icon(AppIcons.KeyboardArrowLeft, "Previous page")
                        }
                        Text("Page ${page + 1} of ${s.pages}", style = MaterialTheme.typography.labelLarge)
                        IconButton(enabled = page < s.pages - 1, onClick = { page += 1 }) {
                            Icon(AppIcons.KeyboardArrowRight, "Next page")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CropRotateScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(ToolInput.takeFirst()) }
    var rotation by remember { mutableIntStateOf(0) }
    var top by remember { mutableFloatStateOf(0f) }
    var bottom by remember { mutableFloatStateOf(0f) }
    var left by remember { mutableFloatStateOf(0f) }
    var right by remember { mutableFloatStateOf(0f) }
    val job = remember { ToolJob() }

    ToolScaffold("Crop & Rotate", onBack) {
        PdfPickerButton(picked) { picked = it }
        picked?.let { CropPreview(it, rotation, top, bottom, left, right) }
        OptionCard("Rotate all pages") {
            ChipRow(listOf(0 to "None", 90 to "90°", 180 to "180°", 270 to "270°"), rotation) { rotation = it }
        }
        OptionCard("Crop margins (every page)") {
            MarginSlider("Top", top) { top = it }
            MarginSlider("Bottom", bottom) { bottom = it }
            MarginSlider("Left", left) { left = it }
            MarginSlider("Right", right) { right = it }
        }
        PrimaryButton(
            "Apply", icon = AppIcons.CropRotate,
            enabled = picked != null && !job.busy && (rotation != 0 || top + bottom + left + right > 0f),
            onClick = {
                val p = picked!!
                val (rot, t, b, l, r) = listOf(rotation.toFloat(), top, bottom, left, right)
                job.launch(scope) {
                    val out = OutputStore.savePdf(context, outName("Cropped", p.name)) {
                        PdfOps.cropRotate(context, p.uri, it, rot.toInt(), t, b, l, r)
                    }
                    "Done. Saved in Documents/PDF Manager" to out
                }
            }
        )
        JobStatus(job, onOpen)
        RecentResults(job.result, { it.startsWith("Cropped_") || it.startsWith("Edited_") }, onOpen)
    }
}

// ---------------------------------------------------------------- Lock / Unlock

@Composable
fun LockUnlockScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(ToolInput.takeFirst()) }
    var lockMode by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val job = remember { ToolJob() }
    ToolScaffold("Lock / Unlock PDF", onBack) {
        PdfPickerButton(picked) { picked = it }
        OptionCard("Action") {
            ChipRow(listOf(true to "Lock", false to "Unlock"), lockMode) { lockMode = it }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) AppIcons.VisibilityOff else AppIcons.Visibility, "Show or hide password")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        PrimaryButton(
            if (lockMode) "Lock PDF" else "Unlock PDF",
            icon = if (lockMode) AppIcons.Lock else AppIcons.LockOpen,
            enabled = picked != null && password.isNotEmpty() && !job.busy,
            onClick = {
                val p = picked!!
                val pw = password
                val lock = lockMode
                job.launch(scope) {
                    val suffix = if (lock) "locked" else "unlocked"
                    val out = OutputStore.savePdf(context, outName(if (lock) "Locked" else "Unlocked", p.name)) {
                        if (lock) PdfOps.lock(context, p.uri, it, pw) else PdfOps.unlock(context, p.uri, it, pw)
                    }
                    "PDF $suffix. Saved in Documents/PDF Manager" to out
                }
            }
        )
        JobStatus(job, onOpen)
        RecentResults(job.result, { it.startsWith("Locked_") || it.startsWith("Unlocked_") }, onOpen)
    }
}


/** Lets the person rename the file a tool just saved. Shown only when the Settings switch is on. */
@Composable
private fun RenameDialog(job: ToolJob) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = job.renameCandidate ?: return
    if (!NamePref.enabled(context)) return
    val current by produceState<String?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }
    }
    val name = current?.takeIf { it.endsWith(".pdf", true) } ?: return
    var input by remember(name) { mutableStateOf(name.removeSuffix(".pdf")) }
    val cleaned = input.trim().removeSuffix(".pdf").replace(Regex("[\\\\/:*?\"<>|]"), "_")
    AlertDialog(
        onDismissRequest = { job.renameCandidate = null },
        title = { Text("Name your file") },
        text = {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, singleLine = true,
                label = { Text("File name") }, suffix = { Text(".pdf") }
            )
        },
        confirmButton = {
            TextButton(enabled = cleaned.isNotBlank(), onClick = {
                job.renameCandidate = null
                if ("$cleaned.pdf" != name) scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val v = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$cleaned.pdf")
                            }
                            context.contentResolver.update(uri, v, null, null) > 0
                        }.getOrDefault(false)
                    }
                    if (ok) {
                        job.status = "Saved as $cleaned.pdf in Documents/PDF Manager"
                        ResultsVersion.tick++
                    } else job.fail("Could not rename the file")
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { job.renameCandidate = null }) { Text("Keep name") } }
    )
}
