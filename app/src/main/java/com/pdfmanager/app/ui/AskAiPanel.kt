package com.pdfmanager.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfmanager.app.R
import com.pdfmanager.app.data.AiChat
import com.pdfmanager.app.data.AiChip
import com.pdfmanager.app.data.AiMarkdownParser
import com.pdfmanager.app.data.AiPrefs
import com.pdfmanager.app.data.AiScope
import com.pdfmanager.app.data.GeminiAsk
import com.pdfmanager.app.data.GeminiAskResult
import com.pdfmanager.app.data.GeminiError
import com.pdfmanager.app.data.GeminiErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One question and what came of it. [shown] is what the bubble says, [prompt] is what is sent (a chip sends a longer text). */
@Stable
class AiExchange(val shown: String, val prompt: String, val scope: AiScope, val readerPage: Int) {
    /** A number that never repeats, used as the list key. */
    val id: Long = nextId++

    var answer by mutableStateOf<String?>(null)
    var error by mutableStateOf<GeminiError?>(null)
    var loading by mutableStateOf(true)
}

/**
 * Everything the Ask AI panel remembers while the viewer is open. It is created with `remember(uriString)` in the
 * viewer, so it survives closing the panel, the bars hiding and rotation. Nothing is saved after the viewer closes.
 */
@Stable
class AskAiState {
    val exchanges = mutableStateListOf<AiExchange>()

    /** 0 = This page, 1 = Whole PDF, 2 = Range. */
    var kind by mutableIntStateOf(0)
    var page by mutableIntStateOf(1)
    var rangeText by mutableStateOf("")
    var range by mutableStateOf<AiScope.Range?>(null)
    var rangeError by mutableStateOf<String?>(null)
    var input by mutableStateOf("")
    var uploadPercent by mutableStateOf<Int?>(null)

    /** When Send may be used again after a 429 (SystemClock.elapsedRealtime), and the whole seconds left. 0 means free. */
    var cooldownUntil by mutableLongStateOf(0L)
    var cooldownLeft by mutableIntStateOf(0)

    /** Pauses Send for [seconds], the wait Google asked for. Nothing retries by itself. */
    fun startCooldown(seconds: Int) {
        if (seconds <= 0) return
        cooldownUntil = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
        cooldownLeft = seconds
    }

    /** The scope of the PDF attached last time, used only when [AiChat.ALWAYS_ATTACH] is off. */
    var attached: AiScope? = null
    var job: Job? = null

    val loading: Boolean get() = exchanges.lastOrNull()?.loading == true

    /** A fresh chat. The scope and the page stay as they are. */
    fun newChat() {
        job?.cancel()
        job = null
        exchanges.clear()
        attached = null
        uploadPercent = null
        input = ""
    }

    /** Opening the panel starts on This page, on the page the reader is on. */
    fun reset(readerPage: Int) {
        kind = 0
        page = readerPage
    }

    fun stop() {
        job?.cancel()
    }
}

private var nextId = 0L

private const val KEY_URL = "https://aistudio.google.com/apikey"

