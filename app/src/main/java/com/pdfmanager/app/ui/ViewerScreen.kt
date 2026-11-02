package com.pdfmanager.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.luminance
import com.pdfmanager.app.data.PdfEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Size
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfmanager.app.data.AppDatabase
import com.pdfmanager.app.data.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import androidx.compose.ui.res.stringResource
import com.pdfmanager.app.R

/** Height of the floating top bar. The page list keeps this much room above its first page. */
private val ViewerBarHeight = 64.dp

/** How far (in pixels) a finger must scroll in one direction before the bar hides or shows. */
private const val BAR_SCROLL_SLOP = 24f

/** Largest bitmap width we ever render. Beyond this the page is just scaled up on screen. */
private const val MAX_RENDER_WIDTH = 4096

/** Search stops after this many matches so a very common word cannot flood the screen. */
private const val MAX_SEARCH_HITS = 2000

/** One glyph of a page with its box (0..1 of the page) and what follows it when copied (space / newline). */
class CharBox(val text: String, val l: Float, val t: Float, val r: Float, val b: Float, var trailing: String = "")

/** The selectable text of one page, in reading order. */
class PageText(val chars: List<CharBox>) {
    /** Index of the glyph nearest to the point; with [strict], -1 when nothing is close. */
    fun hit(x: Float, y: Float, strict: Boolean): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in chars.indices) {
            val c = chars[i]
            val dx = when { x < c.l -> c.l - x; x > c.r -> x - c.r; else -> 0f }
            val dy = when { y < c.t -> c.t - y; y > c.b -> y - c.b; else -> 0f }
            val d = dx * dx + dy * dy * 4f
            if (d < bestD) { bestD = d; best = i }
        }
        return if (strict && bestD > 0.0006f) -1 else best
    }

    fun wordStart(i: Int): Int {
        var k = i
        while (k > 0 && chars[k - 1].trailing.isEmpty()) k--
        return k
    }

    fun wordEnd(i: Int): Int {
        var k = i
        while (k < chars.lastIndex && chars[k].trailing.isEmpty()) k++
        return k
    }

    fun textBetween(a: Int, b: Int): String {
        val sb = StringBuilder()
        for (k in a..b) {
            sb.append(chars[k].text)
            if (k < b) sb.append(chars[k].trailing)
        }
        return sb.toString().trim()
    }
}

data class TextSelection(val page: Int, val a: Int, val b: Int)

/** One match of a search: glyphs [a]..[b] of [page]; [top] is where it starts on the page (0..1). */
data class SearchHit(val page: Int, val a: Int, val b: Int, val top: Float)

/** The matches to paint, grouped by page, and the one the reader is looking at. */
class SearchView(val byPage: Map<Int, List<SearchHit>>, val current: SearchHit?)

private val LocalNightMode = compositionLocalOf { false }

/** Backdrop behind the pages in Night mode: darker than the normal viewer background, so black pages stand out. */
private val NightBackdrop = androidx.compose.ui.graphics.Color(0xFF0E0E10)

/** Thin page edge for Night mode on the light theme. In the dark theme the edge uses outlineVariant. */
private val NightPageEdge = androidx.compose.ui.graphics.Color(0xFF3C3C43)

/** Turns white paper dark and black text light. */
private val NightFilter: ColorFilter by lazy {
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
}

/** A sharp, full-resolution piece of a zoomed page. [left]/[top] are in the page's own layout pixels. */
private class PageRegion(val bitmap: Bitmap, val left: Float, val top: Float, val scale: Float) {
    val image = bitmap.asImageBitmap()
}

/** Where the viewer window is on screen (root pixels). Plain fields: no recomposition while scrolling. */
private class ViewportRef {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f
}

/** Where one page is on screen (root pixels, after zoom). */
private class PageGeo {
    var x = 0f
    var y = 0f
}

/** Exact pixel height, so the bitmap and its box are the same size and nothing is resampled. */
private fun Modifier.fixedHeightPx(h: Int): Modifier = this.layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
    layout(p.width, p.height) { p.place(0, 0) }
}

/**
 * Wraps PdfRenderer. It allows only one open page at a time, so access is serialized.
 * Rendered pages are kept in a size-limited cache so scrolling back is instant, and
 * page shapes are read in the background so the list does not jump while it loads.
 */
class PdfHolder(context: Context, uri: Uri) {
    private val appContext = context.applicationContext
    private val srcUri = uri
    private val fd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
    private val renderer: PdfRenderer? = fd?.let {
        // PdfRenderer refuses password-protected or damaged files; do not leak the descriptor then
        try { PdfRenderer(it) } catch (e: Throwable) { runCatching { it.close() }; throw e }
    }
    private val mutex = Mutex()
    val pageCount: Int = renderer?.pageCount ?: 0

    /** Height / width of each page. Filled in by [loadSizes]; unknown pages use [defaultRatio]. */
    var ratios by mutableStateOf<FloatArray?>(null)
        private set
    val defaultRatio: Float get() = ratios?.getOrNull(0)?.takeIf { it > 0f } ?: 1.414f
    fun ratioOf(index: Int): Float = ratios?.getOrNull(index)?.takeIf { it > 0f } ?: defaultRatio

