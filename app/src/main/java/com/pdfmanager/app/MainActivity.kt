package com.pdfmanager.app

import android.graphics.Color as AndroidColor
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.pdfmanager.app.data.IncomingPdf
import com.pdfmanager.app.data.OfficeKind
import kotlin.concurrent.thread
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pdfmanager.app.printing.PrintSessionStore
import com.pdfmanager.app.printing.PrintWatchService
import com.pdfmanager.app.ui.*
import com.pdfmanager.app.ui.tools.ToolsScreen
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : ComponentActivity() {
    /** The one library state of the app. Created once here and passed explicitly to the screens that need it. */
    private val libraryVm: LibraryViewModel by viewModels()

    /** A PDF sent by another app, already copied into the cache. The screen opens it in the viewer and clears this. */
    private var incomingPdf by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        com.pdfmanager.app.ui.ToolJobText.app = applicationContext
        com.pdfmanager.app.data.PrivacyCleanup.runOnce(applicationContext)
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        PrintSessionStore.init(applicationContext)
        applicationContext.let { app -> thread(name = "cache-cleanup") { runCatching { IncomingPdf.cleanup(app) } } }
        handlePrintIntent(intent)
        // Only for a fresh start: after the system recreates the app the same intent must not open the file again
        if (savedInstanceState == null) handleViewIntent(intent)

        setContent {
            var themeMode by remember { mutableIntStateOf(prefs.getInt("theme", ThemeMode.SYSTEM)) }
            var dynamic by remember { mutableStateOf(prefs.getBoolean("dynamic", true)) }
            var pureBlack by remember { mutableStateOf(PureBlackPrefs.get(applicationContext)) }
            var accent by remember { mutableIntStateOf(AccentPrefs.get(applicationContext)) }
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> systemDark
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark }
                )
                onDispose { }
            }

            PdfManagerTheme(darkTheme = dark, dynamicColor = dynamic, accent = accent, pureBlack = pureBlack) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    // A second tap while a screen is still opening must not stack the same screen twice
                    val open: (String) -> Unit = { nav.navigate("viewer/${Uri.encode(it)}") { launchSingleTop = true } }
                    // A second tap on Back while the screen is closing must not pop one screen too many
                    val back: () -> Unit = {
                        if (nav.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) nav.popBackStack()
                    }
                    // Top-level switch: no new back-stack entries, and each tab keeps its own state
                    val switchTab: (String) -> Unit = { route ->
                        nav.navigate(route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                    // Tools and quick actions may name a main-tab route (e.g. "scan"): treat it as a tab switch
                    val go: (String) -> Unit = { route ->
                        if (MainTab.fromRoute(route) != null) switchTab(route) else nav.navigate(route) { launchSingleTop = true }
                    }
                    // Before the first back-stack entry exists the start destination (Home) is current, so the bar does not pop in a frame late
                    val currentTab = MainTab.fromRoute(
                        nav.currentBackStackEntryAsState().value?.destination?.route ?: MainTab.Home.route
                    )
                    // The bar belongs to the five main tabs only, and steps aside while the keyboard is open
                    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    val showBar = currentTab != null && !keyboardOpen
                    // A print notification asked to open the print screen of the running job.
                    val openTick by PrintSessionStore.openTick.collectAsState()
                    LaunchedEffect(openTick) {
                        if (openTick > 0) {
                            PrintSessionStore.current()?.let {
                                nav.navigate("print-properties/${Uri.encode(it.uri)}?name=${Uri.encode(it.name)}") {
                                    launchSingleTop = true
                                }
                            }
                        }
                    }
                    // A PDF opened from another app ("Open with" / "Share")
                    val incoming = incomingPdf
                    LaunchedEffect(incoming) {
                        if (incoming != null) {
                            nav.navigate("viewer/${Uri.encode(incoming)}") { launchSingleTop = true }
                            incomingPdf = null
                        }
                    }
                    val openPrint: (com.pdfmanager.app.data.PdfEntity) -> Unit = { pdf ->
                        nav.navigate("print-properties/${Uri.encode(pdf.uri)}?name=${Uri.encode(pdf.name)}")
                    }
                    Column(Modifier.fillMaxSize()) {
                    // The bar owns the gesture area, so the screens above it must not add that inset again
                    Box(Modifier.weight(1f).then(if (showBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
                    NavHost(
                        nav, startDestination = MainTab.Home.route,
                        enterTransition = { fadeIn(tween(240)) + slideInHorizontally(tween(280)) { it / 12 } },
                        exitTransition = { fadeOut(tween(160)) },
                        popEnterTransition = { fadeIn(tween(240)) },
                        popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 12 } }
                    ) {
                        // Home and Files are separate screens on the one LibraryViewModel created above
                        composable(MainTab.Home.route) {
                            HomeScreen(
                                onOpen = open,
                                onNavigate = go,
                                onPrint = openPrint,
                                onOpenFiles = { switchTab(MainTab.Files.route) },
                                vm = libraryVm
                            )
                        }
                        composable(MainTab.Files.route) {
                            FilesScreen(onOpen = open, onNavigate = go, onPrint = openPrint, vm = libraryVm)
                        }
                        composable("viewer/{uri}") { entry ->
                            ViewerScreen(
                                uriString = Uri.decode(entry.arguments?.getString("uri").orEmpty()),
                                onBack = back,
                                onPrint = { uri, name ->
                                    nav.navigate("print-properties/${Uri.encode(uri)}?name=${Uri.encode(name)}")
                                },
                                onTool = go
                            )
                        }
                        composable(
                            "print-properties/{uri}?name={name}",
                            arguments = listOf(
                                navArgument("uri") { type = NavType.StringType },
                                navArgument("name") { type = NavType.StringType; defaultValue = "" }
                            )
                        ) { entry ->
                            PrintPropertiesScreen(
                                uriString = Uri.decode(entry.arguments?.getString("uri").orEmpty()),
                                displayName = Uri.decode(entry.arguments?.getString("name").orEmpty()),
                                onBack = back
                            )
                        }
                        composable(MainTab.Tools.route) { ToolsScreen(onNavigate = go) }
                        composable(MainTab.Scanner.route) { ScanScreen(onBack = null, onOpen = open) }
                        composable(MainTab.Settings.route) {
                            SettingsScreen(
                                vm = libraryVm,
                                themeMode = themeMode,
                                onThemeMode = {
                                    themeMode = it
                                    prefs.edit().putInt("theme", it).apply()
                                },
                                pureBlack = pureBlack,
                                onPureBlack = {
                                    pureBlack = it
                                    PureBlackPrefs.set(applicationContext, it)
                                },
                                accent = accent,
                                onAccent = {
                                    accent = it
                                    AccentPrefs.set(applicationContext, it)
                                },
                                dynamicColor = dynamic,
                                onDynamicColor = {
                                    dynamic = it
                                    prefs.edit().putBoolean("dynamic", it).apply()
                                }
                            )
                        }
                        composable("merge") { MergeScreen(back, open) }
                        composable("img2pdf") { ImagesToPdfScreen(back, open) }
                        composable("pdf2img") { PdfToImagesScreen(back) }
                        composable("pdf2text") { PdfToTextScreen(back) }
                        composable("extractimg") { ExtractImagesScreen(back) }
                        composable("text2pdf") { TextToPdfScreen(back, open) }
                        composable("word2pdf") { OfficeToPdfScreen(OfficeKind.WORD, back, open) }
                        composable("excel2pdf") { OfficeToPdfScreen(OfficeKind.EXCEL, back, open) }
                        composable("ppt2pdf") { OfficeToPdfScreen(OfficeKind.POWERPOINT, back, open) }
                        composable("croprotate") { CropRotateScreen(back, open) }
                        composable("lock") { LockUnlockScreen(back, open) }
                        composable("compress") { CompressScreen(back, open) }
                        composable("nup") { NupScreen(back, open) }
                        composable("booklet") { BookletScreen(back, open) }
                        composable("split") { SplitScreen(back, open) }
                        composable("extract") { ExtractDeleteScreen(back, open) }
                        composable("reorder") { ReorderScreen(back, open) }
                        composable("blank") { BlankPageScreen(back, open) }
                        composable("watermark") { WatermarkScreen(back, open) }
                        composable("pagenum") { PageNumbersScreen(back, open) }
                        composable("sign") { SignScreen(back, open) }
                        composable("notes") { HighlightNotesScreen(back, open) }
                        composable("resize") { ResizePaperScreen(back, open) }
                        composable("poster") { PosterScreen(back, open) }
                        composable("metadata") { EditMetadataScreen(back, open) }
                        composable("repair") { RepairScreen(back, open) }
                    }
                    }
                    if (showBar) CustomBottomNavigationBar(selected = currentTab, onSelect = { switchTab(it.route) })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePrintIntent(intent)
        handleViewIntent(intent)
    }

    /** Opens a PDF that another app sent here with "Open with" (VIEW) or "Share" (SEND). */
    private fun handleViewIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            else -> null
        } ?: return
        // Handled once: a later onCreate / onNewIntent with the same intent must not open it again
        intent.action = Intent.ACTION_MAIN
        intent.data = null
        val app = applicationContext
        thread(name = "incoming-pdf") {
            val result = runCatching { IncomingPdf.copyToCache(app, uri) }
            runOnUiThread {
                result.onSuccess { incomingPdf = it }
                    .onFailure {
                        val msg = if (it.message == "Not a PDF file") "Not a PDF file" else "Could not open file"
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private fun handlePrintIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(PrintWatchService.EXTRA_OPEN_PRINT, false) == true) {
            if (intent.getBooleanExtra(PrintWatchService.EXTRA_AUTO_START, false)) {
                PrintSessionStore.autoStart.value = true
            }
            PrintSessionStore.openTick.value = PrintSessionStore.openTick.value + 1
            intent.removeExtra(PrintWatchService.EXTRA_OPEN_PRINT)
            intent.removeExtra(PrintWatchService.EXTRA_AUTO_START)
        }
    }
}