/**
 * The Ask AI panel: scope control, chat, quick chips and the input. It never sends anything before the first send
 * dialog is accepted, and never in the background. [uri] is the viewer's openUri, so a password PDF sends its unlocked copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskAiPanel(
    state: AskAiState,
    uri: String,
    totalPages: Int,
    readerPage: Int,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onGoToPage: (Int) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { AiPrefs.of(context) }
    val hasKey = remember { prefs.hasKey }
    val haptics = rememberAppHaptics()
    val coroutines = rememberCoroutineScope()
    var pendingSend by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pickPage by remember { mutableStateOf(false) }

    fun startAsk(shown: String, prompt: String, scope: AiScope, reader: Int) {
        val ex = AiExchange(shown, prompt, scope, reader)
        // Only finished answers go back to Gemini as earlier turns. A question that failed halfway does not.
        val done = state.exchanges.mapNotNull { e ->
            e.answer?.takeIf { !e.loading && e.error == null && it.isNotBlank() }?.let { AiChat.Pair(e.prompt, it) }
        }
        state.exchanges += ex
        state.input = ""
        state.uploadPercent = null
        state.job = coroutines.launch {
            try {
                val result = GeminiAsk.ask(
                    context = context, uri = Uri.parse(uri), scope = scope, readerPage = reader, totalPages = totalPages,
                    question = prompt, apiKey = prefs.apiKey, modelId = prefs.modelId(),
                    language = prefs.language, length = prefs.length,
                    history = AiChat.history(done), attachedScope = AiChat.attachedFor(state.attached),
                    onProgress = { state.uploadPercent = it },
                    // The answer types out: the card shows the text so far
                    onText = { text -> ex.answer = text },
                )
                when (result) {
                    is GeminiAskResult.Answer -> { ex.answer = result.answer.text; ex.error = null; state.attached = scope }
                    // A stream that broke keeps the text that came. The card shows it with the error line and Try again.
                    is GeminiAskResult.Failed -> { ex.error = result.error; state.startCooldown(result.retryAfterSec) }
                }
                ex.loading = false
                state.uploadPercent = null
            } catch (e: CancellationException) {
                // Stop keeps the partial text. A question that got no text at all goes away.
                if (ex.answer.isNullOrBlank()) {
                    state.exchanges.remove(ex)
                    // The question the person typed comes back, so nothing is lost
                    if (state.input.isBlank() && ex.shown == ex.prompt) state.input = ex.shown
                } else ex.loading = false
                state.uploadPercent = null
                throw e
            } catch (e: Exception) {
                // Anything unexpected ends as one short error, never a crash and never a spinner that does not stop
                ex.error = GeminiError.File
                ex.loading = false
                state.uploadPercent = null
            } catch (e: OutOfMemoryError) {
                ex.error = GeminiError.File
                ex.loading = false
                state.uploadPercent = null
            }
        }
    }

    /** Works out the scope, then sends once the first send dialog has been accepted. */
    fun send(shown: String, prompt: String) {
        if (state.loading || state.cooldownLeft > 0 || shown.isBlank()) return
        val scope: AiScope = when (state.kind) {
            0 -> AiScope.Page(state.page)
            1 -> AiScope.Whole
            else -> when (val r = AiScope.range(state.rangeText, totalPages)) {
                is AiScope.Parsed.Ok -> { state.range = r.scope; state.rangeError = null; r.scope }
                is AiScope.Parsed.Invalid -> { state.rangeError = r.message; return }
            }
        }
        val go = { startAsk(shown, prompt, scope, readerPage) }
        haptics.tick()
        if (prefs.consented) go() else pendingSend = go
    }

    /** Asks the same question again. The last exchange is replaced, an older one gets a new exchange at the end. */
    fun redo(ex: AiExchange) {
        if (state.loading || state.cooldownLeft > 0) return
        if (ex === state.exchanges.lastOrNull()) state.exchanges.remove(ex)
        val go = { startAsk(ex.shown, ex.prompt, ex.scope, ex.readerPage) }
        if (prefs.consented) go() else pendingSend = go
    }

    val copiedText = stringResource(R.string.common_copied)
    val snackbar = remember { SnackbarHostState() }
    fun copy(answer: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("", AiMarkdownParser.toPlain(answer)))
        }
        haptics.confirm()
        coroutines.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(copiedText, duration = SnackbarDuration.Short)
        }
    }
    fun share(answer: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, AiMarkdownParser.toPlain(answer))
        }
        runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // The screen stays on while an answer is on its way, and only then
    val view = LocalView.current
    val streaming = state.loading
    DisposableEffect(streaming) {
        view.keepScreenOn = streaming
        onDispose { view.keepScreenOn = false }
    }
    // Counts the pause after a 429 down, once a second. Send and the chips come back at 0.
    LaunchedEffect(state.cooldownUntil) {
        while (true) {
            val left = ((state.cooldownUntil - android.os.SystemClock.elapsedRealtime() + 999L) / 1000L).toInt()
            state.cooldownLeft = left.coerceAtLeast(0)
            if (left <= 0) break
            delay(1000L)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeight = config.screenHeightDp.dp
    val imeHeight = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val imeOpen = imeHeight > 0.dp
    val compact = config.screenHeightDp < 480
    // Dragging the handle up opens the panel to full height, dragging it down brings it back to 62 percent.
    // The panel is never so tall that the keyboard pushes its top off screen.
    var expanded by remember { mutableStateOf(false) }
    val target = if (expanded) screenHeight - 96.dp else screenHeight * 0.62f
    val panelHeight by animateDpAsState(
        minOf(target, screenHeight - imeHeight - 48.dp).coerceAtLeast(if (compact) 220.dp else 280.dp),
        label = "askAiHeight",
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                Modifier.pointerInput(expanded) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total < -40f) expanded = true
                            else if (total > 40f) { if (expanded) expanded = false else onDismiss() }
                        },
                        onVerticalDrag = { _, delta -> total += delta },
                    )
                }
            ) { BottomSheetDefaults.DragHandle() }
        },
    ) {
        Box(Modifier.fillMaxWidth().imePadding().height(panelHeight)) {
        Column(Modifier.fillMaxSize().padding(horizontal = AppDimens.space16)) {
            // Header: title on the left, New chat and AI settings on the right. Drag down and Back close the panel.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ai_title),
                    style = AppText.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (hasKey) {
                    IconButton(onClick = { state.newChat() }) {
                        Icon(AppIcons.NoteAdd, stringResource(R.string.ai_new_chat))
                    }
                }
                IconButton(onClick = onOpenSettings) { Icon(AppIcons.Settings, stringResource(R.string.ai_settings)) }
            }
            if (!hasKey) {
                NoKey(onAddKey = onOpenSettings, onGetKey = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            } else {
                ScopeControl(state, totalPages, onTick = { haptics.tick() }, onPickPage = { pickPage = true })
                Spacer(Modifier.height(AppDimens.space8))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.exchanges.isEmpty()) SmallEmpty(state)
                    else Chat(state, onPage = onGoToPage, onCopy = { copy(it) }, onShare = { share(it) }, onRedo = { redo(it) })
                }
                Spacer(Modifier.height(AppDimens.space8))
                // On a short screen (landscape) the keyboard needs the room, so the chips wait until it closes
                if (!(compact && imeOpen)) {
                    Chips(state, enabled = !state.loading && state.cooldownLeft == 0, onChip = { chip, label -> send(label, chip.prompt) })
                    Spacer(Modifier.height(AppDimens.space8))
                }
                InputRow(state, onSend = { send(state.input.trim(), state.input.trim()) }, onStop = { state.stop() })
                Spacer(Modifier.height(AppDimens.space12))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
        }
    }

    if (pickPage) {
        // The same Go to page dialog as the viewer. It sets the page the question is about and does not scroll the PDF.
        GoToPageDialog(totalPages, state.page, onDismiss = { pickPage = false }) { page ->
            pickPage = false
            haptics.tick()
            state.page = page.coerceIn(1, totalPages.coerceAtLeast(1))
        }
    }

    pendingSend?.let { go ->
        AlertDialog(
            modifier = Modifier.dialogEnter(),
            onDismissRequest = { pendingSend = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.ai_consent_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { prefs.consented = true; pendingSend = null; go() }) {
                    Text(stringResource(R.string.ai_consent_send), maxLines = 1)
                }
            },
            dismissButton = { TextButton(onClick = { pendingSend = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1) } },
        )
    }
}

