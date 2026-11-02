package com.pdfmanager.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.pdfmanager.app.data.SaveFolder
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdfmanager.app.LibraryViewModel
import com.pdfmanager.app.R
import com.pdfmanager.app.SortMode
import com.pdfmanager.app.data.ConvertHelpers
import com.pdfmanager.app.data.OfficeConvert
import com.pdfmanager.app.data.BackupData
import com.pdfmanager.app.data.BackupManager
import com.pdfmanager.app.data.DriveException
import com.pdfmanager.app.data.GoogleAccount
import com.pdfmanager.app.data.OfficeHelpers
import androidx.compose.runtime.collectAsState
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings tab. It changes the same stored preferences the rest of the app already uses:
 * theme and wallpaper colors come from MainActivity (the "ui" preferences), default sorting and
 * search-inside-PDFs come from the shared [LibraryViewModel]. Nothing is stored twice.
 *
 * Every topic is always visible: a small icon and title, then one card of rows. Order: Appearance,
 * Files, Saving, Feedback, Google Drive, About.
 */
@Composable
fun SettingsScreen(
    vm: LibraryViewModel,
    themeMode: Int,
    onThemeMode: (Int) -> Unit,
    dynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit,
    accent: Int,
    onAccent: (Int) -> Unit,
    pureBlack: Boolean,
    onPureBlack: (Boolean) -> Unit
) {
    // The message shown when the stored PDF text is deleted comes from the ViewModel, as in the library
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Box(Modifier.fillMaxWidth()) {
        ToolScaffold(stringResource(R.string.settings_title), onBack = null) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                AppearanceSection(themeMode, onThemeMode, dynamicColor, onDynamicColor, accent, onAccent, pureBlack, onPureBlack)
                FilesSection(vm)
                SavingSection()
                FeedbackSection()
                BackupSection(vm)
                DriveSection()
                AboutSection()
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}

@Composable
private fun AppearanceSection(
    themeMode: Int,
    onThemeMode: (Int) -> Unit,
    dynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit,
    accent: Int,
    onAccent: (Int) -> Unit,
    pureBlack: Boolean,
    onPureBlack: (Boolean) -> Unit
) {
    val wallpaperColorsAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    SettingsGroup(AppIcons.Palette, Accent.Violet, stringResource(R.string.settings_appearance)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsLabel(stringResource(R.string.settings_theme))
            Segmented(
                options = listOf(
                    stringResource(R.string.settings_theme_system),
                    stringResource(R.string.settings_theme_light),
                    stringResource(R.string.settings_theme_dark)
                ),
                selected = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK).indexOf(themeMode).coerceAtLeast(0),
                onSelect = { onThemeMode(listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)[it]) }
            )
        }
        // Only the dark theme can be pure black, so the switch is gray and locked in the light theme
        val darkNow = when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            else -> androidx.compose.foundation.isSystemInDarkTheme()
        }
        RowDivider()
        SwitchRow(
            title = stringResource(R.string.settings_pure_black),
            checked = pureBlack,
            onChange = onPureBlack,
            enabled = darkNow
        )
        // Colors: wallpaper colors and the accent picker sit together because the first overrides the second
        if (wallpaperColorsAvailable) {
            RowDivider()
            SwitchRow(
                title = stringResource(R.string.settings_wallpaper_colors),
                checked = dynamicColor,
                onChange = onDynamicColor
            )
        }
        RowDivider()
        // Wallpaper colors win over the accent, so the picker is shown dimmed and cannot be changed
        val accentLocked = dynamicColor && wallpaperColorsAvailable
        Box(Modifier.padding(horizontal = 16.dp)) {
            AccentPicker(selected = accent, enabled = !accentLocked, onSelect = onAccent)
        }
    }
}

