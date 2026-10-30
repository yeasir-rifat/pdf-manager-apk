package com.pdfmanager.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pdfmanager.app.printing.*
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What we know about the PDF chosen for printing. */
private sealed interface PrintSource {
    data object Loading : PrintSource
    data class Ready(val pageCount: Int, val sizeBytes: Long?) : PrintSource
    data object Failed : PrintSource
}

/**
 * Opens the PDF just far enough to learn its page count and size. Returns null if the file
 * can't be read (permission lost, file moved, password-protected or damaged), so the screen
 * can show an error instead of crashing. Always runs off the main thread.
 */
private fun readSource(context: Context, uri: Uri): PrintSource.Ready? = try {
    val pages = context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
        val renderer = PdfRenderer(fd)
        try {
            renderer.pageCount
        } finally {
            renderer.close()
        }
    }?.takeIf { it > 0 }
    val size = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()
    pages?.let { PrintSource.Ready(it, size) }
} catch (e: Exception) {
    null
}

private val PaperSizes = PrintSummary.paperSizes

/** Remembers the last used print options (not the page range, which belongs to one document). */
private object PrintDefaults {
    private const val FILE = "print_defaults"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> SharedPreferencesGet(p: android.content.SharedPreferences, k: String, d: E): E =
        runCatching { enumValueOf<E>(p.getString(k, null) ?: d.name) }.getOrDefault(d)

    fun load(c: Context): PrintSettings {
        val p = prefs(c)
        val d = PrintSettings(duplexMode = DuplexChoice.ONE_SIDED)
        if (!p.contains("paper")) return d
        return PrintSettings(
            copies = p.getInt("copies", d.copies).coerceAtLeast(1),
            paperSizeId = p.getString("paper", d.paperSizeId) ?: d.paperSizeId,
            orientation = SharedPreferencesGet(p, "orientation", d.orientation),
            colorMode = SharedPreferencesGet(p, "color", d.colorMode),
            duplexMode = SharedPreferencesGet(p, "duplex", d.duplexMode),
            scaleMode = SharedPreferencesGet(p, "scale", d.scaleMode),
            reverseOrder = p.getBoolean("reverse", d.reverseOrder),
            manualDuplex = SharedPreferencesGet(p, "manual", d.manualDuplex),
            stackStyle = SharedPreferencesGet(p, "stack", d.stackStyle),
            turnSecondPass = p.getBoolean("turn", d.turnSecondPass),
            pagesPerSheet = p.getInt("perSheet", d.pagesPerSheet),
            quality = SharedPreferencesGet(p, "quality", d.quality),
            layout = SharedPreferencesGet(p, "layout", d.layout),
            layoutBorder = p.getBoolean("border", d.layoutBorder),
            watermarkText = p.getString("watermark", "") ?: "",
            pageNumbers = p.getBoolean("numbers", d.pageNumbers)
        )
    }

    fun save(c: Context, s: PrintSettings) {
        prefs(c).edit()
            .putInt("copies", s.copies).putString("paper", s.paperSizeId)
            .putString("orientation", s.orientation.name).putString("color", s.colorMode.name)
            .putString("duplex", s.duplexMode.name).putString("scale", s.scaleMode.name)
            .putBoolean("reverse", s.reverseOrder).putString("manual", s.manualDuplex.name)
            .putString("stack", s.stackStyle.name).putBoolean("turn", s.turnSecondPass)
            .putInt("perSheet", s.pagesPerSheet).putString("quality", s.quality.name)
            .putString("layout", s.layout.name).putBoolean("border", s.layoutBorder)
            .putString("watermark", s.watermarkText).putBoolean("numbers", s.pageNumbers)
            .apply()
    }

    fun section(c: Context, key: String, default: Boolean) = prefs(c).getBoolean("open_$key", default)
    fun saveSection(c: Context, key: String, value: Boolean) { prefs(c).edit().putBoolean("open_$key", value).apply() }
}