@Composable
private fun chipLabel(chip: AiChip): String = stringResource(
    when (chip) {
        AiChip.Explain -> R.string.ai_chip_explain
        AiChip.Summarize -> R.string.ai_chip_summarize
        AiChip.Detail -> R.string.ai_chip_detail
        AiChip.Points -> R.string.ai_chip_points
        AiChip.Outline -> R.string.ai_chip_outline
        AiChip.Quiz -> R.string.ai_chip_quiz
    }
)

@Composable
private fun scopeTag(scope: AiScope): String = when (scope) {
    is AiScope.Page -> stringResource(R.string.ai_tag_page, scope.number)
    AiScope.Whole -> stringResource(R.string.ai_scope_whole)
    is AiScope.Range -> stringResource(R.string.ai_tag_pages, AiScope.rangeLabel(scope.pages))
}

/** No key saved: only the two buttons. The scope control, chips and input stay hidden. */
@Composable
private fun ColumnScope.NoKey(onAddKey: () -> Unit, onGetKey: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyIcon()
        Spacer(Modifier.height(AppDimens.space16))
        Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Spacer(Modifier.height(AppDimens.space24))
        PrimaryButton(stringResource(R.string.ai_add_key), onAddKey)
        Spacer(Modifier.height(AppDimens.space8))
        SecondaryButton(stringResource(R.string.ai_get_key), onGetKey)
    }
}

@Composable
private fun EmptyIcon() {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)))
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
        Icon(AppIcons.Sparkle, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
    }
}

