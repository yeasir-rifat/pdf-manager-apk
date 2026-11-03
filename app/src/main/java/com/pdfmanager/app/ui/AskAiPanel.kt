package com.pdfmanager.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
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

    /** 0 = This page, 1 = Whole PDF. */
    var kind by mutableIntStateOf(0)
    var page by mutableIntStateOf(1)
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
        val scope: AiScope = if (state.kind == 0) {
            AiScope.Page(state.page)
        } else {
            AiScope.Whole
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
            cm.setPrimaryClip(ClipData.newPlainText("", AiMarkdownParser.toPlain(stripPageRefs(answer))))
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
            putExtra(Intent.EXTRA_TEXT, AiMarkdownParser.toPlain(stripPageRefs(answer)))
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

    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val imm = remember { context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager }
    // The focus goes first, so the field comes back down together with the keyboard.
    fun hideKeyboard() {
        focus.clearFocus()
        runCatching { imm?.hideSoftInputFromWindow(view.windowToken, 0) }
    }
    // However the panel goes away (Close, AI settings, the viewer closing) the keyboard goes with it.
    // It must never stay behind over the PDF.
    DisposableEffect(Unit) {
        onDispose { runCatching { imm?.hideSoftInputFromWindow(view.windowToken, 0) } }
    }
    // 62 percent (false) or the full screen (true).
    var expanded by remember { mutableStateOf(false) }
    // 0 = 62 percent, 1 = full screen. It follows the finger while the handle is dragged and springs to either end otherwise.
    val frac = remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, dragging) {
        if (!dragging) {
            animate(
                frac.floatValue, if (expanded) 1f else 0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            ) { v, _ -> frac.floatValue = v }
        }
    }
    // Is the keyboard up (or opening)? It turns false as soon as the keyboard starts going down, so a quick second
    // Back is never wasted. Only the true/false change reaches the composition, not every frame of the animation.
    val imeInsets = WindowInsets.ime
    val statusInsets = WindowInsets.statusBars
    var imeShowing by remember { mutableStateOf(false) }
    LaunchedEffect(imeInsets) {
        var peak = 0
        snapshotFlow { imeInsets.getBottom(density) }.collect { b ->
            if (b == 0) peak = 0 else if (b > peak) peak = b
            imeShowing = b > 0 && b >= peak
        }
    }
    LaunchedEffect(imeShowing) {
        // Keyboard up means full screen. When it goes away (the system takes the first Back for it) the focus goes with it,
        // so the next tap on the field brings the keyboard and the full screen back.
        if (imeShowing) expanded = true else focus.clearFocus()
    }
    val closePanel = { hideKeyboard(); onDismiss() }
    val openSettings = { hideKeyboard(); onOpenSettings() }
    // One step back at a time, like ChatGPT and Claude: keyboard away, then full screen down to 62 percent,
    // then the panel closes and the PDF is back. A tap on the dim area above the panel does the same.
    fun goBack() {
        when {
            imeShowing -> hideKeyboard()
            expanded -> { hideKeyboard(); expanded = false }
            else -> closePanel()
        }
    }
    BackHandler(enabled = true) { goBack() }

    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val minPanelPx = with(density) { (if (compact) 220.dp else 280.dp).roundToPx() }
    val statusPx = statusInsets.getTop(density)
    var screenPx by remember { mutableIntStateOf(0) }
    // The handle and the header follow the finger. Letting go snaps to the nearest end, and a quick flick decides it.
    val dragState = rememberDraggableState { delta ->
        val range = (screenPx - statusPx) - screenPx * 0.62f
        if (range > 1f) frac.floatValue = (frac.floatValue - delta / range).coerceIn(0f, 1f)
    }
    val dragHandleModifier = Modifier.draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        onDragStarted = { dragging = true; if (imeShowing) hideKeyboard() },
        onDragStopped = { v ->
            val up = when { v < -700f -> true; v > 700f -> false; else -> frac.floatValue > 0.5f }
            dragging = false
            expanded = up
            if (!up) hideKeyboard()
        },
    )
    // The panel is 62 percent of the screen, up to the full screen below the status bar, and never taller than the room above
    // the keyboard. It is worked out while measuring, so the animation never recomposes the chat.
    val panelSizing = Modifier.fillMaxWidth().layout { measurable, constraints ->
        val screen = constraints.maxHeight
        // The parent Box applies imePadding(), so these constraints already end above the keyboard.
        // Do not subtract or offset by the IME inset again: doing so leaves a second keyboard-sized gap.
        val roomNoIme = (screen - statusInsets.getTop(this)).coerceAtLeast(0)
        val minPx = min(minPanelPx, roomNoIme)
        val half = max(min((screen * 0.62f).roundToInt(), roomNoIme), minPx)
        val wanted = half + ((roomNoIme - half) * frac.floatValue).roundToInt()
        val h = max(min(wanted, roomNoIme), minPx)
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val none = remember { MutableInteractionSource() }
    val none2 = remember { MutableInteractionSource() }

    // Reserve the IME area once at the root. The panel is measured in the remaining space,
    // so its bottom edge sits directly above the keyboard without a second manual offset.
    Box(
        Modifier.fillMaxSize().imePadding().onSizeChanged { screenPx = it.height },
        contentAlignment = Alignment.BottomCenter,
    ) {
        // The dim layer behind the panel. It fades as the panel grows. A tap on it is one step back.
        AnimatedVisibility(visibleState = visible, enter = fadeIn()) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = 1f - 0.7f * frac.floatValue }
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f))
                    .clickable(interactionSource = none, indication = null) { goBack() }
            )
        }
        run {
            AnimatedVisibility(visibleState = visible, enter = slideInVertically { it }) {
                Surface(
                    modifier = panelSizing.clickable(interactionSource = none2, indication = null) { },
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 12.dp,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime)).padding(horizontal = AppDimens.space16)) {
                            // Handle: drag up for full screen, drag down to bring it back
                            Box(Modifier.fillMaxWidth().then(dragHandleModifier).padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.width(36.dp).height(4.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                )
                            }
                            // Header: title on the left, New chat and Close on the right
                            Row(Modifier.fillMaxWidth().then(dragHandleModifier), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.Sparkle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.size(AppDimens.space8))
                                    Text(
                                        stringResource(R.string.ai_title),
                                        style = AppText.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.semantics { heading() },
                                    )
                                }
                                if (hasKey) {
                                    ScopeDropdown(state = state, totalPages = totalPages, onTick = { haptics.tick() })
                                    IconButton(onClick = { state.newChat() }) {
                                        Icon(AppIcons.NewChat, stringResource(R.string.ai_new_chat))
                                    }
                                }
                                IconButton(onClick = closePanel) { Icon(AppIcons.Close, stringResource(R.string.common_close)) }
                            }
                            if (!hasKey) {
                                NoKey(onAddKey = openSettings, onGetKey = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                })
                            } else {
                                ScopeDetails(state, totalPages, onTick = { haptics.tick() }, onPickPage = { hideKeyboard(); pickPage = true })
                                Spacer(Modifier.height(AppDimens.space8))
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    if (state.exchanges.isEmpty()) SmallEmpty(state)
                                    else Chat(state, layoutKey = (if (expanded) 2 else 0) + (if (imeShowing) 1 else 0), onUserScroll = { if (imeShowing) hideKeyboard() }, onPage = onGoToPage, onCopy = { copy(it) }, onShare = { share(it) }, onRedo = { redo(it) })
                                }
                                Spacer(Modifier.height(AppDimens.space8))
                                InputRow(state, onSend = { send(state.input.trim(), state.input.trim()); hideKeyboard() }, onStop = { state.stop() }, onFocus = { expanded = true })
                                Spacer(Modifier.height(AppDimens.space12))
                            }
                        }
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
                    }
                }
            }
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
                if (state.kind == 1) R.string.ai_empty_whole else R.string.ai_empty_page
            ),
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Scope pill in the header beside New chat. It shows the current scope and opens a small two-choice menu. */
@Composable
private fun ScopeDropdown(state: AskAiState, totalPages: Int, onTick: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val whole = state.kind == 1
    val label = if (whole) stringResource(R.string.ai_scope_whole)
    else stringResource(R.string.ai_tag_page, state.page.coerceIn(1, totalPages.coerceAtLeast(1)))
    val arrow by animateFloatAsState(if (open) 180f else 0f, label = "scopeArrow")

    Box {
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(role = Role.DropdownList) { open = true }
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Icon(
                AppIcons.ExpandMore, null,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = arrow },
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            listOf(
                Triple(0, R.string.ai_scope_page, R.string.ai_scope_page_hint),
                Triple(1, R.string.ai_scope_whole, R.string.ai_scope_whole_hint),
            ).forEach { (kind, title, hint) ->
                val selected = state.kind == kind
                DropdownMenuItem(
                    modifier = Modifier.widthIn(min = 220.dp),
                    text = {
                        Column {
                            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(
                                stringResource(hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    trailingIcon = {
                        if (selected) Icon(AppIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = {
                        if (!selected) { onTick(); state.kind = kind }
                        open = false
                    },
                )
            }
        }
    }
}

/** Slim page stepper, shown only for This page. One small centered pill instead of a tall row. */
@Composable
private fun ScopeDetails(state: AskAiState, totalPages: Int, onTick: () -> Unit, onPickPage: () -> Unit) {
    if (state.kind != 0) return
    Box(Modifier.fillMaxWidth().padding(top = AppDimens.space4), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onTick(); state.page = (state.page - 1).coerceAtLeast(1) }, enabled = state.page > 1, modifier = Modifier.size(40.dp)) {
                Icon(AppIcons.KeyboardArrowLeft, stringResource(R.string.ai_prev_page), Modifier.size(22.dp))
            }
            Text(
                stringResource(R.string.ai_page_of, state.page.coerceIn(1, totalPages.coerceAtLeast(1)), totalPages),
                Modifier.widthIn(min = 64.dp).height(40.dp)
                    .clickable(role = Role.Button) { onPickPage() }
                    .wrapContentHeight(Alignment.CenterVertically),
                style = MaterialTheme.typography.titleSmall, maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = { onTick(); state.page = (state.page + 1).coerceAtMost(totalPages) }, enabled = state.page < totalPages, modifier = Modifier.size(40.dp)) {
                Icon(AppIcons.KeyboardArrowRight, stringResource(R.string.ai_next_page), Modifier.size(22.dp))
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
    layoutKey: Int,
    onUserScroll: () -> Unit,
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
    // The panel grew or shrank, or the keyboard came or went: the newest text stays in view
    LaunchedEffect(layoutKey) {
        delay(350L)
        if (stuck) listState.scrollBy(1_000_000f)
    }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Dragging the chat puts the keyboard away. Nothing is consumed, so the list scrolls as always.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var total = 0f
                var fired = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    total += event.changes.firstOrNull()?.positionChange()?.y ?: 0f
                    if (!fired && abs(total) > viewConfiguration.touchSlop * 2) { fired = true; onUserScroll() }
                } while (event.changes.any { it.pressed })
            }
        }
    ) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(AppDimens.space24)) {
        items(state.exchanges, key = { it.id }) { ex ->
            Column(verticalArrangement = Arrangement.spacedBy(AppDimens.space12)) {
                // Your message: a soft grey bubble on the right
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier.widthIn(max = 300.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(ex.shown, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp), color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                // The answer: plain text on the page, no box. The actions sit right under it.
                Column(Modifier.fillMaxWidth()) {
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
                                    val tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    IconButton(onClick = { onCopy(partial) }) { Icon(AppIcons.ContentCopy, stringResource(R.string.viewer_copy), Modifier.size(20.dp), tint = tint) }
                                    IconButton(onClick = { onShare(partial) }) { Icon(AppIcons.Share, stringResource(R.string.common_share), Modifier.size(20.dp), tint = tint) }
                                    IconButton(onClick = { onRedo(ex) }, enabled = !state.loading && state.cooldownLeft == 0) { Icon(AppIcons.Refresh, stringResource(R.string.ai_redo), Modifier.size(20.dp), tint = tint) }
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

/** One pill, like ChatGPT and Claude: the text field with the round Send button inside it. Send turns into Stop while an answer is on its way. */
@Composable
private fun InputRow(state: AskAiState, onSend: () -> Unit, onStop: () -> Unit, onFocus: () -> Unit) {
    val loading = state.loading
    val wait = state.cooldownLeft
    val sendLabel = stringResource(if (loading) R.string.ai_stop else R.string.ai_send)
    val canSend = loading || (state.input.isNotBlank() && wait == 0)
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // A plain field with tight padding, so the hint and the typed text sit in the middle of the pill
        Box(Modifier.weight(1f).heightIn(min = 40.dp).padding(vertical = 9.dp), contentAlignment = Alignment.CenterStart) {
            if (state.input.isEmpty()) {
                Text(stringResource(R.string.ai_input), style = MaterialTheme.typography.bodyLarge, color = placeholderColor, maxLines = 1)
            }
            BasicTextField(
                value = state.input,
                onValueChange = { state.input = it },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (canSend) 1f else 0.25f))
                .clickable(enabled = canSend, role = Role.Button) { if (loading) onStop() else onSend() },
            contentAlignment = Alignment.Center,
        ) {
            if (wait > 0 && !loading) {
                // After a 429 the button shows the seconds left, then turns back into Send
                Text(
                    "$wait", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.surface,
                    maxLines = 1, modifier = Modifier.semantics { contentDescription = sendLabel },
                )
            } else {
                Icon(
                    if (loading) AppIcons.Stop else AppIcons.ArrowForward,
                    sendLabel,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
