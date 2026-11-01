package com.pdfmanager.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.pdfmanager.app.ui.tools.ToolCatalog
import com.pdfmanager.app.ui.tools.ToolCategory
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pdfmanager.app.LibraryViewModel
import com.pdfmanager.app.R
import com.pdfmanager.app.data.FolderEntity
import com.pdfmanager.app.data.PdfEntity
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes

// Pieces shared by the Home and Files screens: storage access, the file dialogs and the permission screen.
// Both screens use them with the same LibraryViewModel, so the file operations exist only once.

internal fun hasStoragePermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

/** Whether the app may read the PDFs, and how to ask for that. */
internal class StorageAccess(val granted: Boolean, val request: () -> Unit)

/** Tracks the storage permission, rescans the PDFs when it is granted or the screen resumes. */
@Composable
internal fun rememberStorageAccess(vm: LibraryViewModel): StorageAccess {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasStoragePermission(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasStoragePermission(context)
        if (granted) vm.rescan()
    }
    // Also runs when returning from a tool screen, so new files show up
    LaunchedEffect(granted) { if (granted) vm.rescan() }

    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it; if (it) vm.rescan(force = true) }

    return StorageAccess(granted) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } else legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

/** Which file dialog is open, and for which files. One instance per screen. */
internal class FileActionState {
    var renameTarget by mutableStateOf<PdfEntity?>(null)
    var moveTargets by mutableStateOf<List<String>>(emptyList())
    var deleteTargets by mutableStateOf<List<PdfEntity>>(emptyList())
    var toolTargets by mutableStateOf<List<PdfEntity>>(emptyList())
}

@Composable
internal fun rememberFileActionState() = remember { FileActionState() }

/**
 * Move, delete, rename and "use in tool" dialogs for the files in [state]. The work is done by the shared
 * [LibraryViewModel]. [onHandled] runs after an action was chosen (the Files screen clears its selection).
 */
