package com.pdfmanager.app.ui.tools

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pdfmanager.app.R
import com.pdfmanager.app.ui.AppIcons
import com.pdfmanager.app.ui.AppTheme
import com.pdfmanager.app.ui.EmptyState
import java.util.Locale

/** Texts a tool can be found by: its name and its category, in the current language and in English. */
private class ToolLabels(context: Context) {
    private val current = context.resources
    private val english = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
    ).resources

    fun searchTerms(tool: ToolDef): List<String> = listOf(
        current.getString(tool.nameRes), english.getString(tool.nameRes),
        current.getString(tool.category.titleRes), english.getString(tool.category.titleRes)
    )
}

/**
 * The Tools screen (Grid view). All content comes from [ToolCatalog]; [onNavigate] receives the
 * tool's existing route. Search filters the catalog by tool name and category.
 */
@Composable
fun ToolsScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val focus = LocalFocusManager.current
    // Rebuilt when the language changes
    val labels = remember(configuration) { ToolLabels(context) }
    // The chosen view is remembered in the same "ui" preferences the rest of the app uses
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var listMode by remember { mutableStateOf(prefs.getBoolean("toolsList", false)) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, labels) { ToolCatalog.search(query) { labels.searchTerms(it) } }

    val closeSearch = { searching = false; query = "" }
    // Back closes the search first, then leaves the screen
    BackHandler(enabled = searching) { closeSearch() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ToolsHeader(
                searching = searching,
                listMode = listMode,
                onToggleView = { listMode = !listMode; prefs.edit().putBoolean("toolsList", listMode).apply() },
                query = query,
                onQuery = { query = it },
                onOpenSearch = { searching = true },
                onCloseSearch = closeSearch
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (results.isEmpty()) {
                EmptyState(
                    icon = AppIcons.Search,
                    title = stringResource(R.string.tools_empty_title)
                )
            } else {
                val open: (ToolDef) -> Unit = { tool ->
                    focus.clearFocus()
                    onNavigate(tool.route)
                }
                if (listMode) ToolList(results, onToolClick = open) else ToolGrid(results, onToolClick = open)
            }
        }
    }
}

/** App logo and name with a search action; the search action turns the header into a search field. */
@Composable
private fun ToolsHeader(
    searching: Boolean,
    listMode: Boolean,
    onToggleView: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (searching) {
            ToolSearchField(query, onQuery, onCloseSearch)
        } else {
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
            IconButton(onClick = onOpenSearch) {
                Icon(AppIcons.Search, contentDescription = stringResource(R.string.tools_search_open))
            }
            // Shows the view you switch to, as in the reference
            IconButton(onClick = onToggleView) {
                Icon(
                    if (listMode) AppIcons.GridView else AppIcons.ViewList,
                    contentDescription = stringResource(if (listMode) R.string.tools_view_grid else R.string.tools_view_list)
                )
            }
        }
    }
}

@Composable
private fun ToolSearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val colors = AppTheme.colors
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Surface(
        Modifier.fillMaxWidth().height(48.dp),
        shape = CircleShape,
        color = colors.toolCard
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.tools_search_close))
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.weight(1f).focusRequester(requester),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.tools_search_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textSecondary,
                                maxLines = 1
                            )
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.tools_search_clear))
                }
            }
        }
    }
}
