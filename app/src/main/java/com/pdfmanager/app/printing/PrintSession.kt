package com.pdfmanager.app.printing

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Where a guided two-sided job is. Plain Ints so they can be stored. */
object SessionPhase {
    const val NONE = 0
    const val PRINTING_1 = 1   // pass 1 (back sides) was sent
    const val READY_2 = 2      // pass 1 finished; waiting for the paper to be turned over
    const val PRINTING_2 = 3   // pass 2 (front sides) was sent
    const val STOPPED_1 = 4    // pass 1 was cancelled or failed
    const val STOPPED_2 = 5    // pass 2 was cancelled or failed

    /** The phase after the print system reports [status] for the job that belongs to [phase]. */
    fun after(phase: Int, status: JobStatus): Int = when (status) {
        JobStatus.COMPLETED -> when (phase) {
            PRINTING_1 -> READY_2
            PRINTING_2 -> NONE
            else -> phase
        }
        JobStatus.FAILED, JobStatus.CANCELLED -> when (phase) {
            PRINTING_1 -> STOPPED_1
            PRINTING_2 -> STOPPED_2
            else -> phase
        }
        JobStatus.WAITING, JobStatus.BLOCKED -> phase
    }

    /** Which pass a resume or restart of [phase] means. */
    fun passOf(phase: Int): DuplexPass = when (phase) {
        PRINTING_2, STOPPED_2, READY_2 -> DuplexPass.SECOND
        else -> DuplexPass.FIRST
    }
}

/**
 * One running or paused guided two-sided print. It is kept on the device (not the PDF content,
 * only its address, name and the chosen settings) so a notification can bring the user back to
 * the right place even after the app was closed.
 */
data class PrintSession(
    val uri: String,
    val name: String,
    val settings: PrintSettings,
    val phase: Int,
    /** Sheets in the pass that was sent last. */
    val passTotal: Int,
    /** True when the 4-page sample is being printed. */
    val test: Boolean
)

object PrintSessionStore {
    private const val FILE = "print-session"
    private const val KEY = "session"

    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow<PrintSession?>(null)
    val state: StateFlow<PrintSession?> get() = _state

    /** Bumped when a notification asks the app to open the print screen. */
    val openTick = MutableStateFlow(0)

    /** True when the notification button "Start pass 2" was tapped. */
    val autoStart = MutableStateFlow(false)

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _state.value = prefs?.getString(KEY, null)?.let { runCatching { decode(it) }.getOrNull() }
    }

    fun current(): PrintSession? = _state.value

    fun begin(session: PrintSession) {
        _state.value = session
        save(session)
    }

    fun setPhase(phase: Int) {
        val s = _state.value ?: return
        if (phase == SessionPhase.NONE) clear() else begin(s.copy(phase = phase))
    }

    fun clear() {
        _state.value = null
        prefs?.edit()?.remove(KEY)?.apply()
    }

    private fun save(s: PrintSession) {
        prefs?.edit()?.putString(KEY, encode(s))?.apply()
    }

    private fun encode(s: PrintSession): String = JSONObject().apply {
        put("uri", s.uri); put("name", s.name); put("phase", s.phase)
        put("passTotal", s.passTotal); put("test", s.test)
        put("settings", encodeSettings(s.settings))
    }.toString()

    private fun decode(text: String): PrintSession {
        val o = JSONObject(text)
        return PrintSession(
            uri = o.getString("uri"), name = o.getString("name"), phase = o.getInt("phase"),
            passTotal = o.getInt("passTotal"), test = o.getBoolean("test"),
            settings = decodeSettings(o.getJSONObject("settings"))
        )
    }

    private fun encodeSettings(s: PrintSettings) = JSONObject().apply {
        put("pageRangeText", s.pageRangeText); put("copies", s.copies); put("paperSizeId", s.paperSizeId)
        put("orientation", s.orientation.name); put("colorMode", s.colorMode.name)
        put("duplexMode", s.duplexMode.name); put("scaleMode", s.scaleMode.name)
        put("reverseOrder", s.reverseOrder); put("manualDuplex", s.manualDuplex.name)
        put("stackStyle", s.stackStyle.name); put("turnSecondPass", s.turnSecondPass)
        put("pagesPerSheet", s.pagesPerSheet); put("quality", s.quality.name)
        put("layout", s.layout.name); put("layoutBorder", s.layoutBorder)
        put("watermarkText", s.watermarkText); put("pageNumbers", s.pageNumbers)
    }

    private fun decodeSettings(o: JSONObject) = PrintSettings(
        pageRangeText = o.getString("pageRangeText"), copies = o.getInt("copies"),
        paperSizeId = o.getString("paperSizeId"),
        orientation = OrientationChoice.valueOf(o.getString("orientation")),
        colorMode = ColorChoice.valueOf(o.getString("colorMode")),
        duplexMode = DuplexChoice.valueOf(o.getString("duplexMode")),
        scaleMode = ScaleChoice.valueOf(o.getString("scaleMode")),
        reverseOrder = o.getBoolean("reverseOrder"),
        manualDuplex = ManualDuplexChoice.valueOf(o.getString("manualDuplex")),
        stackStyle = StackStyle.valueOf(o.getString("stackStyle")),
        turnSecondPass = o.getBoolean("turnSecondPass"),
        pagesPerSheet = o.getInt("pagesPerSheet"), quality = QualityChoice.valueOf(o.getString("quality")),
        layout = LayoutChoice.valueOf(o.getString("layout")), layoutBorder = o.getBoolean("layoutBorder"),
        watermarkText = o.getString("watermarkText"), pageNumbers = o.getBoolean("pageNumbers")
    )
}