/** Key saved, no messages yet: the sparkle on soft circles, and a title that follows the scope. */
@Composable
private fun SmallEmpty(state: AskAiState) {
    Column(Modifier.fillMaxWidth().padding(vertical = AppDimens.space8), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyIcon()
        Spacer(Modifier.height(AppDimens.space12))
        Text(
            stringResource(
                when (state.kind) { 0 -> R.string.ai_empty_page; 1 -> R.string.ai_empty_whole; else -> R.string.ai_empty_range }
            ),
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** This page, Whole PDF, Range. This page shows the stepper, Range shows one field and Set. */
@Composable
private fun ScopeControl(state: AskAiState, totalPages: Int, onTick: () -> Unit, onPickPage: () -> Unit) {
    Segmented(
        listOf(stringResource(R.string.ai_scope_page), stringResource(R.string.ai_scope_whole), stringResource(R.string.ai_scope_range)),
        state.kind,
    ) { if (it != state.kind) { onTick(); state.kind = it } }
    when (state.kind) {
        0 -> Row(Modifier.fillMaxWidth().padding(top = AppDimens.space4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { onTick(); state.page = (state.page - 1).coerceAtLeast(1) }, enabled = state.page > 1) {
                Icon(AppIcons.KeyboardArrowLeft, stringResource(R.string.ai_prev_page))
            }
            Text(
                stringResource(R.string.ai_page_of, state.page.coerceIn(1, totalPages.coerceAtLeast(1)), totalPages),
                Modifier.widthIn(min = 72.dp).heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { onPickPage() }
                    .wrapContentHeight(Alignment.CenterVertically),
                style = MaterialTheme.typography.titleSmall, maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = { onTick(); state.page = (state.page + 1).coerceAtMost(totalPages) }, enabled = state.page < totalPages) {
                Icon(AppIcons.KeyboardArrowRight, stringResource(R.string.ai_next_page))
            }
        }
        2 -> Column(Modifier.fillMaxWidth().padding(top = AppDimens.space8)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = state.rangeText,
                    onValueChange = { state.rangeText = it; state.rangeError = null; state.range = null },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("3-7, 10", maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = pillColors(),
                )
                Spacer(Modifier.size(AppDimens.space8))
                TextButton(onClick = {
                    onTick()
                    when (val r = AiScope.range(state.rangeText, totalPages)) {
                        is AiScope.Parsed.Ok -> { state.range = r.scope; state.rangeError = null }
                        is AiScope.Parsed.Invalid -> state.rangeError = r.message
                    }
                }) { Text(stringResource(R.string.ai_range_set), maxLines = 1) }
            }
            if (state.rangeError == null) state.range?.let { r ->
                Text(
                    scopeTag(r), Modifier.padding(start = 16.dp, top = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                )
            }
            state.rangeError?.let {
                Text(it, Modifier.padding(start = 16.dp, top = 4.dp), color = MaterialTheme.colorScheme.error, style = AppText.caption, maxLines = 2)
            }
        }
    }
}

@Composable
private fun pillColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    errorIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
)

/** Your messages on the right with a scope tag, answers on the left in a full width card. The list sticks to the bottom. */
@Composable
private fun Chat(
    state: AskAiState,
    onPage: (Int) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRedo: (AiExchange) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutines = rememberCoroutineScope()
    val last = state.exchanges.lastOrNull()
    // True while the list follows the newest text. Scrolling up by hand turns it off, and the Latest button turns it on again.
    var stuck by remember { mutableStateOf(true) }
    fun atBottom(): Boolean {
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
        return lastVisible.index >= info.totalItemsCount - 1 && lastVisible.offset + lastVisible.size <= info.viewportEndOffset + 24
    }
    LaunchedEffect(state.exchanges.size) {
        if (state.exchanges.isNotEmpty()) { stuck = true; listState.scrollToItem(state.exchanges.size - 1) }
    }
    LaunchedEffect(last?.answer, last?.loading) {
        if (stuck && !listState.isScrollInProgress) listState.scrollBy(1_000_000f)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { moving -> if (!moving) stuck = atBottom() }
    }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(AppDimens.space8)) {
        items(state.exchanges, key = { it.id }) { ex ->
            Column(verticalArrangement = Arrangement.spacedBy(AppDimens.space8)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Column(
                        Modifier.widthIn(max = 320.dp)
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .semantics(mergeDescendants = true) {}
                    ) {
                        Text(
                            scopeTag(ex.scope), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f), maxLines = 1,
                        )
                        Text(ex.shown, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        val err = ex.error
                        val partial = ex.answer?.takeIf { it.isNotBlank() }
                        when {
                            ex.loading && partial == null -> Waiting(if (ex === last) state.uploadPercent else null)
                            ex.loading -> SelectionContainer { AiMarkdown(partial!!, onPage = onPage) }
                            else -> {
                                if (partial != null) SelectionContainer { AiMarkdown(partial, onPage = onPage) }
                                if (err != null) {
                                    if (partial != null) Spacer(Modifier.height(AppDimens.space8))
                                    Text(stringResource(GeminiErrors.messageRes(err)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (ex === last) TextButton(onClick = { onRedo(ex) }, enabled = state.cooldownLeft == 0) { Text(stringResource(R.string.common_try_again), maxLines = 1) }
                                } else if (partial != null) {
                                    Row(Modifier.fillMaxWidth().padding(top = AppDimens.space4)) {
                                        IconButton(onClick = { onCopy(partial) }) { Icon(AppIcons.ContentCopy, stringResource(R.string.viewer_copy)) }
                                        IconButton(onClick = { onShare(partial) }) { Icon(AppIcons.Share, stringResource(R.string.common_share)) }
                                        IconButton(onClick = { onRedo(ex) }, enabled = !state.loading && state.cooldownLeft == 0) { Icon(AppIcons.Refresh, stringResource(R.string.ai_redo)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // The round arrow shows only while an answer is typing and the reader has scrolled up
    if (!stuck && last?.loading == true) {
        Box(
            Modifier.align(Alignment.BottomEnd).padding(AppDimens.space8).size(48.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(AppDimens.hairline, AppTheme.colors.cardBorder, CircleShape)
                .clickable(role = Role.Button) { stuck = true; coroutines.launch { listState.scrollBy(1_000_000f) } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.KeyboardArrowDown, stringResource(R.string.ai_latest), Modifier.size(24.dp))
        }
    }
    }
}

/** Three shimmer lines, or one "Uploading 42%" line while a large PDF goes up. */
@Composable
private fun Waiting(percent: Int?) {
    if (percent != null) {
        Text(stringResource(R.string.ai_uploading, percent), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (w in listOf(1f, 0.85f, 0.55f)) {
            Box(Modifier.fillMaxWidth(w).height(14.dp).clip(RoundedCornerShape(6.dp)).shimmer())
        }
    }
}

/** Quick prompts. They change with the scope and scroll sideways. A chip sends its longer prompt and shows its label. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Chips(state: AskAiState, enabled: Boolean, onChip: (AiChip, String) -> Unit) {
    val chips = AiChip.forScope(if (state.kind == 0) AiScope.Page(state.page) else AiScope.Whole)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppDimens.space8)) {
        for (chip in chips) {
            val label = chipLabel(chip)
            val src = remember { MutableInteractionSource() }
            AssistChip(
                onClick = { onChip(chip, label) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.pressScale(src),
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(AppDimens.hairline, AppTheme.colors.cardBorder),
                interactionSource = src,
            )
        }
    }
}

/** The pill input with a round Send button that turns into Stop while an answer is on its way. */
@Composable
private fun InputRow(state: AskAiState, onSend: () -> Unit, onStop: () -> Unit) {
    val loading = state.loading
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(AppDimens.space8)) {
        TextField(
            value = state.input,
            onValueChange = { state.input = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.ai_input), maxLines = 1) },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            colors = pillColors(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        val wait = state.cooldownLeft
        val sendLabel = stringResource(if (loading) R.string.ai_stop else R.string.ai_send)
        val canSend = loading || (state.input.isNotBlank() && wait == 0)
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (loading) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary.copy(alpha = if (canSend) 1f else 0.4f))
                .clickable(enabled = canSend, role = Role.Button) { if (loading) onStop() else onSend() },
            contentAlignment = Alignment.Center,
        ) {
            if (wait > 0 && !loading) {
                // After a 429 the button shows the seconds left, then turns back into Send
                Text(
                    "$wait", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1, modifier = Modifier.semantics { contentDescription = sendLabel },
                )
            } else {
                Icon(
                    if (loading) AppIcons.Stop else AppIcons.Send,
                    sendLabel,
                    tint = if (loading) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