    /** Height in pixels of a page drawn [widthPx] wide. Used for both the bitmap and its box. */
    fun heightPx(index: Int, widthPx: Int): Int =
        (widthPx * ratioOf(index)).roundToInt().coerceIn(1, widthPx * 6)

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 5L).toInt().coerceAtLeast(16 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun keyOf(index: Int, widthPx: Int) = "$index|$widthPx"
    fun cached(index: Int, widthPx: Int): Bitmap? = cache.get(keyOf(index, widthPx))

    suspend fun loadSizes() = withContext(Dispatchers.IO) {
        val r = renderer ?: return@withContext
        val arr = FloatArray(pageCount)
        for (i in 0 until pageCount) {
            if (!isActive) return@withContext
            mutex.withLock {
                runCatching { r.openPage(i).use { arr[i] = it.height.toFloat() / it.width } }
            }
            if (i == 0 || i % 40 == 39) {
                ratios = arr.copyOf()
                yield()
            }
        }
        ratios = arr
    }

    /**
     * Renders only the part of a zoomed page that is on screen, at real screen resolution.
     * [s] is the current zoom, [pageLeft]/[pageTop] the page's on-screen position, and
     * ([iL], [iT], [iW], [iH]) the on-screen rectangle to draw, all in device pixels.
     */
    suspend fun renderRegion(
        index: Int, pageWpx: Int, pageHpx: Int, s: Float,
        pageLeft: Float, pageTop: Float, iL: Int, iT: Int, iW: Int, iH: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val r = renderer ?: return@withContext null
        mutex.withLock {
            try {
                r.openPage(index).use { page ->
                    val b = Bitmap.createBitmap(iW, iH, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    val m = android.graphics.Matrix()
                    m.postScale(pageWpx * s / page.width, pageHpx * s / page.height)
                    m.postTranslate(pageLeft - iL, pageTop - iT)
                    page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                }
            } catch (e: OutOfMemoryError) {
                cache.evictAll()
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Renders one page. Returns null if it fails (damaged page, out of memory). */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val w = widthPx.coerceIn(64, MAX_RENDER_WIDTH)
        cache.get(keyOf(index, w))?.let { return@withContext it }
        val r = renderer ?: return@withContext null
        mutex.withLock {
            cache.get(keyOf(index, w))?.let { return@withLock it }
            var width = w
            repeat(2) {
                try {
                    val bmp = r.openPage(index).use { page ->
                        val ratio = page.height.toFloat() / page.width
                        val height = (width * ratio).roundToInt().coerceIn(1, width * 6)
                        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                    cache.put(keyOf(index, w), bmp)
                    return@withLock bmp
                } catch (e: OutOfMemoryError) {
                    // Free memory and try once more at half the size
                    cache.evictAll()
                    width = (width / 2).coerceAtLeast(64)
                } catch (e: Exception) {
                    return@withLock null
                }
            }
            null
        }
    }


    // ---- Selectable text (read with PDFBox, only when the reader first asks for it)
    private val textCache = ConcurrentHashMap<Int, PageText>()
    private val textMutex = Mutex()
    private var textDoc: PDDocument? = null
    private var textFailed = false

    fun textNow(index: Int): PageText? = textCache[index]

    suspend fun textOf(index: Int): PageText? = withContext(Dispatchers.IO) {
        textCache[index]?.let { return@withContext it }
        textMutex.withLock {
            textCache[index]?.let { return@withLock it }
            if (textFailed) return@withLock null
            val doc = textDoc ?: runCatching {
                appContext.contentResolver.openInputStream(srcUri)?.use { PdfOps.load(it) }
            }.getOrNull()?.also { textDoc = it }
            if (doc == null) { textFailed = true; return@withLock null }
            val result = runCatching { extractText(doc, index) }.getOrNull()
            if (result != null) textCache[index] = result
            result
        }
    }

    /**
     * Finds [query] on every page (ignoring case) and reports the matches found so far after each page that
     * had any. Returns false when the text of this file cannot be read at all.
     */
    suspend fun search(query: String, onFound: (List<SearchHit>) -> Unit): Boolean {
        val found = ArrayList<SearchHit>()
        for (i in 0 until pageCount) {
            currentCoroutineContext().ensureActive()
            val wasCached = textCache.containsKey(i)
            val t = textOf(i)
            if (t == null) {
                if (textFailed) return false
                continue
            }
            var added = false
            if (t.chars.isNotEmpty() && found.size < MAX_SEARCH_HITS) {
                // Line breaks count as spaces, so a phrase still matches when it wraps onto the next line
                val starts = IntArray(t.chars.size)
                val sb = StringBuilder()
                for (k in t.chars.indices) {
                    starts[k] = sb.length
                    sb.append(t.chars[k].text)
                    if (t.chars[k].trailing.isNotEmpty()) sb.append(' ')
                }
                val text = sb.toString()
                var from = 0
                while (found.size < MAX_SEARCH_HITS) {
                    val m = text.indexOf(query, from, ignoreCase = true)
                    if (m < 0) break
                    val a = charIndexAt(starts, m)
                    val b = charIndexAt(starts, m + query.length - 1)
                    found.add(SearchHit(i, a, b, t.chars[a].t))
                    added = true
                    from = m + query.length
                }
            }
            // Pages without a match are not kept in memory; a long book would otherwise fill it
            if (!added && !wasCached) textCache.remove(i)
            if (added) onFound(found.toList())
        }
        return true
    }

    private fun charIndexAt(starts: IntArray, offset: Int): Int {
        val r = java.util.Arrays.binarySearch(starts, offset)
        return if (r >= 0) r else (-r - 2).coerceAtLeast(0)
    }

    private fun extractText(doc: PDDocument, index: Int): PageText {
        val chars = ArrayList<CharBox>()
        val raw = ArrayList<FloatArray>()   // x, top, right, bottom in page points
        var pw = 1f
        var ph = 1f
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                textPositions?.forEach { tp ->
                    val u = tp.unicode
                    if (u.isNullOrEmpty()) return@forEach
                    pw = tp.pageWidth
                    ph = tp.pageHeight
                    val h = tp.heightDir.takeIf { it > 0f } ?: tp.fontSizeInPt
                    val x = tp.xDirAdj
                    val y = tp.yDirAdj
                    val w = tp.widthDirAdj
                    chars.add(CharBox(u, 0f, 0f, 0f, 0f))
                    raw.add(floatArrayOf(x, y - h, x + w, y + h * 0.25f))
                }
            }
            override fun writeWordSeparator() {
                chars.lastOrNull()?.let { if (it.trailing.isEmpty()) it.trailing = " " }
            }
            override fun writeLineSeparator() {
                chars.lastOrNull()?.trailing = "\n"
            }
            override fun writeParagraphEnd() {
                chars.lastOrNull()?.trailing = "\n"
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.getText(doc)
        // PDFBox may report the page size before rotation: pick the orientation that matches what is shown
        val shown = ratioOf(index)
        if (pw > 0f && ph > 0f && Math.abs(ph / pw - shown) > Math.abs(pw / ph - shown)) { val tmp = pw; pw = ph; ph = tmp }
        val out = chars.indices.map { i ->
            val c = chars[i]
            val r = raw[i]
            CharBox(c.text, r[0] / pw, r[1] / ph, r[2] / pw, r[3] / ph, c.trailing)
        }
        return PageText(out)
    }

    fun close() {
        runCatching { cache.evictAll() }
        runCatching { textDoc?.close() }
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
    }
}

private sealed interface PageState {
    object Loading : PageState
    object Failed : PageState
    class Ready(val bitmap: Bitmap) : PageState
}

/** Asks for the password of a locked PDF. The text is hidden and can be shown with the eye button. */
@Composable
private fun PasswordDialog(error: String?, busy: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        modifier = Modifier.dialogEnter(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_enter_password)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                enabled = !busy,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty() && !busy) onSubmit(password) }),
                trailingIcon = {
                    TextButton(onClick = { visible = !visible }) { Text(stringResource(if (visible) R.string.common_hide else R.string.common_show)) }
                }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = password.isNotEmpty() && !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.common_open))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    uriString: String, onBack: () -> Unit,
    onPrint: (String, String) -> Unit = { _, _ -> },
    onTool: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Open the file off the main thread so the screen appears immediately
    var holder by remember(uriString) { mutableStateOf<PdfHolder?>(null) }
    var openFailed by remember(uriString) { mutableStateOf(false) }
    // A password-protected file is unlocked into a private temporary copy, and the viewer then shows that copy
    var openUri by remember(uriString) { mutableStateOf(uriString) }
    var needsPassword by remember(uriString) { mutableStateOf(false) }
    var passwordDialog by remember(uriString) { mutableStateOf(false) }
    var passwordError by remember(uriString) { mutableStateOf<String?>(null) }
    var unlocking by remember(uriString) { mutableStateOf(false) }
    LaunchedEffect(openUri) {
        val h = withContext(Dispatchers.IO) {
            runCatching { PdfHolder(context, Uri.parse(openUri)) }.getOrNull()
        }
        if (h == null || h.pageCount == 0) {
            h?.close()
            val locked = openUri == uriString &&
                withContext(Dispatchers.IO) { PdfOps.needsPassword(context, Uri.parse(uriString)) }
            if (locked) {
                needsPassword = true
                passwordDialog = true
            } else openFailed = true
        } else {
            holder = h
            needsPassword = false
            passwordDialog = false
        }
    }
    DisposableEffect(uriString) { onDispose { holder?.close() } }
    LaunchedEffect(holder) { holder?.loadSizes() }

    val title by produceState("PDF", uriString) {
        value = withContext(Dispatchers.IO) {
            runCatching { PdfOps.displayName(context, Uri.parse(uriString)) }.getOrDefault("PDF")
        }
    }

    // Remember that this file was opened, for the Recent list
    LaunchedEffect(uriString) {
        withContext(Dispatchers.IO) {
            runCatching { AppDatabase.get(context).dao().touch(uriString, System.currentTimeMillis() / 1000) }
        }
    }

    // Reopen where the reader stopped last time
    val prefs = remember { context.getSharedPreferences("positions", Context.MODE_PRIVATE) }
    // The key comes from the file name and size, so a backup can be restored on another phone
    val key = remember(uriString) { com.pdfmanager.app.data.ReaderState.keyFor(context, uriString) }
    val savedPage = remember(uriString) { prefs.getInt(key, 0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = savedPage)
    LaunchedEffect(listState, holder) {
        if (holder != null) {
            snapshotFlow { listState.firstVisibleItemIndex }.collect { prefs.edit().putInt(key, it).apply() }
        }
    }

    var selection by remember { mutableStateOf<TextSelection?>(null) }
    // Zoom is a transform on the whole page list (smooth, around the fingers); pages re-render sharper when it settles
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var renderScale by remember { mutableFloatStateOf(1f) }
    var viewW by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableFloatStateOf(0f) }
    // Ticks a moment after scrolling and zooming stop; zoomed pages then draw a sharp on-screen piece
    var settleTick by remember { mutableIntStateOf(0) }
    val viewport = remember { ViewportRef() }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(listState.isScrollInProgress, scale, offsetX, renderScale) }
            .collectLatest { st ->
                if (st[0] as Boolean) return@collectLatest
                delay(180)
                if (scale == renderScale) settleTick++
            }
    }

    fun applyZoom(factor: Float, focus: Offset, pan: Offset) {
        val s0 = scale
        var s1 = (s0 * factor).coerceIn(1f, 5f)
        if (factor < 1f && s1 < 1.05f) s1 = 1f
        val f = s1 / s0
        offsetX = (focus.x - (focus.x - offsetX) * f + pan.x).coerceIn(viewW * (1f - s1), 0f)
        scale = s1
        // Keep the point under the fingers in place vertically by scrolling the list
        val d = focus.y / s0 - focus.y / s1 - pan.y / s1
        if (d != 0f) listState.dispatchRawDelta(d)
    }

    fun zoomTo(target: Float, focus: Offset) {
        scope.launch {
            val start = scale
            val steps = 8
            for (i in 1..steps) {
                val st = start + (target - start) * i / steps
                applyZoom(st / scale, focus, Offset.Zero)
                delay(16)
            }
            if (target <= 1f) { scale = 1f; offsetX = 0f }
            renderScale = scale
        }
    }
    var showPages by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    // The page the reader is looking at (the one across the middle of the screen). Bookmarks use this,
    // since the first visible page can be just a sliver at the top.
    val bookmarkPage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val mid = info.viewportSize.height / 2
            val items = info.visibleItemsInfo
            val hit = items.firstOrNull { mid >= it.offset && mid <= it.offset + it.size }
                ?: items.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - mid) }
            (hit?.index ?: listState.firstVisibleItemIndex) + 1
        }
    }
    val dens = LocalDensity.current

    // Reading comfort: remembered between files
    var night by remember { mutableStateOf(ViewerPrefs.night(context)) }
    var keepOn by remember { mutableStateOf(ViewerPrefs.keepOn(context)) }
    var orient by remember { mutableIntStateOf(ViewerPrefs.orientation(context)) }
    ViewerWindowEffects(keepOn, orient)

    // Bookmarks of this file
    var bookmarks by remember(key) { mutableStateOf(ViewerBookmarks.get(context, key)) }
    var showBookmarks by remember { mutableStateOf(false) }
    val haptics = rememberAppHaptics()
    var showGoTo by remember { mutableStateOf(false) }
    var showRotation by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }

    // Search in the text of the PDF
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var hitIndex by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    val closeSearch = { searchOpen = false; query = "" }
    BackHandler(enabled = searchOpen) { closeSearch() }
    LaunchedEffect(query, holder, searchOpen) {
        hits = emptyList()
        hitIndex = 0
        searchFailed = false
        val hh = holder
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (!searchOpen || hh == null || q.isEmpty()) { searching = false; return@LaunchedEffect }
        delay(350)
        searching = true
        val ok = try {
            hh.search(q) { found -> hits = found }
        } finally {
            searching = false
        }
        if (!ok) searchFailed = true
    }
    val currentHit = hits.getOrNull(hitIndex)
    // Bring the match the reader is on into view, a third of the way down the screen
    LaunchedEffect(currentHit) {
        val hit = currentHit ?: return@LaunchedEffect
        val hh = holder ?: return@LaunchedEffect
        val pageW = (viewW - with(dens) { 24.dp.toPx() }).roundToInt().coerceAtLeast(1)
        val ph = hh.heightPx(hit.page, pageW)
        val off = (hit.top * ph - viewH / scale / 3f).roundToInt().coerceIn(0, (ph - 1).coerceAtLeast(0))
        listState.scrollToItem(hit.page, off)
    }
    val searchView = remember(hits, currentHit) {
        if (hits.isEmpty()) null else SearchView(hits.groupBy { it.page }, currentHit)
    }

    // Short messages (bookmark, copy) appear as a Snackbar above the bottom toolbar instead of a system Toast
    val snack = remember { SnackbarHostState() }
    val say: (String) -> Unit = { message ->
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    // Save a copy of the original file where the reader chooses
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { dest: Uri? ->
        if (dest != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uriString))!!.use { input ->
                        context.contentResolver.openOutputStream(dest, "wt")!!.use { out -> input.copyTo(out) }
                    }
                }.isSuccess
            }
            say(context.getString(if (ok) R.string.viewer_copy_saved else R.string.viewer_copy_failed))
        }
    }

    // Top bar auto-hide: reading down hides the bar, reading up brings it back, and a tap on a page
    // toggles it. The bar floats over the pages instead of being the Scaffold's top bar, and the
    // list keeps a fixed top padding, so hiding or showing it never moves a page.
    var barVisible by remember { mutableStateOf(true) }
    val barShown = barVisible || searchOpen || moreMenu || holder == null
    val atTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    LaunchedEffect(atTop) { if (atTop) barVisible = true }
    val barScroll = remember {
        object : NestedScrollConnection {
            private var acc = 0f
            // Only finger scrolling reaches this; jumps (fast scroller, Go to page) leave the bar alone
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy == 0f) return Offset.Zero
                if ((dy > 0f) != (acc > 0f)) acc = 0f
                acc += dy
                if (acc < -BAR_SCROLL_SLOP) barVisible = false
                else if (acc > BAR_SCROLL_SLOP) barVisible = true
                return Offset.Zero
            }
        }
    }
    // One place for the bookmark logic: the bottom toolbar and the ⋮ menu both use it
    val toggleBookmark: () -> Unit = {
        val page = bookmarkPage
        val added = ViewerBookmarks.toggle(context, key, page)
        bookmarks = ViewerBookmarks.get(context, key)
        if (added) haptics.tick()
        say(
            if (added) context.getString(R.string.viewer_bookmarked, page) else context.getString(R.string.viewer_bookmark_removed)
        )
    }
    val viewerBar: @Composable () -> Unit = {
        // The search bar and the normal top bar share one spot, so they fade into each other
        Crossfade(targetState = searchOpen, animationSpec = tween(150), label = "viewerBar") { searchShown ->
            if (searchShown) {
                ViewerSearchBar(
                    query = query, onQuery = { query = it },
                    status = when {
                        searchFailed -> stringResource(R.string.viewer_not_available)
                        hits.isNotEmpty() -> "${hitIndex + 1} / ${hits.size}${if (hits.size >= MAX_SEARCH_HITS) "+" else ""}"
                        query.isNotBlank() && !searching -> stringResource(R.string.viewer_no_results)
                        else -> ""
                    },
                    busy = searching, canStep = hits.size > 1,
                    onPrev = { if (hits.isNotEmpty()) hitIndex = (hitIndex - 1 + hits.size) % hits.size },
                    onNext = { if (hits.isNotEmpty()) hitIndex = (hitIndex + 1) % hits.size },
                    onClose = closeSearch,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
            } else TopAppBar(
                title = {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { Share.pdfs(context, listOf(uriString)) }) {
                        Icon(AppIcons.Share, contentDescription = stringResource(R.string.common_share))
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(AppIcons.MoreVert, stringResource(R.string.common_more)) }
                        ViewerMoreMenu(
                            expanded = moreMenu, onDismiss = { moreMenu = false },
                            ready = holder != null,
                            night = night, keepOn = keepOn, orientationLabel = ViewerPrefs.orientationLabel(context, orient),
                            bookmarked = bookmarkPage in bookmarks, bookmarkCount = bookmarks.size,
                            canZoomIn = scale < 4.99f, canZoomOut = scale > 1.01f,
                            onGoTo = { showGoTo = true },
                            onSearch = { searchOpen = true },
                            onBookmark = toggleBookmark,
                            onBookmarks = { showBookmarks = true },
                            onNight = { night = !night; ViewerPrefs.setNight(context, night) },
                            onKeepOn = { keepOn = !keepOn; ViewerPrefs.setKeepOn(context, keepOn) },
                            onRotation = { showRotation = true },
                            onZoomIn = { zoomTo((scale * 1.5f).coerceAtMost(5f), Offset(viewW / 2, viewH / 2)) },
                            onZoomOut = { zoomTo((scale / 1.5f).coerceAtLeast(1f), Offset(viewW / 2, viewH / 2)) },
                            onFit = { zoomTo(1f, Offset(viewW / 2, viewH / 2)) },
                            onPrint = { onPrint(openUri, title) },
                            onEdit = { showTools = true },
                            onOpenWith = { Share.openWith(context, uriString) },
                            onSave = { saveCopy.launch(if (title.endsWith(".pdf", true)) title else "$title.pdf") },
                            onInfo = { showInfo = true }
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    }

    Scaffold(
        containerColor = if (night) NightBackdrop else MaterialTheme.colorScheme.surfaceContainerHigh,
        // Lifted above the floating bottom toolbar while it is showing
        snackbarHost = {
            SnackbarHost(
                snack,
                Modifier.padding(bottom = if (barShown && holder != null && !searchOpen) ViewerBottomBarHeight else 0.dp)
            )
        },
        topBar = {
            // Only the status-bar area stays here, so the pages never run behind the system icons
            Spacer(
                Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
        val h = holder
        when {
            openFailed -> EmptyState(
                icon = AppIcons.ErrorOutline,
                title = stringResource(R.string.viewer_cant_open_title),
                message = stringResource(R.string.viewer_cant_open_msg),
                modifier = Modifier.padding(padding)
            )
            needsPassword -> {
                EmptyState(
                    icon = AppIcons.Lock,
                    title = stringResource(R.string.viewer_locked_title),
                    message = stringResource(R.string.viewer_locked_msg),
                    modifier = Modifier.padding(padding),
                    action = { Button(onClick = { passwordError = null; passwordDialog = true }) { Text(stringResource(R.string.viewer_enter_password)) } }
                )
                if (passwordDialog) {
                    PasswordDialog(
                        error = passwordError,
                        busy = unlocking,
                        onDismiss = { if (!unlocking) passwordDialog = false },
                        onSubmit = { pw ->
                            scope.launch {
                                unlocking = true
                                passwordError = null
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { PdfOps.unlockForViewing(context, Uri.parse(uriString), pw) }
                                }
                                unlocking = false
                                result.onSuccess { openUri = Uri.fromFile(it).toString() }
                                    .onFailure {
                                        passwordError = if (it.message == "Wrong password") context.getString(R.string.viewer_wrong_password)
                                        else context.getString(R.string.viewer_open_failed)
                                    }
                            }
                        }
                    )
                }
            }
            h == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
            }
            else -> Box(Modifier.fillMaxSize().padding(padding)) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    var pagePx by remember { mutableIntStateOf(0) }
                    // Render the pages just below and above the screen while the reader is reading,
                    // so the next page is already there when it scrolls into view. Skipped while
                    // zoomed in (big bitmaps) and cancelled as soon as the reader scrolls again.
                    val density = LocalDensity.current
                    LaunchedEffect(h, pagePx, renderScale) {
                        if (renderScale > 1.01f || pagePx <= 0) return@LaunchedEffect
                        val widthPx = pagePx.coerceIn(64, MAX_RENDER_WIDTH)
                        snapshotFlow {
                            val info = listState.layoutInfo.visibleItemsInfo
                            (info.firstOrNull()?.index ?: 0) to (info.lastOrNull()?.index ?: 0)
                        }.collectLatest { (first, last) ->
                            delay(150)
                            val targets = listOf(last + 1, last + 2, first - 1)
                            for (i in targets) {
                                if (i in 0 until h.pageCount && h.cached(i, widthPx) == null) h.render(i, widthPx)
                            }
                        }
                    }
                    val panState = rememberDraggableState { dx ->
                        offsetX = (offsetX + dx).coerceIn(viewW * (1f - scale), 0f)
                    }
                    Box(
                        Modifier.fillMaxSize().clipToBounds()
                            .nestedScroll(barScroll)
                            .onSizeChanged { viewW = it.width.toFloat(); viewH = it.height.toFloat() }
                            .onGloballyPositioned { c ->
                                val p = c.positionInRoot()
                                viewport.left = p.x
                                viewport.top = p.y
                                viewport.right = p.x + c.size.width
                                viewport.bottom = p.y + c.size.height
                            }
                            // Pinch to zoom + two-finger pan, watched before the list so scrolling does not fight it
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    var multi = false
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.count { it.pressed } >= 2) {
                                            multi = true
                                            val z = event.calculateZoom()
                                            val pan = event.calculatePan()
                                            if (z != 1f || pan != Offset.Zero) {
                                                applyZoom(z, event.calculateCentroid(useCurrent = false), pan)
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                    if (multi) {
                                        if (scale < 1.05f && scale != 1f) { scale = 1f; offsetX = 0f }
                                        renderScale = scale
                                    }
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { if (selection != null) selection = null else barVisible = !barVisible },
                                    onDoubleTap = { pos -> zoomTo(if (scale > 1.2f) 1f else 2.5f, pos) }
                                )
                            }
                            // One-finger sideways pan while zoomed in
                            .draggable(panState, Orientation.Horizontal, enabled = scale > 1.01f)
                    ) {
                        CompositionLocalProvider(LocalNightMode provides night) {
                        LazyColumn(
                            Modifier.fillMaxSize()
                                // The list is drawn scaled up from its top-left corner, so only 1/scale of its
                                // height is visible. Give it exactly that height; with the full height, the
                                // bottom part of the last page can never be scrolled into view while zoomed.
                                .layout { measurable, constraints ->
                                    val listH = (constraints.maxHeight / scale).roundToInt().coerceAtLeast(1)
                                    val placeable = measurable.measure(constraints.copy(minHeight = listH, maxHeight = listH))
                                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                                }
                                .graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 0f)
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                            },
                            state = listState,
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = ViewerBarHeight + 12.dp, bottom = (12.dp + ViewerBottomBarHeight) / scale),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(count = h.pageCount, key = { it }) { index ->
                                Box {
                                    PdfPage(h, index, renderScale, settleTick, viewport, { pagePx = it }, selection, searchView) { selection = it }
                                    if ((index + 1) in bookmarks) {
                                        // Small ribbon on a bookmarked page; kept the same size on screen at any zoom
                                        Row(
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(end = 14.dp)
                                                .graphicsLayer {
                                                    transformOrigin = TransformOrigin(1f, 0f)
                                                    scaleX = 1f / scale
                                                    scaleY = 1f / scale
                                                }
                                                .background(
                                                    MaterialTheme.colorScheme.primary,
                                                    RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(AppIcons.Bookmark, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                            Spacer(Modifier.width(3.dp))
                                            Text("Bookmark", fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                }

                // Thin reading-progress line
                LinearProgressIndicator(
                    progress = { current.toFloat() / h.pageCount },
                    modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                    trackColor = androidx.compose.ui.graphics.Color.Transparent
                )

                // Copy bar for selected text
                val sel = selection
                if (sel != null) {
                    Surface(
                        Modifier.align(Alignment.TopCenter).padding(top = ViewerBarHeight + 10.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shadowElevation = 6.dp
                    ) {
                        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val t = h.textNow(sel.page)
                                    if (t != null && sel.b < t.chars.size) {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("PDF text", t.textBetween(sel.a, sel.b)))
                                        say(context.getString(R.string.common_copied))
                                    }
                                    selection = null
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)
                            ) {
                                Icon(AppIcons.ContentCopy, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.viewer_copy))
                            }
                            TextButton(
                                onClick = {
                                    val t = h.textNow(sel.page)
                                    if (t != null && t.chars.isNotEmpty()) selection = TextSelection(sel.page, 0, t.chars.lastIndex)
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)
                            ) { Text(stringResource(R.string.common_select_all)) }
                            IconButton(onClick = { selection = null }) {
                                Icon(AppIcons.Close, stringResource(R.string.viewer_clear_selection), tint = MaterialTheme.colorScheme.inverseOnSurface)
                            }
                        }
                    }
                }

                // Drag the handle on the right edge to move through many pages quickly
                FastScroller(
                    listState, h.pageCount,
                    Modifier.align(Alignment.CenterEnd).padding(top = ViewerBarHeight + 8.dp, bottom = 8.dp + ViewerBottomBarHeight)
                )

                // Small floating "12 / 158" badge; tap it to jump to a page
                PageIndicatorPill(
                    listState, current, h.pageCount,
                    onClick = { showGoTo = true },
                    // The Scaffold padding around this box already keeps clear of the navigation bar
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = AppDimens.space16 + ViewerBottomBarHeight)
                )
            }
        }
        // The bar itself, floating over the pages
        AnimatedVisibility(
            visible = barShown,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding())
        ) { viewerBar() }
        // Quick toolbar at the bottom, shown and hidden together with the top bar. It leaves while searching.
        AnimatedVisibility(
            visible = barShown && holder != null && !searchOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ViewerBottomBar(
                bookmarked = bookmarkPage in bookmarks,
                onSearch = { searchOpen = true },
                onPages = { showPages = true },
                onBookmark = toggleBookmark,
                onEdit = { showTools = true }
            )
        }
        }
    }

    val h = holder
    if (showPages && h != null) {
        ModalBottomSheet(
            onDismissRequest = { showPages = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Text(
                stringResource(R.string.viewer_pages_title, h.pageCount),
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium
            )
            val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
            LazyVerticalGrid(
                GridCells.Adaptive(96.dp),
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                state = gridState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items((0 until h.pageCount).toList(), key = { it }) { index ->
                    Column(
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                            showPages = false
                            haptics.tick()
                            scope.launch { listState.scrollToItem(index) }
                        },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PageThumb(h, index, selected = index + 1 == current)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${index + 1}", style = MaterialTheme.typography.labelMedium,
                            color = if (index + 1 == current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showGoTo && h != null) {
        GoToPageDialog(h.pageCount, current, onDismiss = { showGoTo = false }) { page ->
            showGoTo = false
            haptics.tick()
            scope.launch { listState.scrollToItem(page - 1) }
        }
    }
    if (showBookmarks) {
        BookmarksDialog(
            bookmarks, onDismiss = { showBookmarks = false },
            onOpen = { page -> showBookmarks = false; haptics.tick(); scope.launch { listState.scrollToItem(page - 1) } },
            onRemove = { page ->
                ViewerBookmarks.remove(context, key, page)
                bookmarks = ViewerBookmarks.get(context, key)
                if (bookmarks.isEmpty()) showBookmarks = false
            }
        )
    }
    if (showRotation) {
        RotationDialog(orient, onDismiss = { showRotation = false }) { mode ->
            orient = mode
            ViewerPrefs.setOrientation(context, mode)
            showRotation = false
        }
    }
    if (showInfo) {
        val info by produceState<DocInfo?>(null, openUri) { value = loadDocInfo(context, Uri.parse(openUri), title) }
        DocInfoDialog(info) { showInfo = false }
    }
    if (showTools) {
        SendToToolDialog(
            files = listOf(PdfEntity(openUri, title, 0L, 0L)),
            onDismiss = { showTools = false },
            onPick = { route ->
                ToolInput.set(listOf(Picked(Uri.parse(openUri), title)))
                showTools = false
                onTool(route)
            }
        )
    }
}

@Composable
private fun PdfPage(
    holder: PdfHolder, index: Int, zoom: Float, settleTick: Int, viewport: ViewportRef,
    onWidthPx: (Int) -> Unit,
    selection: TextSelection? = null, search: SearchView? = null, onSelect: (TextSelection?) -> Unit = {}
) {
    // The page is exactly as wide as the list gives it, and the bitmap is rendered at exactly that many
    // pixels, so it is drawn 1:1 (no stretching, no blur)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        PdfPageContent(holder, index, constraints.maxWidth, zoom, settleTick, viewport, onWidthPx, selection, search, onSelect)
    }
}

@Composable
private fun PdfPageContent(
    holder: PdfHolder, index: Int, widthPx: Int, zoom: Float, settleTick: Int, viewport: ViewportRef,
    onWidthPx: (Int) -> Unit,
    selection: TextSelection?, search: SearchView?, onSelect: (TextSelection?) -> Unit
) {
    LaunchedEffect(widthPx) { onWidthPx(widthPx) }
    val night = LocalNightMode.current
    val rw = widthPx.coerceIn(64, MAX_RENDER_WIDTH)
    val heightPx = holder.heightPx(index, widthPx)
    var region by remember(holder, index) { mutableStateOf<PageRegion?>(null) }
    val geo = remember { PageGeo() }
    val zoomNow by rememberUpdatedState(zoom)
    LaunchedEffect(settleTick, widthPx, heightPx) {
        val s = zoomNow
        if (s <= 1.05f) { region = null; return@LaunchedEffect }
        val pL = geo.x
        val pT = geo.y
        val margin = 96f
        val iL = floor(maxOf(pL, viewport.left - margin)).toInt()
        val iT = floor(maxOf(pT, viewport.top - margin)).toInt()
        val iR = ceil(minOf(pL + widthPx * s, viewport.right + margin)).toInt()
        val iB = ceil(minOf(pT + heightPx * s, viewport.bottom + margin)).toInt()
        if (iR - iL < 16 || iB - iT < 16 || (iR - iL).toLong() * (iB - iT) > 16_000_000L) {
            region = null
            return@LaunchedEffect
        }
        val bmp = holder.renderRegion(index, widthPx, heightPx, s, pL, pT, iL, iT, iR - iL, iB - iT)
        region = bmp?.let { PageRegion(it, (iL - pL) / s, (iT - pT) / s, s) }
    }
    var lastBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val pageScope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var pageText by remember(holder, index) { mutableStateOf(holder.textNow(index)) }
    val mySel = selection?.takeIf { it.page == index }
    val selNow by rememberUpdatedState(mySel)
    val onSelectNow by rememberUpdatedState(onSelect)
    val highlight = MaterialTheme.colorScheme.primary
    val handleR = with(LocalDensity.current) { 8.dp.toPx() }
    var attempt by remember { mutableIntStateOf(0) }
    // A page with search matches needs its text boxes to paint them
    val hasHits = search?.byPage?.containsKey(index) == true
    LaunchedEffect(hasHits, holder, index) {
        if (hasHits && pageText == null) pageText = holder.textOf(index)
    }
    val state by produceState<PageState>(
        holder.cached(index, rw)?.let { PageState.Ready(it) } ?: PageState.Loading,
        index, rw, attempt
    ) {
        // Always (re)load: the width can change, and the state keeps its old value across keys
        val bmp = holder.cached(index, rw) ?: holder.render(index, rw)
        value = if (bmp != null) PageState.Ready(bmp) else PageState.Failed
    }
    // Pages stay white in dark mode, like paper. Light mode: a soft, wide shadow lifts the page off the
    // background. A shadow cannot be seen on dark backgrounds (dark theme, Night mode), so a thin
    // edge line marks the page there instead.
    val pageShape = RoundedCornerShape(4.dp)
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pageEdgeColor = if (night && !darkTheme) NightPageEdge else MaterialTheme.colorScheme.outlineVariant
    val raised = if (darkTheme || night) Modifier.shadow(3.dp, pageShape)
    else Modifier.shadow(
        6.dp, pageShape,
        ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.10f),
        spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.16f)
    )
    Box(
        Modifier.fillMaxWidth().fixedHeightPx(heightPx)
            .onGloballyPositioned { c -> val p = c.positionInRoot(); geo.x = p.x; geo.y = p.y }
            .then(raised)
            .clip(pageShape).background(if (night) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White)
            .then(if (darkTheme || night) Modifier.border(Dp.Hairline, pageEdgeColor, pageShape) else Modifier)
            // Long-press a word, then drag to extend the selection
            .pointerInput(holder, index) {
                var anchorA = 0
                var anchorB = 0
                detectDragGesturesAfterLongPress(
                    onDragStart = { off ->
                        pageScope.launch {
                            val t = pageText ?: holder.textOf(index).also { pageText = it }
                            if (t == null || t.chars.isEmpty()) {
                                Toast.makeText(ctx, ctx.getString(R.string.viewer_no_selectable_text), Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val i = t.hit(off.x / size.width, off.y / size.height, strict = true)
                            if (i >= 0) {
                                anchorA = t.wordStart(i)
                                anchorB = t.wordEnd(i)
                                onSelectNow(TextSelection(index, anchorA, anchorB))
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        val t = pageText
                        if (t != null && selNow != null) {
                            change.consume()
                            val j = t.hit(change.position.x / size.width, change.position.y / size.height, strict = false)
                            if (j >= 0) onSelectNow(TextSelection(index, minOf(anchorA, j), maxOf(anchorB, j)))
                        }
                    }
                )
            }
            // Drag the two handles of an existing selection
            .pointerInput(holder, index) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cur = selNow
                    val t = pageText
                    if (cur == null || t == null || cur.b >= t.chars.size) return@awaitEachGesture
                    val first = t.chars[cur.a]
                    val last = t.chars[cur.b]
                    val sx = first.l * size.width
                    val sy = first.b * size.height + handleR
                    val ex = last.r * size.width
                    val ey = last.b * size.height + handleR
                    val slop = 28.dp.toPx()
                    val dStart = Math.hypot((down.position.x - sx).toDouble(), (down.position.y - sy).toDouble())
                    val dEnd = Math.hypot((down.position.x - ex).toDouble(), (down.position.y - ey).toDouble())
                    if (minOf(dStart, dEnd) > slop) return@awaitEachGesture
                    val fixed = if (dStart <= dEnd) cur.b else cur.a
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (ch.positionChange() != Offset.Zero) {
                            ch.consume()
                            // The handle hangs below the text, so aim above the finger
                            val j = t.hit(ch.position.x / size.width, (ch.position.y - handleR * 2) / size.height, strict = false)
                            if (j >= 0) onSelectNow(TextSelection(index, minOf(j, fixed), maxOf(j, fixed)))
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when (val s = state) {
            is PageState.Ready -> {
                SideEffect { lastBitmap = s.bitmap }
                Image(
                    s.bitmap.asImageBitmap(), contentDescription = stringResource(R.string.viewer_page_n, index + 1),
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                    colorFilter = if (night) NightFilter else null
                )
            }
            PageState.Loading -> {
                val old = lastBitmap
                if (old != null) Image(
                    old.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                    colorFilter = if (night) NightFilter else null
                ) else Box(Modifier.fillMaxSize().shimmer(dark = night))
            }
            PageState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.viewer_page_failed, index + 1),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (night) androidx.compose.ui.graphics.Color.LightGray else androidx.compose.ui.graphics.Color.DarkGray
                )
                TextButton(onClick = { attempt++ }) {
                    Icon(AppIcons.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.common_try_again))
                }
            }
        }
        region?.let { r ->
            Canvas(Modifier.matchParentSize()) {
                translate(left = r.left, top = r.top) {
                    scale(scale = 1f / r.scale, pivot = Offset.Zero) {
                        drawImage(r.image, colorFilter = if (night) NightFilter else null)
                    }
                }
            }
        }
        val t = pageText
        val pageHits = search?.byPage?.get(index)
        if (!pageHits.isNullOrEmpty() && t != null) {
            Canvas(Modifier.matchParentSize()) {
                val others = androidx.compose.ui.graphics.Color(0xFFFFEB3B).copy(alpha = 0.45f)
                val chosen = androidx.compose.ui.graphics.Color(0xFFFF9800).copy(alpha = 0.6f)
                for (hit in pageHits) {
                    val fill = if (hit == search?.current) chosen else others
                    for (k in hit.a..hit.b) {
                        if (k >= t.chars.size) break
                        val c = t.chars[k]
                        drawRect(
                            fill,
                            Offset(c.l * size.width, c.t * size.height),
                            Size((c.r - c.l) * size.width, (c.b - c.t) * size.height)
                        )
                    }
                }
            }
        }
        if (mySel != null && t != null && mySel.b < t.chars.size) {
            Canvas(Modifier.matchParentSize()) {
                val fill = highlight.copy(alpha = 0.35f)
                for (k in mySel.a..mySel.b) {
                    val c = t.chars[k]
                    drawRect(
                        fill,
                        Offset(c.l * size.width, c.t * size.height),
                        Size((c.r - c.l) * size.width, (c.b - c.t) * size.height)
                    )
                }
                val first = t.chars[mySel.a]
                val last = t.chars[mySel.b]
                val sx = first.l * size.width
                val sy = first.b * size.height
                val ex = last.r * size.width
                val ey = last.b * size.height
                drawLine(highlight, Offset(sx, first.t * size.height), Offset(sx, sy), strokeWidth = 2f)
                drawLine(highlight, Offset(ex, last.t * size.height), Offset(ex, ey), strokeWidth = 2f)
                drawCircle(highlight, handleR, Offset(sx, sy + handleR))
                drawCircle(highlight, handleR, Offset(ex, ey + handleR))
            }
        }
    }
}

@Composable
private fun PageThumb(holder: PdfHolder, index: Int, selected: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Rendered at exactly the cell's pixel width, so it is never scaled
        val widthPx = constraints.maxWidth.coerceIn(64, MAX_RENDER_WIDTH)
        PageThumbBox(holder, index, selected, widthPx)
    }
}

@Composable
private fun PageThumbBox(holder: PdfHolder, index: Int, selected: Boolean, widthPx: Int) {
    val bmp by produceState<Bitmap?>(holder.cached(index, widthPx), index, widthPx) {
        value = holder.cached(index, widthPx) ?: holder.render(index, widthPx)
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.fillMaxWidth().fixedHeightPx(holder.heightPx(index, widthPx)).clip(shape)
            .background(androidx.compose.ui.graphics.Color.White)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape
            ),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        } else {
            Box(Modifier.fillMaxSize().shimmer())
        }
    }
}


