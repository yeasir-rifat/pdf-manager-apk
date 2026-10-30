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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pdfmanager.app.LibraryViewModel
import com.pdfmanager.app.R
import com.pdfmanager.app.data.FolderEntity
import com.pdfmanager.app.data.PdfEntity

// Pieces shared by the Home and Files screens: storage access, the file dialogs and the permission screen.
// Both screens use them with the same LibraryViewModel, so the file operations exist only once.

internal fun hasStoragePermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

internal fun filesLabel(n: Int) = if (n == 1) "1 file" else "$n files"

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
    if (state.moveTargets.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { state.moveTargets = emptyList() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Move ${filesLabel(state.moveTargets.size)}") },
            text = {
                LazyColumn {
                    item {
                        FolderChoice("Unfiled", MaterialTheme.colorScheme.onSurfaceVariant) {
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
            dismissButton = { TextButton(onClick = { state.moveTargets = emptyList() }) { Text("Cancel") } }
        )
    }

    if (state.deleteTargets.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { state.deleteTargets = emptyList() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Delete ${filesLabel(state.deleteTargets.size)}?") },
            text = {
                Text(
                    if (state.deleteTargets.size == 1) "\"${state.deleteTargets[0].name}\" will be permanently deleted from your device."
                    else "These files will be permanently deleted from your device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(state.deleteTargets.map { it.uri }); state.deleteTargets = emptyList(); onHandled()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { state.deleteTargets = emptyList() }) { Text("Cancel") } }
        )
    }

    state.renameTarget?.let { pdf ->
        var name by remember(pdf.uri) { mutableStateOf(pdf.name.removeSuffix(".pdf").removeSuffix(".PDF")) }
        AlertDialog(
            onDismissRequest = { state.renameTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text("File name") },
                    suffix = { Text(".pdf") },
                    keyboardOptions = KeyboardOptions.Default,
                    shape = RoundedCornerShape(14.dp)
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.rename(pdf.uri, name); state.renameTarget = null }) {
                    Text("Rename")
                }
            },
            dismissButton = { TextButton(onClick = { state.renameTarget = null }) { Text("Cancel") } }
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

private data class SendTool(val title: String, val icon: ImageVector, val route: String, val accent: Color)

private val singleFileTools = listOf(
    SendTool("Compress PDF", AppIcons.Compress, "compress", Accent.Orange),
    SendTool("Split PDF", AppIcons.ContentCut, "split", Accent.Indigo),
    SendTool("Extract / Delete pages", AppIcons.Checklist, "extract", Accent.Indigo),
    SendTool("Reorder pages", AppIcons.SwapVert, "reorder", Accent.Indigo),
    SendTool("Add blank page", AppIcons.NoteAdd, "blank", Accent.Indigo),
    SendTool("Watermark", AppIcons.Watermark, "watermark", Accent.Violet),
    SendTool("Page numbers", AppIcons.Numbers, "pagenum", Accent.Violet),
    SendTool("Sign PDF", AppIcons.Edit, "sign", Accent.Violet),
    SendTool("Highlight / Notes", AppIcons.StickyNote, "notes", Accent.Violet),
    SendTool("Resize paper", AppIcons.AspectRatio, "resize", Accent.Violet),
    SendTool("Poster print", AppIcons.Poster, "poster", Accent.Violet),
    SendTool("Crop & Rotate", AppIcons.CropRotate, "croprotate", Accent.Indigo),
    SendTool("Lock / Unlock", AppIcons.Lock, "lock", Accent.Rose),
    SendTool("Pages per sheet", AppIcons.GridView, "nup", Accent.Indigo),
    SendTool("Booklet", AppIcons.AutoStories, "booklet", Accent.Indigo),
    SendTool("PDF to Image", AppIcons.PhotoLibrary, "pdf2img", Accent.Teal),
    SendTool("PDF to Text", AppIcons.Description, "pdf2text", Accent.Teal),
    SendTool("Extract images", AppIcons.ExtractImages, "extractimg", Accent.Teal),
)

/** Lets the user pick a tool for the chosen files; the tool opens with the files already loaded. */
@Composable
private fun SendToToolDialog(files: List<PdfEntity>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val many = files.size >= 2
    val options = if (many) listOf(SendTool("Merge PDFs", AppIcons.MergeType, "merge", Accent.Indigo))
    else singleFileTools
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (many) "Use ${files.size} files in" else "Use in tool") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { t ->
                    ListItem(
                        headlineContent = { FitText(t.title) },
                        leadingContent = { IconBadge(t.icon, t.accent, size = 36.dp) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(t.route) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
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
        Text("Your PDFs, all in one place", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        listOf(
            Triple(AppIcons.Search, "Finds every PDF automatically", Accent.Indigo),
            Triple(AppIcons.Handyman, "Merge, compress, scan and more", Accent.Orange),
            Triple(AppIcons.Lock, "Private: your files stay on this device", Accent.Green)
        ).forEach { (icon, text, tint) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, tint, size = 42.dp)
                Spacer(Modifier.width(14.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Allow file access", onClick = onGrant)
    }
}