@Composable
internal fun FileActionDialogs(
    state: FileActionState,
    vm: LibraryViewModel,
    folders: List<FolderEntity>,
    onNavigate: (String) -> Unit,
    onHandled: () -> Unit = {}
) {
    val res = LocalContext.current.resources
    if (state.moveTargets.isNotEmpty()) {
        AlertDialog(
            modifier = Modifier.dialogEnter(),
            onDismissRequest = { state.moveTargets = emptyList() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(res.getQuantityString(R.plurals.files_move_title, state.moveTargets.size, state.moveTargets.size)) },
            text = {
                LazyColumn {
                    item {
                        FolderChoice(stringResource(R.string.files_unfiled), MaterialTheme.colorScheme.onSurfaceVariant) {
                            vm.move(state.moveTargets, null, null); state.moveTargets = emptyList(); onHandled()
                        }
                    }
                    items(folders, key = { it.id }) { f ->
                        FolderChoice(f.name, Accent.Indigo) {
                            vm.move(state.moveTargets, f.id, f.name); state.moveTargets = emptyList(); onHandled()
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { state.moveTargets = emptyList() }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    val haptics = rememberAppHaptics()
    if (state.deleteTargets.isNotEmpty()) {
        AlertDialog(
            modifier = Modifier.dialogEnter(),
            onDismissRequest = { state.deleteTargets = emptyList() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(res.getQuantityString(R.plurals.files_delete_title, state.deleteTargets.size, state.deleteTargets.size)) },
            text = {
                Text(
                    if (state.deleteTargets.size == 1) stringResource(R.string.files_delete_one_msg, state.deleteTargets[0].name)
                    else stringResource(R.string.files_delete_many_msg)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.confirm()
                    vm.delete(state.deleteTargets.map { it.uri }); state.deleteTargets = emptyList(); onHandled()
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { state.deleteTargets = emptyList() }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    state.renameTarget?.let { pdf ->
        var name by remember(pdf.uri) { mutableStateOf(pdf.name.removeSuffix(".pdf").removeSuffix(".PDF")) }
        AlertDialog(
            modifier = Modifier.dialogEnter(),
            onDismissRequest = { state.renameTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.common_rename)) },
            text = {
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.files_file_name)) },
                    suffix = { Text(".pdf") },
                    keyboardOptions = KeyboardOptions.Default,
                    shape = RoundedCornerShape(14.dp)
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.rename(pdf.uri, name); state.renameTarget = null }) {
                    Text(stringResource(R.string.common_rename))
                }
            },
            dismissButton = { TextButton(onClick = { state.renameTarget = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (state.toolTargets.isNotEmpty()) {
        SendToToolDialog(
            files = state.toolTargets,
            onDismiss = { state.toolTargets = emptyList() },
            onPick = { route ->
                ToolInput.set(state.toolTargets.map { Picked(Uri.parse(it.uri), it.name) })
                state.toolTargets = emptyList()
                onHandled()
                onNavigate(route)
            }
        )
    }
}

@Composable
private fun FolderChoice(name: String, tint: Color, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name) },
        leadingContent = { Icon(AppIcons.Folder, null, tint = tint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
    )
}

private data class SendTool(@StringRes val titleRes: Int, val icon: ImageVector, val route: String, val accent: Color)

private val singleFileTools = listOf(
    SendTool(R.string.tool_compress, AppIcons.Compress, "compress", Accent.Orange),
    SendTool(R.string.tool_split, AppIcons.ContentCut, "split", Accent.Indigo),
    SendTool(R.string.tool_extract, AppIcons.Checklist, "extract", Accent.Indigo),
    SendTool(R.string.tool_reorder, AppIcons.SwapVert, "reorder", Accent.Indigo),
    SendTool(R.string.tool_blank, AppIcons.NoteAdd, "blank", Accent.Indigo),
    SendTool(R.string.tool_watermark, AppIcons.Watermark, "watermark", Accent.Violet),
    SendTool(R.string.tool_pagenum, AppIcons.Numbers, "pagenum", Accent.Violet),
    SendTool(R.string.tool_sign, AppIcons.Edit, "sign", Accent.Violet),
    SendTool(R.string.tool_notes, AppIcons.StickyNote, "notes", Accent.Violet),
    SendTool(R.string.tool_resize, AppIcons.AspectRatio, "resize", Accent.Violet),
    SendTool(R.string.tool_poster, AppIcons.Poster, "poster", Accent.Violet),
    SendTool(R.string.tool_croprotate, AppIcons.CropRotate, "croprotate", Accent.Indigo),
    SendTool(R.string.tool_lock, AppIcons.Lock, "lock", Accent.Rose),
    SendTool(R.string.tool_repair, AppIcons.Handyman, "repair", Accent.Rose),
    SendTool(R.string.tool_metadata, AppIcons.DriveFileRenameOutline, "metadata", Accent.Rose),
    SendTool(R.string.tool_nup, AppIcons.GridView, "nup", Accent.Indigo),
    SendTool(R.string.tool_booklet, AppIcons.AutoStories, "booklet", Accent.Indigo),
    SendTool(R.string.tool_pdf2img, AppIcons.PhotoLibrary, "pdf2img", Accent.Teal),
    SendTool(R.string.tool_pdf2text, AppIcons.Description, "pdf2text", Accent.Teal),
    SendTool(R.string.tool_extractimg, AppIcons.ExtractImages, "extractimg", Accent.Teal),
)

/** Lets the user pick a tool for the chosen files; the tool opens with the files already loaded. */
@Composable
internal fun SendToToolDialog(files: List<PdfEntity>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val many = files.size >= 2
    val options = if (many) listOf(SendTool(R.string.tool_merge, AppIcons.MergeType, "merge", Accent.Indigo))
    else singleFileTools
    // Same tools, grouped under the category names used on the Tools screen
    val groups = ToolCategory.entries.mapNotNull { c ->
        val inGroup = options.filter { o -> ToolCatalog.all.firstOrNull { it.route == o.route }?.category == c }
        if (inGroup.isEmpty()) null else c to inGroup
    }
    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.94f).dialogEnter(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (many) stringResource(R.string.files_send_title_many, files.size) else stringResource(R.string.files_send_title_one)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                groups.forEachIndexed { i, (cat, tools) ->
                    Text(
                        stringResource(cat.titleRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = if (i == 0) 0.dp else 14.dp, bottom = 4.dp)
                    )
                    tools.forEach { t ->
                        ListItem(
                            headlineContent = { FitText(stringResource(t.titleRes)) },
                            leadingContent = { IconBadge(t.icon, t.accent, size = 36.dp) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(t.route) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/** First screen before storage access is granted: what the app does, then one clear button. */
@Composable
internal fun PermissionHero(modifier: Modifier, onGrant: () -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(104.dp).clip(RoundedCornerShape(percent = 22)))
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        listOf(
            Triple(AppIcons.Search, stringResource(R.string.permission_point_find), Accent.Indigo),
            Triple(AppIcons.Handyman, stringResource(R.string.permission_point_tools), Accent.Orange),
            Triple(AppIcons.Lock, stringResource(R.string.permission_point_private), Accent.Green)
        ).forEach { (icon, text, tint) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, tint, size = 42.dp)
                Spacer(Modifier.width(14.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(28.dp))
        PrimaryButton(stringResource(R.string.permission_allow), onClick = onGrant)
    }
}

