package com.pdfmanager.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import com.pdfmanager.app.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfmanager.app.LibraryViewModel
import com.pdfmanager.app.SortMode
import com.pdfmanager.app.data.PdfEntity
import com.pdfmanager.app.data.SearchHelpers
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    onOpen: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onPrint: (PdfEntity) -> Unit = {},
    /** The shared library state. It is passed in by the caller; this screen never creates its own. */
    vm: LibraryViewModel
) {
    val context = LocalContext.current
    val haptics = rememberAppHaptics()
    val access = rememberStorageAccess(vm)
    val granted = access.granted

    val folders by vm.folders.collectAsStateWithLifecycle()
    val pdfs by vm.pdfs.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()
    val indexing by vm.indexProgress.collectAsStateWithLifecycle()
    // The text around the found words, only while the hits belong to what is typed now
    val snippets = remember(hits, query) {
        if (query.isNotBlank() && hits.terms == SearchHelpers.textTerms(query)) {
            hits.byUri.mapValues { (_, h) -> SearchHelpers.snippet(h.raw, h.pos) }
        } else emptyMap()
    }
    val snippetColor = MaterialTheme.colorScheme.primary

    val selected = remember { mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    val selectedPdfs = remember(pdfs, selected.size) {
        val chosen = selected.toHashSet()
        pdfs.filter { it.uri in chosen }
    }

    val uiPrefs = remember { context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    var gridMode by remember { mutableStateOf(uiPrefs.getBoolean("grid", false)) }

    val actions = rememberFileActionState()

    // Home's search button asks for the search field to be focused; handle that request once
    val searchFocus = remember { FocusRequester() }
    val focusSearch by vm.searchFocusRequested.collectAsStateWithLifecycle()
    LaunchedEffect(focusSearch, granted) {
        if (focusSearch && granted) {
            withFrameNanos { }
            runCatching { searchFocus.requestFocus() }
            vm.searchFocusRequested.value = false
        }
    }
    var showNewFolder by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf<Long?>(null) }
    var pulling by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(scanning) { if (!scanning) pulling = false }
    LaunchedEffect(filter, query) { selected.clear() }
    BackHandler(enabled = selecting) { selected.clear() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(stringResource(R.string.files_selected_count, selected.size), style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = { selected.clear() }) { Icon(AppIcons.Close, stringResource(R.string.files_cancel_selection)) }
                    },
                    actions = {
                        val allFav = selectedPdfs.isNotEmpty() && selectedPdfs.all { it.favorite }
                        IconButton(onClick = {
                            vm.setFavorite(selected.toList(), !allFav, announce = true); selected.clear()
                        }) {
                            Icon(
                                if (allFav) AppIcons.Star else AppIcons.StarBorder,
                                stringResource(if (allFav) R.string.home_fav_remove else R.string.home_fav_add),
                                tint = if (allFav) Accent.Amber else LocalContentColor.current
                            )
                        }
                        IconButton(onClick = { Share.pdfs(context, selected.toList()) }) {
                            Icon(AppIcons.Share, stringResource(R.string.common_share))
                        }
                        // Print works on one PDF at a time in the first version
                        IconButton(
                            enabled = selectedPdfs.size == 1,
                            onClick = { selectedPdfs.firstOrNull()?.let { pdf -> onPrint(pdf); selected.clear() } }
                        ) {
                            Icon(AppIcons.Print, stringResource(R.string.common_print))
                        }
                        IconButton(onClick = { actions.deleteTargets = selectedPdfs }) {
                            Icon(AppIcons.DeleteOutline, stringResource(R.string.common_delete))
                        }
                        var more by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { more = true }) { Icon(AppIcons.MoreVert, stringResource(R.string.common_more)) }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.files_use_in_tool)) },
                                    leadingIcon = { Icon(AppIcons.Handyman, null) },
                                    onClick = {
                                        more = false
                                        // Keep the order in which the files were selected
                                        actions.toolTargets = selected.mapNotNull { u -> pdfs.find { it.uri == u } }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_select_all)) },
                                    leadingIcon = { Icon(AppIcons.SelectAll, null) },
                                    onClick = { more = false; selected.clear(); selected.addAll(pdfs.map { it.uri }) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.files_move_to_folder)) },
                                    leadingIcon = { Icon(AppIcons.Folder, null) },
                                    onClick = { more = false; actions.moveTargets = selected.toList() }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                )
            } else if (!granted) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.app_logo), null, Modifier.size(32.dp).clip(RoundedCornerShape(percent = 22)))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            } else {
                SearchPill(
                    query = query,
                    onQuery = { vm.query.value = it },
                    gridMode = gridMode,
                    onToggleGrid = {
                        gridMode = !gridMode
                        uiPrefs.edit().putBoolean("grid", gridMode).apply()
                    },
                    sort = sort,
                    onSort = { vm.sort.value = it },
                    focusRequester = searchFocus
                )
            }
        },
        floatingActionButton = {
            if (granted && !selecting) {
                FloatingActionButton(
                    onClick = { showNew = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = CircleShape
                ) {
                    Icon(AppIcons.Add, contentDescription = stringResource(R.string.common_new))
                }
            }
        }
    ) { padding ->
        if (!granted) {
            PermissionHero(Modifier.padding(padding), onGrant = access.request)
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            // All, Starred, Recent and one Folders chip. The folder list itself is in a sheet, so many folders never crowd this row.
            val f = filter
            val inFolders = f != null && f >= 0L
            val folderChipLabel = when {
                f == 0L -> stringResource(R.string.files_unfiled)
                inFolders -> folders.firstOrNull { it.id == f }?.name ?: stringResource(R.string.files_folders)
                else -> stringResource(R.string.files_folders)
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(f == null, { vm.filter.value = null }, { Text(stringResource(R.string.files_all)) },
                        shape = RoundedCornerShape(12.dp))
                }
                item {
                    FilterChip(
                        f == -1L, { vm.filter.value = -1L }, { Text(stringResource(R.string.files_starred)) },
                        leadingIcon = { Icon(AppIcons.Star, null, Modifier.size(18.dp)) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                item {
                    FilterChip(
                        f == -2L, { vm.filter.value = -2L }, { Text(stringResource(R.string.files_recent)) },
                        leadingIcon = { Icon(AppIcons.History, null, Modifier.size(18.dp)) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                item {
                    FilterChip(
                        selected = inFolders, onClick = { showFolders = true },
                        label = { Text(folderChipLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(AppIcons.Folder, null, Modifier.size(18.dp)) },
                        trailingIcon = { Icon(AppIcons.KeyboardArrowDown, null, Modifier.size(18.dp)) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = pulling,
                onRefresh = { pulling = true; vm.rescan(force = true) },
                modifier = Modifier.fillMaxSize()
            ) {
                if (pdfs.isEmpty() && !scanning) {
                    val searching = query.isNotBlank()
                    val noFavorites = filter == -1L && !searching
                    val noRecent = filter == -2L && !searching
                    EmptyState(
                        icon = when {
                            noFavorites -> AppIcons.StarBorder
                            noRecent -> AppIcons.History
                            else -> AppIcons.PictureAsPdf
                        },
                        title = when {
                            searching -> stringResource(R.string.files_no_matches)
                            noFavorites -> stringResource(R.string.files_no_favorites)
                            noRecent -> stringResource(R.string.files_no_recent)
                            else -> stringResource(R.string.files_no_pdfs)
                        },
                        message = indexing?.takeIf { searching }?.let { stringResource(R.string.files_indexing, it.done, it.total) } ?: "",
                        action = if (searching || noFavorites || noRecent) null else ({
                            PrimaryButton(stringResource(R.string.files_scan_a_document), icon = AppIcons.DocumentScanner, onClick = { onNavigate("scan") })
                        })
                    )
                } else {
                    LazyVerticalGrid(
                        GridCells.Fixed(if (gridMode) 2 else 1),
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (filter == -2L && pdfs.isNotEmpty() && !selecting) {
                            item(key = "clear-recent", span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                    TextButton(onClick = { vm.clearRecent() }) { Text(stringResource(R.string.files_clear_recent)) }
                                }
                            }
                        }
                        if (!selecting && pdfs.isNotEmpty()) {
                            item(key = "files-label", span = { GridItemSpan(maxLineSpan) }) {
                                val sectionName = when {
                                    query.isNotBlank() -> stringResource(R.string.files_label_results)
                                    filter == -1L -> stringResource(R.string.files_label_favorites)
                                    filter == -2L -> stringResource(R.string.files_recent)
                                    filter == 0L -> stringResource(R.string.files_unfiled)
                                    filter != null -> folders.firstOrNull { it.id == filter }?.name ?: stringResource(R.string.files_label_folder)
                                    else -> stringResource(R.string.files_label_all)
                                }
                                SectionLabel(
                                    stringResource(R.string.files_section_count, sectionName, pdfs.size),
                                    Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        if (!selecting && query.isNotBlank() && indexing != null && pdfs.isNotEmpty()) {
                            item(key = "index-status", span = { GridItemSpan(maxLineSpan) }) {
                                val p = indexing ?: return@item
                                FitText(
                                    stringResource(R.string.files_indexing_status, p.done, p.total),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(pdfs, key = { it.uri }) { pdf ->
                            val isSelected = pdf.uri in selected
                            val toggle = { if (isSelected) selected.remove(pdf.uri) else selected.add(pdf.uri); Unit }
                            PdfItem(
                                gridMode = gridMode,
                                pdf = pdf,
                                selected = isSelected,
                                selecting = selecting,
                                onClick = { if (selecting) toggle() else onOpen(pdf.uri) },
                                onLongClick = { haptics.confirm(); toggle() },
                                onFavorite = { vm.setFavorite(listOf(pdf.uri), !pdf.favorite) },
                                onShare = { Share.pdfs(context, listOf(pdf.uri)) },
                                onRename = { actions.renameTarget = pdf },
                                onMove = { actions.moveTargets = listOf(pdf.uri) },
                                onUseInTool = { actions.toolTargets = listOf(pdf) },
                                onPrint = { onPrint(pdf) },
                                onDelete = { actions.deleteTargets = listOf(pdf) },
                                snippet = snippets[pdf.uri]?.let { snippetText(it, hits.terms, snippetColor) }
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ dialogs

    if (showNew) {
        ModalBottomSheet(
            onDismissRequest = { showNew = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                listOf(
                    Triple(stringResource(R.string.tool_scan), AppIcons.DocumentScanner, "scan"),
                    Triple(stringResource(R.string.tool_img2pdf), AppIcons.Image, "img2pdf"),
                    Triple(stringResource(R.string.tool_merge), AppIcons.MergeType, "merge"),
                    Triple(stringResource(R.string.files_new_folder), AppIcons.CreateNewFolder, "")
                ).forEach { (label, icon, route) ->
                    ListItem(
                        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            showNew = false
                            if (route.isEmpty()) showNewFolder = true else onNavigate(route)
                        }
                    )
                }
            }
        }
    }

    if (showFolders) {
        ModalBottomSheet(onDismissRequest = { showFolders = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.files_folders),
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.files_unfiled)) },
                    leadingContent = { Icon(AppIcons.Folder, null) },
                    trailingContent = { if (filter == 0L) Icon(AppIcons.Check, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { vm.filter.value = 0L; showFolders = false }
                )
                folders.forEach { folder ->
                    ListItem(
                        headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(AppIcons.Folder, null) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (filter == folder.id) Icon(AppIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
                                // Opens the same "Delete folder?" dialog as before; the files stay on the device
                                IconButton(onClick = { showFolders = false; folderToDelete = folder.id }) {
                                    Icon(AppIcons.DeleteOutline, stringResource(R.string.common_delete))
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { vm.filter.value = folder.id; showFolders = false }
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.files_new_folder)) },
                    leadingContent = { Icon(AppIcons.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { showFolders = false; showNewFolder = true }
                )
            }
        }
    }

    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            modifier = Modifier.dialogEnter(),
            onDismissRequest = { showNewFolder = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.files_new_folder)) },
            text = {
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.files_folder_name)) },
                    shape = RoundedCornerShape(14.dp)
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.addFolder(name); showNewFolder = false }) {
                    Text(stringResource(R.string.common_create))
                }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    FileActionDialogs(actions, vm, folders, onNavigate) { selected.clear() }

    folderToDelete?.let { id ->
        AlertDialog(
            modifier = Modifier.dialogEnter(),
            onDismissRequest = { folderToDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.files_delete_folder_title)) },
            text = { Text(stringResource(R.string.files_delete_folder_msg)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteFolder(id); folderToDelete = null }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

// ---------------------------------------------------------------- pieces

private val dateFormat: DateFormat by lazy { DateFormat.getDateInstance(DateFormat.MEDIUM) }

@Composable
internal fun PdfItem(
    gridMode: Boolean,
    pdf: PdfEntity,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onUseInTool: () -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit,
    snippet: AnnotatedString? = null
) {
    if (gridMode) PdfGridCard(pdf, selected, selecting, onClick, onLongClick, onShare, onRename, onMove, onUseInTool, onPrint, onDelete, onFavorite, snippet)
    else PdfRow(pdf, selected, selecting, onClick, onLongClick, onFavorite, onShare, onRename, onMove, onUseInTool, onPrint, onDelete, snippet)
}

/** The found text with the searched words in bold and in the accent colour. */
private fun snippetText(text: String, terms: List<String>, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (r in SearchHelpers.highlightRanges(text, terms)) {
        addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color), r.first, r.last + 1)
    }
}

@Composable
private fun pdfMeta(pdf: PdfEntity): String {
    val context = LocalContext.current
    return remember(pdf.sizeBytes, pdf.modifiedSec) {
        "${Formatter.formatShortFileSize(context, pdf.sizeBytes)}  •  ${dateFormat.format(Date(pdf.modifiedSec * 1000))}"
    }
}

@Composable
internal fun PdfMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onUseInTool: () -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.files_use_in_tool)) },
            leadingIcon = { Icon(AppIcons.Handyman, null) },
            onClick = { onDismiss(); onUseInTool() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.common_share)) },
            leadingIcon = { Icon(AppIcons.Share, null) },
            onClick = { onDismiss(); onShare() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.common_print)) },
            leadingIcon = { Icon(AppIcons.Print, null) },
            onClick = { onDismiss(); onPrint() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.common_rename)) },
            leadingIcon = { Icon(AppIcons.DriveFileRenameOutline, null) },
            onClick = { onDismiss(); onRename() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.files_move_to_folder)) },
            leadingIcon = { Icon(AppIcons.Folder, null) },
            onClick = { onDismiss(); onMove() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(AppIcons.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfRow(
    pdf: PdfEntity,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onUseInTool: () -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit,
    snippet: AnnotatedString? = null
) {
    val meta = pdfMeta(pdf)
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    AppCard(
        Modifier.fillMaxWidth().pressScale(interaction).clip(RoundedCornerShape(AppDimens.cardRadius))
            .combinedClickable(
                interactionSource = interaction, indication = LocalIndication.current,
                onClick = onClick, onLongClick = onLongClick
            ),
        highlighted = selected
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = if (selecting) 14.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(width = 52.dp, height = 68.dp)) {
                PdfThumb(pdf.uri, Modifier.fillMaxSize(), stamp = "${pdf.modifiedSec}-${pdf.sizeBytes}")
                if (selected) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(AppIcons.CheckCircle, null, tint = Color.White, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(percent = 22)))
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(pdf.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (snippet != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        snippet,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!selecting) {
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (pdf.favorite) AppIcons.Star else AppIcons.StarBorder,
                        contentDescription = stringResource(if (pdf.favorite) R.string.home_fav_remove else R.string.home_fav_add),
                        tint = if (pdf.favorite) Accent.Amber else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                    PdfMenu(menu, { menu = false }, onShare, onRename, onMove, onUseInTool, onPrint, onDelete)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfGridCard(
    pdf: PdfEntity,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onUseInTool: () -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    snippet: AnnotatedString? = null
) {
    val meta = pdfMeta(pdf)
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    AppCard(
        Modifier.fillMaxWidth().pressScale(interaction).clip(RoundedCornerShape(AppDimens.cardRadius))
            .combinedClickable(
                interactionSource = interaction, indication = LocalIndication.current,
                onClick = onClick, onLongClick = onLongClick
            ),
        highlighted = selected
    ) {
        Box {
            PdfThumb(
                pdf.uri, Modifier.fillMaxWidth().aspectRatio(0.80f),
                stamp = "${pdf.modifiedSec}-${pdf.sizeBytes}", widthPx = 360,
                shape = RoundedCornerShape(topStart = AppDimens.cardRadius, topEnd = AppDimens.cardRadius)
            )
            if (selected) {
                Box(
                    Modifier.matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.CheckCircle, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
            if (!selecting) {
                IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                    Icon(
                        if (pdf.favorite) AppIcons.Star else AppIcons.StarBorder,
                        contentDescription = stringResource(if (pdf.favorite) R.string.home_fav_remove else R.string.home_fav_add),
                        tint = if (pdf.favorite) Accent.Amber else Color.Gray,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    pdf.name, style = MaterialTheme.typography.titleSmall,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    meta, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (snippet != null) {
                    Text(snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!selecting) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(AppIcons.MoreVert, contentDescription = stringResource(R.string.common_more))
                    }
                    PdfMenu(menu, { menu = false }, onShare, onRename, onMove, onUseInTool, onPrint, onDelete)
                }
            }
        }
    }
}

@Composable
private fun SortMenuButton(sort: SortMode, onSort: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(AppIcons.Sort, contentDescription = stringResource(R.string.common_sort)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                SortMode.NAME to stringResource(R.string.settings_sort_name),
                SortMode.NEWEST to stringResource(R.string.settings_sort_newest),
                SortMode.LARGEST to stringResource(R.string.settings_sort_largest)
            ).forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = if (sort == mode) ({ Icon(AppIcons.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    onClick = { onSort(mode); open = false }
                )
            }
        }
    }
}

/**
 * Search field of the Files screen with the layout toggle and the sort menu. Theme, wallpaper colors and
 * search-inside-PDFs now live in Settings. [focusRequester] lets Home open the field ready for typing.
 */
@Composable
private fun SearchPill(
    query: String,
    onQuery: (String) -> Unit,
    gridMode: Boolean,
    onToggleGrid: () -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    focusRequester: FocusRequester
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        // Same header as Home and Tools: logo and name, 64dp high. The view and sort actions sit on its right.
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painterResource(R.drawable.app_logo), contentDescription = null,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(percent = 22))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onToggleGrid) {
                Icon(
                    if (gridMode) AppIcons.ViewAgenda else AppIcons.GridView,
                    contentDescription = stringResource(if (gridMode) R.string.tools_view_list else R.string.tools_view_grid)
                )
            }
            SortMenuButton(sort, onSort)
        }
        // Search pill, the same look and height as the one on Home
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth().height(52.dp)
                .clip(CircleShape)
                .background(colors.toolCard)
                .border(AppDimens.hairline, colors.cardBorder, CircleShape)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(AppIcons.Search, contentDescription = null, tint = colors.textSecondary)
            Spacer(Modifier.width(12.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.files_search_hint), style = MaterialTheme.typography.bodyLarge,
                                color = colors.textSecondary, maxLines = 1
                            )
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(AppIcons.Close, stringResource(R.string.tools_search_clear)) }
            }
        }
    }
}
