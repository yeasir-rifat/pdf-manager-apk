package com.pdfmanager.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
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
 */
@Composable
fun SettingsScreen(
    vm: LibraryViewModel,
    themeMode: Int,
    onThemeMode: (Int) -> Unit,
    dynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit
) {
    val sort by vm.sort.collectAsStateWithLifecycle()
    val searchInside by vm.searchInside.collectAsStateWithLifecycle()

    // The message shown when the stored PDF text is deleted comes from the ViewModel, as in the library
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Box(Modifier.fillMaxWidth()) {
        ToolScaffold(stringResource(R.string.settings_title), onBack = null) {
            OptionCard(stringResource(R.string.settings_appearance)) {
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_wallpaper_colors),
                        checked = dynamicColor,
                        onChange = onDynamicColor
                    )
                }
            }
            OptionCard(stringResource(R.string.settings_files)) {
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
                    description = stringResource(R.string.settings_search_inside_desc),
                    checked = searchInside,
                    onChange = { vm.setSearchInside(it) }
                )
                HorizontalDivider()
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val askName = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(NamePref.enabled(ctx)) }
                SwitchRow(
                    title = "Ask for file name",
                    description = "After a tool finishes, ask what to call the new file",
                    checked = askName.value,
                    onChange = { askName.value = it; NamePref.set(ctx, it) }
                )
            }
            OptionCard("Google Drive") {
                val driveCtx = LocalContext.current
                Text(
                    "Word, Excel and PowerPoint to PDF use your own Google Drive for a moment: the file is sent there, " +
                        "converted and deleted right away. The app only sees files it makes itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var sweeping by remember { mutableStateOf(false) }
                var sweepMessage by remember { mutableStateOf<String?>(null) }
                val sweepScope = rememberCoroutineScope()
                val authorizeSweep = rememberDriveAuthorizer(
                    onToken = { token ->
                        sweepScope.launch {
                            val message = withContext(Dispatchers.IO) {
                                try {
                                    val n = OfficeConvert.removeAllTemp(driveCtx, token)
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
                SecondaryButton(
                    "Remove leftover temp files",
                    enabled = !sweeping,
                    onClick = { sweeping = true; sweepMessage = null; authorizeSweep() }
                )
                sweepMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SecondaryButton(
                    "Manage Google access",
                    onClick = {
                        runCatching {
                            driveCtx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/permissions"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
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
private fun SwitchRow(title: String, description: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