/**
 * Floating page badge at the bottom center. Shows while the reader scrolls and for 1.5 seconds
 * after, then fades out. Tapping it opens the "Go to page" dialog.
 */
@Composable
private fun PageIndicatorPill(
    state: LazyListState,
    current: Int,
    pageCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) visible = true
        else {
            delay(1500)
            visible = false
        }
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val shape = RoundedCornerShape(percent = 50)
        Surface(
            Modifier.clip(shape).clickable(onClick = onClick),
            shape = shape,
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)
        ) {
            Text(
                "$current / $pageCount",
                Modifier.padding(horizontal = AppDimens.space12, vertical = AppDimens.space8),
                style = AppText.caption,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Drive-style fast scroller: a handle on the right edge with the page number beside it.
 * Appears while scrolling; drag it up and down to jump through many pages quickly.
 */
@Composable
private fun FastScroller(state: LazyListState, pageCount: Int, modifier: Modifier = Modifier) {
    if (pageCount < 2) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    val visibleNow by rememberUpdatedState(visible)
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) visible = true
        else {
            delay(1500)
            visible = false
        }
    }
    val listFrac by remember(state, pageCount) {
        derivedStateOf {
            val first = state.layoutInfo.visibleItemsInfo.firstOrNull()
            when {
                first == null -> 0f
                !state.canScrollForward -> 1f
                else -> {
                    val within = state.firstVisibleItemScrollOffset.toFloat() / first.size.coerceAtLeast(1)
                    ((state.firstVisibleItemIndex + within) / (pageCount - 1)).coerceIn(0f, 1f)
                }
            }
        }
    }
    val thumbH = 48.dp
    BoxWithConstraints(modifier.fillMaxHeight().width(64.dp)) {
        val trackPx = with(density) { (maxHeight - thumbH).toPx() }.coerceAtLeast(1f)
        val thumbPx = with(density) { thumbH.toPx() }
        val frac = if (dragging) dragFrac else listFrac
        val page = (frac * (pageCount - 1)).roundToInt() + 1
        Box(
            Modifier.fillMaxSize().pointerInput(pageCount, trackPx) {
                // Only grabs the touch when it starts on the handle; everything else passes through
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val top = (if (dragging) dragFrac else listFrac) * trackPx
                    if (!visibleNow || down.position.y < top || down.position.y > top + thumbPx) return@awaitEachGesture
                    down.consume()
                    dragging = true
                    dragFrac = listFrac
                    do {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dy = ch.positionChange().y
                        if (dy != 0f) {
                            ch.consume()
                            dragFrac = (dragFrac + dy / trackPx).coerceIn(0f, 1f)
                            val target = (dragFrac * (pageCount - 1)).roundToInt()
                            scope.launch { state.scrollToItem(target) }
                        }
                    } while (event.changes.any { it.pressed })
                    dragging = false
                }
            }
        ) {
            AnimatedVisibility(
                visible = visible || dragging,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (frac * trackPx).roundToInt()) }
            ) {
                // Unbounded width: the page number may grow (1, 10, 100 ...) without squeezing the handle
                Row(
                    Modifier.wrapContentWidth(Alignment.End, unbounded = true).height(thumbH),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f)
                    ) {
                        Text(
                            "$page",
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 4.dp
                    ) {
                        Box(Modifier.size(width = 34.dp, height = thumbH), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.UnfoldMore, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
