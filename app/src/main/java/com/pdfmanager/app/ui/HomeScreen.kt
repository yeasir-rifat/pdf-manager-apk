package com.pdfmanager.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdfmanager.app.LibraryViewModel
import com.pdfmanager.app.R
import com.pdfmanager.app.data.IncomingPdf
import com.pdfmanager.app.data.PdfEntity
import com.pdfmanager.app.ui.tools.ToolCard
import com.pdfmanager.app.ui.tools.ToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The tools offered as Quick actions on Home (the same four as before), resolved through the tool catalog. */
internal val quickActionIds = listOf("scan", "merge", "img2pdf", "compress")

/**
 * Home tab: quick actions and the six most recently opened files.
 *
 * It holds no file logic of its own. Files, folders, recents and every file operation come from the
 * shared [LibraryViewModel] that MainActivity passes in, the same instance the Files screen uses.
 * [onNavigate] receives a tool route; [onOpenFiles] switches to the Files tab.
 */
@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onPrint: (PdfEntity) -> Unit,
    onOpenFiles: () -> Unit,
    vm: LibraryViewModel
) {
    val context = LocalContext.current
    val access = rememberStorageAccess(vm)
    val recents by vm.recents.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val actions = rememberFileActionState()
    val quickTools = remember { quickActionIds.mapNotNull { id -> ToolCatalog.all.firstOrNull { it.id == id } } }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // "Open a PDF" on the empty state. The picked file is copied to the cache first, the same way as
    // a PDF opened from another app, so its address is safe to put in the viewer route.
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { runCatching { IncomingPdf.copyToCache(context, uri) } }
                .onSuccess(onOpen)
                .onFailure {
                    val msg = if (it.message == "Not a PDF file") R.string.home_not_pdf else R.string.home_open_failed
                    snackbar.showSnackbar(context.getString(msg))
                }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { HomeHeader() }
    ) { padding ->
        if (!access.granted) {
            PermissionHero(Modifier.padding(padding), onGrant = access.request)
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Search is always visible here, so a new person does not have to find a small icon
            item(key = "search") { HomeSearchPill(onClick = { vm.openSearch(); onOpenFiles() }) }
            item(key = "quick-title") { HomeSectionTitle(stringResource(R.string.home_quick_actions)) }
            item(key = "quick-actions") {
                // Two per row: four narrow cards would clip longer (and Bengali) names
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    quickTools.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { tool ->
                                ToolCard(tool, onClick = { onNavigate(tool.route) }, modifier = Modifier.weight(1f))
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            item(key = "recent-title") {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    HomeSectionTitle(stringResource(R.string.home_recent), Modifier.weight(1f))
                    if (recents.isNotEmpty()) {
                        // Files opens on its Recent filter
                        TextButton(onClick = { vm.showRecent(); onOpenFiles() }) {
                            Text(stringResource(R.string.home_see_all))
                        }
                    }
                }
            }
            if (recents.isEmpty()) {
                item(key = "recent-empty") { EmptyRecent(onOpenPdf = { picker.launch(arrayOf("application/pdf")) }) }
            } else {
                items(recents, key = { it.uri }) { pdf ->
                    RecentFileCard(
                        pdf = pdf,
                        onClick = { onOpen(pdf.uri) },
                        onFavorite = { vm.setFavorite(listOf(pdf.uri), !pdf.favorite) },
                        onShare = { Share.pdfs(context, listOf(pdf.uri)) },
                        onRename = { actions.renameTarget = pdf },
                        onMove = { actions.moveTargets = listOf(pdf.uri) },
                        onUseInTool = { actions.toolTargets = listOf(pdf) },
                        onPrint = { onPrint(pdf) },
                        onDelete = { actions.deleteTargets = listOf(pdf) }
                    )
                }
            }
        }
    }

    FileActionDialogs(actions, vm, folders, onNavigate)
}

/** App logo and name; same header geometry as the Tools screen. */
@Composable
private fun HomeHeader() {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 16.dp),
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
        ProfileButton()
    }
}

/** A wide search field look-alike. Tapping it opens Files with the search field focused. */
@Composable
private fun HomeSearchPill(onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().height(52.dp)
            .clip(CircleShape)
            .background(colors.toolCard)
            .border(AppDimens.hairline, colors.cardBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(AppIcons.Search, contentDescription = null, tint = colors.textSecondary)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.home_search_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HomeSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = AppTheme.colors.textPrimary,
        maxLines = 1
    )
}

/** Shown while no file has been opened yet: icon, one line of text and one clear button that opens the file picker. */
@Composable
private fun EmptyRecent(onOpenPdf: () -> Unit) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(AppDimens.cardRadius)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.toolCard)
            .border(AppDimens.hairline, colors.cardBorder, shape).padding(AppDimens.space24),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconBadge(AppIcons.PictureAsPdf, Accent.Red, size = 56.dp)
        Spacer(Modifier.height(AppDimens.space16))
        FitText(
            stringResource(R.string.home_recent_empty_title),
            modifier = Modifier.fillMaxWidth(),
            style = AppText.subtitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(AppDimens.space16))
        // Same size and shape as PrimaryButton, but the label can shrink so it never wraps
        Button(
            onClick = onOpenPdf,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(AppDimens.space8))
            FitText(stringResource(R.string.home_open_pdf), style = MaterialTheme.typography.labelLarge)
        }
    }
}
