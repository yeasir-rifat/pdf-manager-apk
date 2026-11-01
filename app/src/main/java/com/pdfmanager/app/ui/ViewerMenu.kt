package com.pdfmanager.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.pdfmanager.app.R

// ---------------------------------------------------------------- remembered choices

/** Viewer settings that stay the same the next time a PDF is opened. */
object ViewerPrefs {
    private fun p(c: Context) = c.getSharedPreferences("viewer", Context.MODE_PRIVATE)

    fun night(c: Context) = p(c).getBoolean("night", false)
    fun setNight(c: Context, on: Boolean) = p(c).edit().putBoolean("night", on).apply()

    fun keepOn(c: Context) = p(c).getBoolean("keep_on", false)
    fun setKeepOn(c: Context, on: Boolean) = p(c).edit().putBoolean("keep_on", on).apply()

    /** 0 = follow the device, 1 = portrait, 2 = landscape */
    fun orientation(c: Context) = p(c).getInt("orientation", 0)
    fun setOrientation(c: Context, mode: Int) = p(c).edit().putInt("orientation", mode).apply()

    fun orientationLabel(c: Context, mode: Int): String = c.getString(
        when (mode) { 1 -> R.string.viewer_rot_portrait; 2 -> R.string.viewer_rot_landscape; else -> R.string.viewer_rot_auto_short }
    )
}

/** Saved pages of each PDF, as 1-based page numbers. */
object ViewerBookmarks {
    private fun p(c: Context) = c.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun get(c: Context, key: String): List<Int> =
        (p(c).getString(key, "") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }.distinct().sorted()

    private fun put(c: Context, key: String, pages: List<Int>) {
        val e = p(c).edit()
        if (pages.isEmpty()) e.remove(key) else e.putString(key, pages.sorted().joinToString(","))
        e.apply()
    }

    /** Adds the page, or removes it when it is already saved. Returns true when it was added. */
    fun toggle(c: Context, key: String, page: Int): Boolean {
        val now = get(c, key)
        return if (page in now) { put(c, key, now - page); false } else { put(c, key, now + page); true }
    }

    fun remove(c: Context, key: String, page: Int) = put(c, key, get(c, key) - page)
}

