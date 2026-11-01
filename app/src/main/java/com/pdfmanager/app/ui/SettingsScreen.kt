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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings tab. It changes the same stored preferences the rest of the app already uses:
 * theme and wallpaper colors come from MainActivity (the "ui" preferences), default sorting and
 * search-inside-PDFs come from the shared [LibraryViewModel]. Nothing is stored twice.
 *
 * One dropdown card per topic, in this order: Appearance, Files, Saving, Feedback, Google Drive, About.
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
            AppearanceSection(themeMode, onThemeMode, dynamicColor, onDynamicColor, accent, onAccent, pureBlack, onPureBlack)
            FilesSection(vm)
            SavingSection()
            FeedbackSection()
            DriveSection()
            AboutSection()
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
    SettingsCard(stringResource(R.string.settings_appearance)) {
        // Theme and the dark-only switch that belongs to it
        SettingsLabel(stringResource(R.string.settings_theme))
        Column {
            listOf(
                ThemeMode.SYSTEM to R.string.settings_theme_system,
                ThemeMode.LIGHT to R.string.settings_theme_light,
                ThemeMode.DARK to R.string.settings_theme_dark
            ).forEach { (mode, label) ->
                ChoiceRow(stringResource(label), themeMode == mode) { onThemeMode(mode) }
            }
        }
        // Only the dark theme can be pure black, so the switch is gray and locked in the light theme
        val darkNow = when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            else -> androidx.compose.foundation.isSystemInDarkTheme()
        }
        HorizontalDivider()
        SwitchRow(
            title = stringResource(R.string.settings_pure_black),
            checked = pureBlack,
            onChange = onPureBlack,
            enabled = darkNow
        )

        // Colors: wallpaper colors and the accent picker sit together because the first overrides the second
        HorizontalDivider()
        if (wallpaperColorsAvailable) {
            SwitchRow(
                title = stringResource(R.string.settings_wallpaper_colors),
                checked = dynamicColor,
                onChange = onDynamicColor
            )
            HorizontalDivider()
        }
        // Wallpaper colors win over the accent, so the picker is shown dimmed and cannot be changed
        val accentLocked = dynamicColor && wallpaperColorsAvailable
        AccentPicker(selected = accent, enabled = !accentLocked, onSelect = onAccent)
    }
}

/** How the library lists and searches your files. */
@Composable
private fun FilesSection(vm: LibraryViewModel) {
    val sort by vm.sort.collectAsStateWithLifecycle()
    val searchInside by vm.searchInside.collectAsStateWithLifecycle()
    SettingsCard(stringResource(R.string.settings_files)) {
        SettingsLabel(stringResource(R.string.settings_sort))
        Column {
            listOf(
                SortMode.NAME to R.string.settings_sort_name,
                SortMode.NEWEST to R.string.settings_sort_newest,
                SortMode.LARGEST to R.string.settings_sort_largest
            ).forEach { (mode, label) ->
                ChoiceRow(stringResource(label), sort == mode) { vm.sort.value = mode }
            }
        }
        HorizontalDivider()
        SwitchRow(
            title = stringResource(R.string.settings_search_inside),
            checked = searchInside,
            onChange = { vm.setSearchInside(it) }
        )
    }
}

/** What happens when a tool saves a new file, and where the file goes. */
@Composable
private fun SavingSection() {
    val ctx = LocalContext.current
    var askName by remember { mutableStateOf(NamePref.enabled(ctx)) }
    SettingsCard(stringResource(R.string.settings_saving)) {
        SwitchRow(
            title = stringResource(R.string.settings_ask_name),
            checked = askName,
            onChange = { askName = it; NamePref.set(ctx, it) }
        )
    }
}

@Composable
private fun FeedbackSection() {
    val ctx = LocalContext.current
    var vibration by remember { mutableStateOf(HapticPrefs.enabled(ctx)) }
    SettingsCard(stringResource(R.string.settings_feedback)) {
        SwitchRow(
            title = stringResource(R.string.settings_vibration),
            checked = vibration,
            onChange = { vibration = it; HapticPrefs.set(ctx, it) }
        )
    }
}

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
    SettingsCard(stringResource(R.string.settings_drive)) {
        SecondaryButton(
            stringResource(R.string.settings_drive_sweep),
            enabled = !sweeping,
            onClick = { sweeping = true; sweepMessage = null; authorizeSweep() }
        )
        sweepMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SecondaryButton(
            stringResource(R.string.settings_drive_manage),
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
    SettingsCard(stringResource(R.string.settings_about)) {
        Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Length of the open and close motion of a Settings card. */
private const val SETTINGS_CARD_MS = 300

/**
 * A Settings card that opens and closes like a dropdown. It starts closed. Tapping the title row
 * turns the arrow and slides the content open or shut with a fade. The open or closed choice
 * survives rotation. Content that changes (switches, the Drive result line) is kept by the callers,
 * so closing a card never loses a setting.
 */
@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val arrow by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(SETTINGS_CARD_MS, easing = FastOutSlowInEasing),
        label = "settingsArrow"
    )
    AppCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel(title, Modifier.weight(1f))
                Icon(
                    AppIcons.ExpandMore, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = arrow }
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(SETTINGS_CARD_MS, easing = FastOutSlowInEasing)) +
                    fadeIn(tween(SETTINGS_CARD_MS, easing = FastOutSlowInEasing)),
                exit = shrinkVertically(tween(SETTINGS_CARD_MS, easing = FastOutSlowInEasing)) +
                    fadeOut(tween(SETTINGS_CARD_MS / 2))
            ) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) { content() }
            }
        }
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
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
    description: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, onValueChange = onChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.weight(1f).alpha(if (enabled) 1f else 0.38f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            FitText(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