private val SettingsSaver = listSaver<PrintSettings, Any>(
    save = {
        listOf(
            it.pageRangeText, it.copies, it.paperSizeId, it.orientation.name, it.colorMode.name,
            it.duplexMode.name, it.scaleMode.name, it.reverseOrder, it.pagesPerSheet, it.quality.name,
            it.layout.name, it.layoutBorder, it.watermarkText, it.pageNumbers, it.manualDuplex.name,
            it.stackStyle.name, it.turnSecondPass
        )
    },
    restore = {
        PrintSettings(
            pageRangeText = it[0] as String,
            copies = it[1] as Int,
            paperSizeId = it[2] as String,
            orientation = OrientationChoice.valueOf(it[3] as String),
            colorMode = ColorChoice.valueOf(it[4] as String),
            duplexMode = DuplexChoice.valueOf(it[5] as String),
            scaleMode = ScaleChoice.valueOf(it[6] as String),
            reverseOrder = it[7] as Boolean,
            pagesPerSheet = it[8] as Int,
            quality = QualityChoice.valueOf(it[9] as String),
            layout = LayoutChoice.valueOf(it[10] as String),
            layoutBorder = it[11] as Boolean,
            watermarkText = it[12] as String,
            pageNumbers = it[13] as Boolean,
            manualDuplex = ManualDuplexChoice.valueOf(it[14] as String),
            stackStyle = StackStyle.valueOf(it[15] as String),
            turnSecondPass = it[16] as Boolean
        )
    }
)

private fun summaryOf(s: PrintSettings, pages: Int?): String = PrintSummary.of(PrintTransform.withAutoOrder(s), pages)