internal fun Context.findHostActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Applies "keep screen on" and the orientation lock while the viewer is open, and undoes both on leaving. */
@Composable
internal fun ViewerWindowEffects(keepOn: Boolean, orientation: Int) {
    val activity = LocalContextActivity()
    DisposableEffect(keepOn) {
        if (keepOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(orientation) {
        activity?.requestedOrientation = when (orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

@Composable
private fun LocalContextActivity(): Activity? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) { context.findHostActivity() }
}

// ---------------------------------------------------------------- the three-dot menu

@Composable
private fun MenuRow(
    text: String, icon: ImageVector, enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = trailing,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun Tick(on: Boolean) {
    if (on) Icon(AppIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
}

/** Everything the viewer's three-dot menu does, in four groups. */
@Composable
internal fun ViewerMoreMenu(
    expanded: Boolean, onDismiss: () -> Unit,
    ready: Boolean,
    night: Boolean, keepOn: Boolean, orientationLabel: String,
    bookmarked: Boolean, bookmarkCount: Int,
    canZoomIn: Boolean, canZoomOut: Boolean,
    onGoTo: () -> Unit, onSearch: () -> Unit, onBookmark: () -> Unit, onBookmarks: () -> Unit,
    onNight: () -> Unit, onKeepOn: () -> Unit, onRotation: () -> Unit,
    onZoomIn: () -> Unit, onZoomOut: () -> Unit, onFit: () -> Unit,
    onPrint: () -> Unit, onEdit: () -> Unit, onOpenWith: () -> Unit, onSave: () -> Unit, onInfo: () -> Unit
) {
    fun act(f: () -> Unit): () -> Unit = { onDismiss(); f() }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuRow(stringResource(R.string.viewer_go_to_page), AppIcons.GoToPage, ready, onClick = act(onGoTo))
        MenuRow(stringResource(R.string.viewer_search), AppIcons.Search, ready, onClick = act(onSearch))
        MenuRow(
            stringResource(if (bookmarked) R.string.viewer_bookmark_remove else R.string.viewer_bookmark_add),
            if (bookmarked) AppIcons.Bookmark else AppIcons.BookmarkBorder, ready, onClick = act(onBookmark)
        )
        if (bookmarkCount > 0) MenuRow(stringResource(R.string.viewer_bookmarks_count, bookmarkCount), AppIcons.Bookmark, onClick = act(onBookmarks))
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        MenuRow(stringResource(R.string.viewer_night_mode), AppIcons.DarkMode, ready, { Tick(night) }, act(onNight))
        MenuRow(stringResource(R.string.viewer_keep_on), AppIcons.PhoneAndroid, true, { Tick(keepOn) }, act(onKeepOn))
        MenuRow(
            stringResource(R.string.viewer_rotation), AppIcons.ScreenRotation, true,
            { Text(orientationLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            act(onRotation)
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        MenuRow(stringResource(R.string.viewer_zoom_in), AppIcons.ZoomIn, canZoomIn, onClick = act(onZoomIn))
        MenuRow(stringResource(R.string.viewer_zoom_out), AppIcons.ZoomOut, canZoomOut, onClick = act(onZoomOut))
        MenuRow(stringResource(R.string.viewer_fit_width), AppIcons.FitScreen, canZoomOut, onClick = act(onFit))
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        MenuRow(stringResource(R.string.common_print), AppIcons.Print, onClick = act(onPrint))
        MenuRow(stringResource(R.string.viewer_edit), AppIcons.Handyman, ready, onClick = act(onEdit))
        MenuRow(stringResource(R.string.viewer_open_with), AppIcons.OpenInNew, onClick = act(onOpenWith))
        MenuRow(stringResource(R.string.viewer_save_copy), AppIcons.SaveAlt, onClick = act(onSave))
        MenuRow(stringResource(R.string.viewer_info), AppIcons.Info, ready, onClick = act(onInfo))
    }
}

// ---------------------------------------------------------------- search bar

/** Replaces the top bar while searching: field, result counter and previous / next buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerSearchBar(
    query: String, onQuery: (String) -> Unit,
    status: String, busy: Boolean, canStep: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.viewer_search), maxLines = 1, softWrap = false) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext(); focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        navigationIcon = { IconButton(onClick = onClose) { Icon(AppIcons.ArrowBack, stringResource(R.string.tools_search_close)) } },
        actions = {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            if (status.isNotEmpty()) {
                Text(
                    status, Modifier.padding(horizontal = 6.dp), maxLines = 1, softWrap = false,
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPrev, enabled = canStep) { Icon(AppIcons.KeyboardArrowUp, stringResource(R.string.viewer_prev_result)) }
            IconButton(onClick = onNext, enabled = canStep) { Icon(AppIcons.KeyboardArrowDown, stringResource(R.string.viewer_next_result)) }
        },
        windowInsets = windowInsets,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    )
}

// ---------------------------------------------------------------- dialogs

@Composable
internal fun GoToPageDialog(pageCount: Int, current: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val n = text.toIntOrNull()
    val valid = n != null && n in 1..pageCount
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        modifier = Modifier.dialogEnter(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_go_to_page)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(6) },
                singleLine = true,
                placeholder = { Text("$current", maxLines = 1, softWrap = false) },
                supportingText = { Text(stringResource(R.string.viewer_go_hint, pageCount), maxLines = 1, softWrap = false) },
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onGo(n!!) }),
                modifier = Modifier.focusRequester(focus)
            )
        },
        confirmButton = { TextButton(onClick = { onGo(n!!) }, enabled = valid) { Text(stringResource(R.string.common_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
internal fun BookmarksDialog(pages: List<Int>, onDismiss: () -> Unit, onOpen: (Int) -> Unit, onRemove: (Int) -> Unit) {
    AlertDialog(
        modifier = Modifier.dialogEnter(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_bookmarks)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                pages.forEach { p ->
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.viewer_page_n, p), maxLines = 1, softWrap = false) },
                        leadingContent = { Icon(AppIcons.Bookmark, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            IconButton(onClick = { onRemove(p) }) { Icon(AppIcons.DeleteOutline, stringResource(R.string.viewer_bookmark_remove)) }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpen(p) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
    )
}

@Composable
internal fun RotationDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        modifier = Modifier.dialogEnter(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_rotation)) },
        text = {
            Column {
                listOf(0 to stringResource(R.string.viewer_rot_auto), 1 to stringResource(R.string.viewer_rot_portrait), 2 to stringResource(R.string.viewer_rot_landscape)).forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(mode) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == mode, onClick = { onPick(mode) })
                        Text(label, maxLines = 1, softWrap = false)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/** Facts about the open PDF, shown as label and value pairs. */
class DocInfo(val rows: List<Pair<String, String>>)

@Composable
internal fun DocInfoDialog(info: DocInfo?, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.dialogEnter(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_info)) },
        text = {
            if (info == null) {
                Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    info.rows.forEach { (label, value) ->
                        Column {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FitText(value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
    )
}

private fun paperName(wMm: Float, hMm: Float): String? {
    val a = minOf(wMm, hMm)
    val b = maxOf(wMm, hMm)
    val sizes = listOf(
        "A3" to (297f to 420f), "A4" to (210f to 297f), "A5" to (148f to 210f), "A6" to (105f to 148f),
        "Letter" to (215.9f to 279.4f), "Legal" to (215.9f to 355.6f)
    )
    return sizes.firstOrNull { abs(it.second.first - a) < 3f && abs(it.second.second - b) < 3f }?.first
}

private fun lastModifiedMs(context: Context, uri: Uri): Long? {
    if (uri.scheme == "file") return uri.path?.let { File(it).lastModified() }?.takeIf { it > 0L }
    runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) * 1000L
        }
    }
    runCatching {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
    }
    return null
}

/** Reads the name, size, page count, paper size and date of a PDF. Runs off the main thread. */
suspend fun loadDocInfo(context: Context, uri: Uri, name: String): DocInfo = withContext(Dispatchers.IO) {
    val rows = ArrayList<Pair<String, String>>()
    rows.add(context.getString(R.string.viewer_info_name) to name)
    val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
    if (size != null && size >= 0) rows.add(context.getString(R.string.viewer_info_size) to Formatter.formatFileSize(context, size))
    runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use { r ->
                rows.add(context.getString(R.string.viewer_info_pages) to r.pageCount.toString())
                if (r.pageCount > 0) r.openPage(0).use { p ->
                    val wMm = p.width * 25.4f / 72f
                    val hMm = p.height * 25.4f / 72f
                    val dims = "${wMm.roundToInt()} x ${hMm.roundToInt()} mm"
                    rows.add(context.getString(R.string.viewer_info_page_size) to (paperName(wMm, hMm)?.let { "$it ($dims)" } ?: dims))
                }
            }
        }
    }
    lastModifiedMs(context, uri)?.let {
        rows.add(context.getString(R.string.viewer_info_modified) to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)))
    }
    DocInfo(rows)
}

// ---------------------------------------------------------------- bottom quick toolbar

/** Height of the floating bottom toolbar without the navigation bar. The page list keeps this much free at its end. */
internal val ViewerBottomBarHeight = 64.dp

/** Search, all pages, bookmark and tools, one tap away. Floats over the pages like the top bar, so showing or hiding it never moves a page. */
@Composable
internal fun ViewerBottomBar(
    bookmarked: Boolean,
    onSearch: () -> Unit,
    onPages: () -> Unit,
    onBookmark: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            Modifier.navigationBarsPadding().height(ViewerBottomBarHeight),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarAction(AppIcons.Search, stringResource(R.string.viewer_search_short), onSearch, Modifier.weight(1f))
            BarAction(AppIcons.GridView, stringResource(R.string.viewer_all_pages), onPages, Modifier.weight(1f))
            BarAction(
                if (bookmarked) AppIcons.Bookmark else AppIcons.BookmarkBorder,
                stringResource(R.string.viewer_bookmarks), onBookmark, Modifier.weight(1f),
                tint = if (bookmarked) MaterialTheme.colorScheme.primary else null
            )
            BarAction(AppIcons.Handyman, stringResource(R.string.viewer_edit_short), onEdit, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BarAction(
    icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier, tint: Color? = null
) {
    val c = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.fillMaxHeight().clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = c, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