/** How the library lists and searches your files. */
@Composable
private fun FilesSection(vm: LibraryViewModel) {
    val sort by vm.sort.collectAsStateWithLifecycle()
    val searchInside by vm.searchInside.collectAsStateWithLifecycle()
    var pickSort by remember { mutableStateOf(false) }
    val sortOptions = listOf(
        SortMode.NAME to R.string.settings_sort_name,
        SortMode.NEWEST to R.string.settings_sort_newest,
        SortMode.LARGEST to R.string.settings_sort_largest
    )
    SettingsGroup(AppIcons.Folder, Accent.Orange, stringResource(R.string.settings_files)) {
        ValueRow(
            title = stringResource(R.string.settings_sort),
            value = stringResource(sortOptions.first { it.first == sort }.second),
            onClick = { pickSort = true }
        )
        RowDivider()
        SwitchRow(
            title = stringResource(R.string.settings_search_inside),
            checked = searchInside,
            onChange = { vm.setSearchInside(it) }
        )
    }
    if (pickSort) {
        AlertDialog(
            modifier = Modifier.dialogEnter(),
            onDismissRequest = { pickSort = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.settings_sort)) },
            text = {
                Column {
                    sortOptions.forEach { (mode, label) ->
                        ChoiceRow(stringResource(label), sort == mode) { vm.sort.value = mode; pickSort = false }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickSort = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

/** What happens when a tool saves a new file, and where the file goes. */
@Composable
private fun SavingSection() {
    val ctx = LocalContext.current
    var askName by remember { mutableStateOf(NamePref.enabled(ctx)) }
    var folder by remember { mutableStateOf(SaveFolder.label(ctx)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            SaveFolder.set(ctx, tree)
            folder = SaveFolder.label(ctx)
        }
    }
    SettingsGroup(AppIcons.SaveAlt, Accent.Teal, stringResource(R.string.settings_saving)) {
        SwitchRow(
            title = stringResource(R.string.settings_ask_name),
            checked = askName,
            onChange = { askName = it; NamePref.set(ctx, it) }
        )
        RowDivider()
        ValueRow(
            title = stringResource(R.string.settings_save_location),
            value = folder ?: stringResource(R.string.settings_save_default),
            onClick = { pick.launch(null) }
        )
        if (folder != null) {
            RowDivider()
            ValueRow(
                title = stringResource(R.string.settings_save_reset),
                value = null,
                titleColor = MaterialTheme.colorScheme.primary,
                showChevron = false,
                onClick = { SaveFolder.clear(ctx); folder = null }
            )
        }
    }
}

@Composable
private fun FeedbackSection() {
    val ctx = LocalContext.current
    var vibration by remember { mutableStateOf(HapticPrefs.enabled(ctx)) }
    SettingsGroup(AppIcons.Vibration, Accent.Rose, stringResource(R.string.settings_feedback)) {
        SwitchRow(
            title = stringResource(R.string.settings_vibration),
            checked = vibration,
            onChange = { vibration = it; HapticPrefs.set(ctx, it) }
        )
    }
}

private enum class BackupJob { BACKUP, RESTORE_FETCH, DELETE }

/**
 * Backup and restore of the reading data (bookmarks, last read pages, favorites) and the settings, in the
 * hidden app data folder of the person's own Google Drive. Needs the Google account from the Home button.
 */
@Composable
private fun BackupSection(vm: LibraryViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by GoogleAccount.profile(ctx).collectAsState()
    val startSignIn = rememberGoogleSignIn()
    var lastAt by remember { mutableStateOf(BackupManager.lastBackupAt(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<BackupJob?>(null) }
    var found by remember { mutableStateOf<BackupData?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(BackupManager.autoEnabled(ctx)) }
    val email = profile?.email

    val authorize = rememberDriveAuthorizer(
        onToken = { token ->
            val which = job
            scope.launch {
                var text: String? = null
                try {
                    when (which) {
                        BackupJob.BACKUP -> {
                            withContext(Dispatchers.IO) {
                                val data = BackupManager.collect(ctx)
                                BackupManager.drive(ctx, token, email).save(data)
                                BackupManager.setLastBackupAt(ctx, data.createdAtMillis)
                            }
                            lastAt = BackupManager.lastBackupAt(ctx)
                            text = ctx.getString(R.string.settings_backup_done)
                        }
                        BackupJob.RESTORE_FETCH -> {
                            val data = withContext(Dispatchers.IO) { BackupManager.drive(ctx, token, email).latest() }
                            if (data == null) text = ctx.getString(R.string.settings_backup_none) else found = data
                        }
                        BackupJob.DELETE -> {
                            val had = withContext(Dispatchers.IO) { BackupManager.drive(ctx, token, email).deleteAll() }
                            text = ctx.getString(if (had) R.string.settings_backup_deleted else R.string.settings_backup_none)
                        }
                        null -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DriveException) {
                    text = e.message
                } catch (e: Exception) {
                    text = ctx.getString(R.string.settings_backup_failed)
                }
                message = text
                busy = false
                job = null
            }
        },
        onMessage = { text -> message = text; busy = false; job = null },
        scope = OfficeHelpers.DRIVE_APPDATA_SCOPE,
        accountEmail = email
    )
    fun start(next: BackupJob) {
        job = next
        busy = true
        message = null
        authorize()
    }

    fun restore(data: BackupData) {
        found = null
        busy = true
        message = null
        scope.launch {
            var failed = false
            try {
                withContext(Dispatchers.IO) { BackupManager.restore(ctx, data) }
                vm.reloadPrefs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                message = ctx.getString(R.string.settings_backup_failed)
            }
            busy = false
            if (!failed) {
                Toast.makeText(ctx, R.string.settings_backup_restored, Toast.LENGTH_LONG).show()
                // The theme and colors are read once when the screen starts, so start it again
                ctx.findHostActivity()?.recreate()
            }
        }
    }

    SettingsGroup(null, Accent.Teal, stringResource(R.string.settings_backup)) {
        val p = profile
        if (p == null) {
            ValueRow(
                title = stringResource(R.string.settings_backup_signin),
                value = null,
                onClick = { startSignIn() }
            )
        } else {
            val never = stringResource(R.string.settings_backup_never)
            ValueRow(
                title = stringResource(R.string.settings_backup_now),
                value = if (lastAt > 0L) formatBackupTime(lastAt) else never,
                enabled = !busy,
                showChevron = false,
                trailing = { if (busy && job == BackupJob.BACKUP) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                onClick = { start(BackupJob.BACKUP) }
            )
            RowDivider()
            SwitchRow(
                title = stringResource(R.string.settings_backup_auto),
                checked = auto,
                onChange = { auto = it; BackupManager.setAuto(ctx, it) }
            )
            RowDivider()
            ValueRow(
                title = stringResource(R.string.settings_backup_restore),
                value = null,
                enabled = !busy,
                showChevron = false,
                trailing = { if (busy && job == BackupJob.RESTORE_FETCH) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                onClick = { start(BackupJob.RESTORE_FETCH) }
            )
            RowDivider()
            ValueRow(
                title = stringResource(R.string.settings_backup_delete),
                value = null,
                enabled = !busy,
                titleColor = MaterialTheme.colorScheme.error,
                showChevron = false,
                trailing = { if (busy && job == BackupJob.DELETE) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                onClick = { confirmDelete = true }
            )
        }
        // Only the result of the last action (saved, failed, none found); no standing helper text
        message?.let {
            Text(
                it,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    found?.let { data ->
        AlertDialog(
            modifier = Modifier.dialogEnter(),
            onDismissRequest = { found = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.settings_backup_restore_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_backup_restore_body,
                        formatBackupTime(data.createdAtMillis),
                        data.device.ifBlank { stringResource(R.string.settings_backup_unknown_device) }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { restore(data) }) { Text(stringResource(R.string.settings_backup_restore_confirm)) }
            },
            dismissButton = { TextButton(onClick = { found = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            modifier = Modifier.dialogEnter(),
            onDismissRequest = { confirmDelete = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.settings_backup_delete_title)) },
            text = { Text(stringResource(R.string.settings_backup_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; start(BackupJob.DELETE) }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

private fun formatBackupTime(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(millis))

@Composable
private fun DriveSection() {
    val ctx = LocalContext.current
    var sweeping by remember { mutableStateOf(false) }
    var sweepMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val authorizeSweep = rememberDriveAuthorizer(
        onToken = { token ->
            scope.launch {
                val message = withContext(Dispatchers.IO) {
                    try {
                        val n = OfficeConvert.removeAllTemp(ctx, token)
                        if (n == 0) "No temporary files found in your Google Drive"
                        else "Removed ${ConvertHelpers.plural(n, "temporary file")} from your Google Drive"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message ?: "Could not remove the files"
                    }
                }
                sweepMessage = message
                sweeping = false
            }
        },
        onMessage = { message -> sweepMessage = message; sweeping = false }
    )
    SettingsGroup(AppIcons.Cloud, Accent.Indigo, stringResource(R.string.settings_drive)) {
        ValueRow(
            title = stringResource(R.string.settings_drive_sweep),
            value = null,
            enabled = !sweeping,
            showChevron = false,
            trailing = { if (sweeping) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
            onClick = { sweeping = true; sweepMessage = null; authorizeSweep() }
        )
        sweepMessage?.let {
            Text(
                it,
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RowDivider()
        ValueRow(
            title = stringResource(R.string.settings_drive_manage),
            value = null,
            showChevron = false,
            trailing = {
                Icon(AppIcons.OpenInNew, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            onClick = {
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/permissions"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        )
    }
}

@Composable
private fun AboutSection() {
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }
    SettingsGroup(AppIcons.Info, Accent.Green, stringResource(R.string.settings_about)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A topic: a small icon and title above one card that holds its rows. */
@Composable
private fun SettingsGroup(icon: ImageVector?, tint: Color, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                IconBadge(icon, tint, size = 32.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        AppCard(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth(), content = content) }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = AppTheme.colors.divider)
}

@Composable
private fun SettingsLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}

/** A tappable row: title on the left, current value and an arrow on the right. */
@Composable
private fun ValueRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    showChevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FitText(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = titleColor)
        Spacer(Modifier.width(12.dp))
        if (value != null) {
            Spacer(Modifier.weight(1f))
            Text(
                value,
                Modifier.widthIn(max = 190.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else Spacer(Modifier.weight(1f))
        trailing?.invoke()
        if (showChevron) {
            Icon(AppIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Pill-shaped choice between a few short options. The chosen one is filled. */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(
                if (on) MaterialTheme.colorScheme.primary else Color.Transparent, tween(180), label = "segBg"
            )
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(i) })
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                FitText(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, onValueChange = onChange, role = Role.Switch)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FitText(
            title,
            Modifier.weight(1f).alpha(if (enabled) 1f else 0.38f),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Round swatches, one per accent theme. Shown dimmed and ignoring taps while wallpaper colors are on. */
@Composable
private fun AccentPicker(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    // Follows the app's own light or dark choice, not only the system one
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(if (enabled) 1f else 0.38f),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsLabel(stringResource(R.string.settings_accent))
        Row(Modifier.fillMaxWidth()) {
            AccentThemes.all.forEachIndexed { index, theme ->
                val isSelected = index == selected
                val swatch = theme.swatch(dark)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .selectable(
                                selected = isSelected, enabled = enabled, role = Role.RadioButton,
                                onClick = { onSelect(index) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val label = stringResource(theme.nameRes)
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(swatch)
                                .then(
                                    if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier
                                )
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) Icon(
                                AppIcons.Check, contentDescription = null,
                                tint = if (dark) theme.dark.onPrimary else theme.light.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