/**
 * Print Properties screen. Shows the PDF, lets the user choose print options and checks them.
 * "Continue to system print" opens the Android print screen. Settings are passed to
 * [onContinue] if given. Paper size, orientation, color and sides are sent to the print screen
 * as starting values (see PrintAttributesMapper).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintPropertiesScreen(
    uriString: String,
    displayName: String,
    onBack: () -> Unit,
    onContinue: ((PrintSettings) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val name = displayName.ifBlank { "Untitled.pdf" }

    var source by remember(uriString) { mutableStateOf<PrintSource>(PrintSource.Loading) }
    LaunchedEffect(uriString) {
        val result = withContext(Dispatchers.IO) { readSource(context, Uri.parse(uriString)) }
        source = result ?: PrintSource.Failed
    }

    var settings by rememberSaveable(uriString, stateSaver = SettingsSaver) {
        // A running guided job keeps its settings, so pass 2 and resume use the same ones.
        mutableStateOf(PrintSessionStore.current()?.takeIf { it.uri == uriString }?.settings ?: PrintDefaults.load(context))
    }
    // Remember the options for next time
    LaunchedEffect(settings) { PrintDefaults.save(context, settings) }
    // Which kind of printer (most printers are non-duplex, so that is the starting choice): decides whether Auto sides (duplex) or Manual Sides (non-duplex) can be used.
    var nonDuplex by rememberSaveable(uriString) { mutableStateOf(settings.manualDuplex == ManualDuplexChoice.ON || settings.duplexMode == DuplexChoice.ONE_SIDED) }
    var generalOpen by rememberSaveable { mutableStateOf(PrintDefaults.section(context, "general", true)) }
    LaunchedEffect(generalOpen) { PrintDefaults.saveSection(context, "general", generalOpen) }
    var paperOpen by rememberSaveable { mutableStateOf(PrintDefaults.section(context, "paper", true)) }
    LaunchedEffect(paperOpen) { PrintDefaults.saveSection(context, "paper", paperOpen) }
    var colorOpen by rememberSaveable { mutableStateOf(PrintDefaults.section(context, "color", true)) }
    LaunchedEffect(colorOpen) { PrintDefaults.saveSection(context, "color", colorOpen) }
    var layoutOpen by rememberSaveable { mutableStateOf(PrintDefaults.section(context, "layout", false)) }
    LaunchedEffect(layoutOpen) { PrintDefaults.saveSection(context, "layout", layoutOpen) }
    var preparing by remember { mutableStateOf(false) }
    // Guided two-sided printing is stored in PrintSessionStore, so it survives leaving this screen
    // or the app. The background service updates it as the print job moves on.
    val sessionNow by PrintSessionStore.state.collectAsState()
    val mySession = sessionNow?.takeIf { it.uri == uriString }
    val autoStart by PrintSessionStore.autoStart.collectAsState()
    var resumeOpen by rememberSaveable { mutableStateOf(false) }
    var resumeText by rememberSaveable { mutableStateOf("") }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val afterPermission = remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Printing works without the permission; only the notifications are hidden then.
        afterPermission.value?.invoke()
        afterPermission.value = null
    }
    fun withNotificationPermission(go: () -> Unit) {
        val needs = Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) {
            askedNotifications = true
            afterPermission.value = go
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else go()
    }

    val ready = source as? PrintSource.Ready
    val range = ready?.let { PageRangeParser.parse(settings.pageRangeText, it.pageCount) }
    val validRange = range as? PageRangeResult.Valid
    val rangeError = (range as? PageRangeResult.Invalid)?.message
    val watermarkError = PrintTransform.watermarkError(settings.watermarkText)
    val canPrint = ready != null && validRange != null && watermarkError == null && !preparing
    val pagesChosen = validRange?.pages?.takeUnless { validRange.isAll }

    // Shows a start error, or hands the job to the background service, which keeps following it
    // (with a notification and a Cancel button) even if this screen or the app is closed.
    fun handleLaunch(result: PrintLaunch, label: String, guided: Boolean) {
        when (result) {
            is PrintLaunch.Failed -> scope.launch { snackbar.showSnackbar(result.message) }
            is PrintLaunch.Started -> result.job?.let { job ->
                PrintJobRegistry.job = job
                PrintJobRegistry.label = label
                PrintJobRegistry.guided = guided
                PrintWatchService.start(context)
            }
        }
    }

    fun startPrint(pageCount: Int, pass: DuplexPass? = null, skip: Int = 0, test: Boolean = false) {
        val settings = PrintTransform.withAutoOrder(settings)
        val uri = Uri.parse(uriString)
        if (!PrintTransform.needsPreprocessing(settings)) {
            PrintSessionStore.clear()
            val result = PrintLauncher.start(
                context, uri, name, pageCount, PrintAttributesMapper.build(settings), selectedPages = pagesChosen
            )
            handleLaunch(result, name, false)
            return
        }
        // Pages per sheet, booklet, watermark, page numbers, reverse or manual two-sided:
        // build a temporary copy first.
        val guided = settings.manualDuplex == ManualDuplexChoice.ON
        val thisPass = if (guided) (pass ?: DuplexPass.FIRST) else null
        preparing = true
        val sampleRun = guided && test
        val savedSettings = settings
        // The 4-page sample is plain: no layout or marks, so only the paper handling is tested.
        val chosenSettings = if (sampleRun) {
            settings.copy(layout = LayoutChoice.NORMAL, watermarkText = "", pageNumbers = false)
        } else settings
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (sampleRun) {
                        val sample = PrintPreprocessor.makeTestPdf(context)
                        try {
                            PrintPreprocessor.prepare(context, Uri.fromFile(sample), null, chosenSettings, thisPass, skip)
                        } finally {
                            sample.delete()
                        }
                    } else {
                        PrintPreprocessor.prepare(context, uri, pagesChosen, chosenSettings, thisPass, skip)
                    }
                }
            }
            preparing = false
            val prepared = outcome.getOrNull()
            if (prepared == null) {
                val message = when (outcome.exceptionOrNull()) {
                    is InvalidPasswordException -> "This PDF is password protected. Unlock it in Tools first."
                    is OutOfMemoryError -> "This PDF is too large to prepare with these options"
                    else -> "Could not prepare the PDF for printing"
                }
                snackbar.showSnackbar(message)
            } else {
                val label = if (sampleRun) "Two-sided test" else name
                val result = PrintLauncher.start(
                    context, prepared.uri, label, prepared.pageCount,
                    PrintAttributesMapper.build(PrintTransform.adjustForOutput(chosenSettings)),
                    selectedPages = null, cleanupFile = prepared.file
                )
                if (guided && result is PrintLaunch.Started) {
                    PrintSessionStore.begin(
                        PrintSession(
                            uri = uriString, name = name, settings = savedSettings,
                            phase = if (thisPass == DuplexPass.SECOND) SessionPhase.PRINTING_2 else SessionPhase.PRINTING_1,
                            passTotal = prepared.passTotal, test = sampleRun
                        )
                    )
                } else if (!guided) {
                    PrintSessionStore.clear()
                }
                handleLaunch(result, label, guided)
            }
        }
    }

    // "Print step 2" tapped in the notification: start it as soon as this screen is ready.
    LaunchedEffect(autoStart, ready, mySession?.phase) {
        val s = mySession
        if (autoStart && ready != null && s != null && s.phase == SessionPhase.READY_2 && !preparing) {
            PrintSessionStore.autoStart.value = false
            startPrint(ready.pageCount, DuplexPass.SECOND, 0, s.test)
        }
    }

    // Guided two-sided printing: what to do between the two passes, and how to resume after a stop.
    var hideDialog by rememberSaveable(mySession?.phase) { mutableStateOf(false) }
    if (mySession != null && !hideDialog) {
        val phase = mySession.phase
        val stopped = phase == SessionPhase.STOPPED_1 || phase == SessionPhase.STOPPED_2
        val currentPass = SessionPhase.passOf(phase)
        val pageCountNow = ready?.pageCount ?: 0
        val title = when (phase) {
            SessionPhase.PRINTING_1 -> "Step 1 of 2: back sides"
            SessionPhase.READY_2 -> "Step 1 finished"
            SessionPhase.PRINTING_2 -> "Step 2 of 2: front sides"
            else -> "Printing stopped"
        }
        val message = when (phase) {
            SessionPhase.PRINTING_1 -> "Printing ${mySession.passTotal} sheets. When done, turn the paper over, put it back, then tap \"Print step 2\"."
            SessionPhase.READY_2 -> "Turn the paper over, put it back, then tap \"Print step 2\"."
            SessionPhase.PRINTING_2 -> "Printing the front sides on the same ${mySession.passTotal} sheets."
            else -> "This step stopped."
        }
        val openResume = { resumeText = ""; resumeOpen = true }
        StepDialog(title = title, onDismiss = { hideDialog = true }) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (phase == SessionPhase.PRINTING_2 && mySession.test) {
                Text(
                    "Sheet 1 should show Page 1 and 2, sheet 2 Page 3 and 4, with \"TOP OF PAGE\" at the top on both sides.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                phase == SessionPhase.PRINTING_1 || phase == SessionPhase.READY_2 -> {
                    Button(
                        onClick = { startPrint(pageCountNow, DuplexPass.SECOND, 0, mySession.test) },
                        enabled = !preparing && pageCountNow > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Print step 2") }
                    OutlinedButton(onClick = openResume, enabled = !preparing, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue from a sheet")
                    }
                    TextButton(onClick = { hideDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Hide") }
                }
                phase == SessionPhase.PRINTING_2 -> {
                    Button(onClick = { PrintSessionStore.clear() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    OutlinedButton(onClick = openResume, enabled = !preparing, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue from a sheet")
                    }
                    TextButton(onClick = { hideDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Hide") }
                }
                else -> {
                    Button(onClick = openResume, enabled = !preparing, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue from a sheet")
                    }
                    OutlinedButton(
                        onClick = { startPrint(pageCountNow, currentPass, 0, mySession.test) },
                        enabled = !preparing && pageCountNow > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Print this step again") }
                    TextButton(onClick = { PrintSessionStore.clear() }, modifier = Modifier.fillMaxWidth()) { Text("Discard") }
                }
            }
        }
        if (resumeOpen) {
            val done = resumeText.toIntOrNull()
            val valid = done != null && done in 0 until mySession.passTotal
            StepDialog(title = "Sheets already printed", onDismiss = { resumeOpen = false }) {
                OutlinedTextField(
                    value = resumeText,
                    onValueChange = { resumeText = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Number of sheets") },
                    isError = resumeText.isNotEmpty() && !valid,
                    supportingText = {
                        Text(
                            if (valid && done != null) {
                                PrintSummary.resumeHint(mySession.settings, if (mySession.test) null else validRange?.pages, currentPass, done)
                            } else "0 to ${(mySession.passTotal - 1).coerceAtLeast(0)}"
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { resumeOpen = false }) { Text("Cancel") }
                    Button(
                        onClick = {
                            resumeOpen = false
                            runCatching { PrintJobRegistry.job?.cancel() }
                            startPrint(pageCountNow, currentPass, done ?: 0, mySession.test)
                        },
                        enabled = valid && !preparing && pageCountNow > 0
                    ) { Text("Continue printing") }
                }
            }
        }
    }

    // Same large title that shrinks while scrolling as the tool screens (see ToolScaffold).
    val topBarScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Print PDF", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = topBarScroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            if (ready != null) {
                Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            summaryOf(settings, validRange?.count),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PrimaryButton(
                            text = when {
                                preparing -> "Preparing PDF..."
                                settings.manualDuplex == ManualDuplexChoice.ON -> "Print step 1 (back sides)"
                                else -> "Continue to system print"
                            },
                            icon = AppIcons.Print,
                            enabled = canPrint,
                            onClick = {
                                if (onContinue != null) onContinue(settings)
                                else withNotificationPermission { startPrint(ready.pageCount, DuplexPass.FIRST, 0, false) }
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        when (val s = source) {
            PrintSource.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp) }

            PrintSource.Failed -> EmptyState(
                icon = AppIcons.ErrorOutline,
                title = "Can't print this PDF",
                message = "The file may have been moved or deleted, or it may be locked or damaged. " +
                    "If it has a password, use Unlock PDF in Tools first.",
                modifier = Modifier.padding(padding)
            )

            is PrintSource.Ready -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. File info and preview
                AppCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PdfThumb(
                            uri = uriString,
                            modifier = Modifier.width(84.dp).height(116.dp),
                            widthPx = 320
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 3)
                            val pages = if (s.pageCount == 1) "1 page" else "${s.pageCount} pages"
                            val size = s.sizeBytes?.let { " \u2022 " + Formatter.formatShortFileSize(context, it) }.orEmpty()
                            Text(
                                pages + size,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val picked = validRange?.count
                            if (picked != null && settings.pageRangeText.isNotBlank()) {
                                Text(
                                    if (picked == 1) "1 page selected" else "$picked pages selected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // 2. Printer
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (!PrintServices.openSettings(context)) {
                                scope.launch { snackbar.showSnackbar("This device has no print settings screen") }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Print services") }
                    OutlinedButton(
                        onClick = { Share.pdfs(context, listOf(uriString)) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Share PDF") }
                }

                // 3. General
                SettingsGroup("General", AppIcons.Description, generalOpen, { generalOpen = !generalOpen }) {
                    OutlinedTextField(
                        value = settings.pageRangeText,
                        onValueChange = { settings = settings.copy(pageRangeText = it.take(80)) },
                        label = { Text("Pages") },
                        placeholder = { Text("All ${s.pageCount} pages") },
                        isError = rangeError != null,
                        supportingText = rangeError?.let { msg -> { Text(msg) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OptionRow("Copies") {
                        CopiesStepper(
                            value = settings.copies,
                            enabled = settings.manualDuplex == ManualDuplexChoice.OFF,
                            onChange = { settings = settings.copy(copies = it) }
                        )
                    }
                }

                // 4. Paper and layout
                SettingsGroup("Paper & layout", AppIcons.Description, paperOpen, { paperOpen = !paperOpen }) {
                    OptionRow("Paper size") {
                        ChipRow(PaperSizes, settings.paperSizeId) { settings = settings.copy(paperSizeId = it) }
                    }
                    OptionRow("Orientation") {
                        ChipRow(
                            listOf(
                                OrientationChoice.AUTO to "Auto",
                                OrientationChoice.PORTRAIT to "Portrait",
                                OrientationChoice.LANDSCAPE to "Landscape"
                            ),
                            settings.orientation
                        ) { settings = settings.copy(orientation = it) }
                    }
                }

                // 5. Color and sides
                SettingsGroup("Color & sides", AppIcons.Palette, colorOpen, { colorOpen = !colorOpen }) {
                    OptionRow("Color") {
                        ChipRow(
                            listOf(
                                ColorChoice.COLOR to "Color",
                                ColorChoice.GRAYSCALE to "Grayscale",
                                ColorChoice.MONOCHROME to "Black & white"
                            ),
                            settings.colorMode
                        ) { settings = settings.copy(colorMode = it) }
                    }
                    OptionRow("Your printer type") {
                        ChipRow(
                            listOf(false to "Duplex", true to "Non-duplex"),
                            nonDuplex
                        ) { nonDuplexChoice ->
                            nonDuplex = nonDuplexChoice
                            settings = if (nonDuplexChoice) {
                                settings.copy(duplexMode = DuplexChoice.ONE_SIDED)
                            } else {
                                settings.copy(manualDuplex = ManualDuplexChoice.OFF, duplexMode = DuplexChoice.DEFAULT)
                            }
                        }
                    }
                    OptionRow("Auto sides (Duplex printer)") {
                        ChipRow(
                            listOf(
                                DuplexChoice.DEFAULT to "Default",
                                DuplexChoice.ONE_SIDED to "One side",
                                DuplexChoice.LONG_EDGE to "Both sides"
                            ),
                            settings.duplexMode,
                            enabled = !nonDuplex
                        ) { settings = settings.copy(duplexMode = it) }
                    }
                    OptionRow("Manual Sides (Non-duplex printer)") {
                        ChipRow(
                            listOf(
                                ManualDuplexChoice.OFF to "One side",
                                ManualDuplexChoice.ON to "Both sides"
                            ),
                            settings.manualDuplex,
                            enabled = nonDuplex
                        ) { settings = settings.copy(manualDuplex = it) }
                    }
                }

                // 6. Layout and marks (Step 9)
                SettingsGroup("Layout & marks", AppIcons.GridView, layoutOpen, { layoutOpen = !layoutOpen }) {
                    OptionRow("Pages on each sheet") {
                        ChipRow(
                            listOf(
                                LayoutChoice.NORMAL to "One per sheet",
                                LayoutChoice.NUP to "Multiple per sheet",
                                LayoutChoice.BOOKLET to "Booklet"
                            ),
                            settings.layout
                        ) { choice ->
                            val perSheet = if (choice == LayoutChoice.NUP && settings.pagesPerSheet !in PrintTransform.nupChoices) 4 else settings.pagesPerSheet
                            settings = settings.copy(layout = choice, pagesPerSheet = perSheet)
                        }
                    }
                    if (settings.layout == LayoutChoice.NUP) {
                        OptionRow("Pages per sheet") {
                            ChipRow(PrintTransform.nupChoices.map { it to "$it" }, settings.pagesPerSheet) {
                                settings = settings.copy(pagesPerSheet = it)
                            }
                        }
                        OptionRow("Border around pages") {
                            ChipRow(listOf(true to "On", false to "Off"), settings.layoutBorder) {
                                settings = settings.copy(layoutBorder = it)
                            }
                        }
                    }
                    OptionRow("Page numbers") {
                        ChipRow(listOf(false to "Off", true to "On"), settings.pageNumbers) {
                            settings = settings.copy(pageNumbers = it)
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                        contentDescription = if (expanded) "Collapse $title" else "Expand $title"
                    )
                }
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun CopiesStepper(value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = { onChange((value - 1).coerceAtLeast(1)) }, enabled = enabled && value > 1) {
            Icon(AppIcons.Remove, contentDescription = "Fewer copies")
        }
        Text(
            if (enabled) "$value" else "1",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.widthIn(min = 40.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        FilledTonalIconButton(onClick = { onChange((value + 1).coerceAtMost(PrintTransform.MAX_COPIES)) }, enabled = enabled && value < PrintTransform.MAX_COPIES) {
            Icon(AppIcons.Add, contentDescription = "More copies")
        }
    }
}

/** Dialog with the title and text on one left edge and full-width actions below. */
@Composable
private fun StepDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    content()
                }
            )
        }
    }
}
