package com.pdfmanager.app.data

import java.util.Locale

/** The three Office families the app can turn into PDF, with the Google format each one is converted to first. */
enum class OfficeKind(
    /** Tool tag of the output file name (lower-cased when used), e.g. Report_word.pdf. */
    val prefix: String,
    /** What the user sees in messages, e.g. "Word". */
    val label: String,
    /** Google format the upload is converted to (Docs, Sheets or Slides). */
    val googleMime: String,
    /** Modern XML-based extensions (a ZIP file). */
    val modernExt: Set<String>,
    /** Old binary extensions (OLE2 container). */
    val legacyExt: Set<String>,
) {
    WORD(
        "Word", "Word", "application/vnd.google-apps.document",
        setOf("docx"), setOf("doc"),
    ),
    EXCEL(
        "Excel", "Excel", "application/vnd.google-apps.spreadsheet",
        setOf("xlsx"), setOf("xls"),
    ),
    POWERPOINT(
        "Slides", "PowerPoint", "application/vnd.google-apps.presentation",
        setOf("pptx"), setOf("ppt"),
    );

    val allExt: Set<String> get() = modernExt + legacyExt
}

/** What the first bytes of a file say about its container. */
enum class OfficeContainer { MODERN_ZIP, LEGACY_OLE, UNKNOWN }

/**
 * Plain logic of the Office-to-PDF tools (Word, Excel, PowerPoint via the user's own Google Drive).
 * It uses no Android classes, so it is covered by ordinary unit tests.
 */
object OfficeHelpers {

    /** Biggest Office file the app will upload. Google's own limits differ per type, see the plan (L6). */
    const val MAX_FILE_BYTES = 50L * 1024 * 1024

    /** Google refuses to export a PDF larger than this through files.export. */
    const val MAX_EXPORT_BYTES = 10L * 1024 * 1024

    /** How many times a failed request is tried again. */
    const val MAX_ATTEMPTS = 3

    const val PDF_MIME = "application/pdf"
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    private val SOURCE_MIME = mapOf(
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "doc" to "application/msword",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xls" to "application/vnd.ms-excel",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ppt" to "application/vnd.ms-powerpoint",
    )

    /** MIME types for the file picker (OpenDocument) of one kind. */
    fun pickerMimes(kind: OfficeKind): Array<String> =
        kind.allExt.mapNotNull { SOURCE_MIME[it] }.toTypedArray()

    // ------------------------------------------------------------ file checks

    /** Lower-case extension without the dot, or "" when there is none. */
    fun extensionOf(name: String?): String {
        if (name == null || !name.contains('.')) return ""
        return name.substringAfterLast('.').trim().lowercase(Locale.ROOT)
    }

    /** Which kind a file name belongs to, or null when it is none of the three. */
    fun kindOfName(name: String?): OfficeKind? {
        val ext = extensionOf(name)
        return OfficeKind.values().firstOrNull { ext in it.allExt }
    }

    /** Content type to send with the upload, chosen from the extension. */
    fun sourceMime(name: String?): String? = SOURCE_MIME[extensionOf(name)]

    /** Reads the container from the first bytes: "PK" is a ZIP (docx/xlsx/pptx), D0 CF 11 E0 is the old OLE format. */
    fun containerOf(header: ByteArray): OfficeContainer {
        fun b(i: Int) = header[i].toInt() and 0xFF
        if (header.size >= 4 && b(0) == 0x50 && b(1) == 0x4B && (b(2) == 0x03 || b(2) == 0x05 || b(2) == 0x07)) {
            return OfficeContainer.MODERN_ZIP
        }
        if (header.size >= 4 && b(0) == 0xD0 && b(1) == 0xCF && b(2) == 0x11 && b(3) == 0xE0) {
            return OfficeContainer.LEGACY_OLE
        }
        return OfficeContainer.UNKNOWN
    }

    /**
     * Checks a picked file before anything is uploaded. Returns the message for the user, or null when the file is fine.
     * The extension and the real content must agree: a renamed photo is refused.
     */
    fun checkFile(kind: OfficeKind, name: String?, size: Long, header: ByteArray): String? {
        val ext = extensionOf(name)
        if (ext !in kind.allExt) return "This is not a valid ${kind.label} file"
        if (size <= 0L) return "This file is empty"
        if (size > MAX_FILE_BYTES) return "File is too large (max ${MAX_FILE_BYTES / (1024 * 1024)} MB)"
        val expected = if (ext in kind.modernExt) OfficeContainer.MODERN_ZIP else OfficeContainer.LEGACY_OLE
        if (containerOf(header) != expected) return "This is not a valid ${kind.label} file"
        return null
    }

    /** A real PDF starts with "%PDF" (a few bytes of junk before it are allowed by the format). */
    fun looksLikePdf(header: ByteArray): Boolean {
        if (header.size < 4) return false
        val limit = minOf(header.size - 3, 1024)
        for (i in 0 until limit) {
            if (header[i] == '%'.code.toByte() && header[i + 1] == 'P'.code.toByte() &&
                header[i + 2] == 'D'.code.toByte() && header[i + 3] == 'F'.code.toByte()
            ) return true
        }
        return false
    }

    // ------------------------------------------------------------ names

    /** Output file name: "<original name>_<kind>.pdf", e.g. Report_word.pdf. A missing name falls back to [fallbackStamp]. */
    fun outputName(kind: OfficeKind, source: String?, fallbackStamp: String): String {
        val base = source?.takeIf { it.isNotBlank() }?.substringBeforeLast('.')?.trim().orEmpty()
        return "${base.ifEmpty { fallbackStamp }}_${kind.prefix.lowercase()}.pdf"
    }

    /** Name used inside the user's Drive: neutral, so the real file name never leaves the phone. */
    fun neutralDriveName(randomPart: String): String = "pdfm-temp-$randomPart"

    /**
     * Text shown when a conversion finished. [pages] is 0 when the page count could not be read.
     * [driveCopyRemoved] false means the temporary Drive copy could not be deleted yet and will be later.
     */
    fun resultMessage(pages: Int, driveCopyRemoved: Boolean): String {
        val head = if (pages > 0) {
            "Created a PDF (${if (pages == 1) "1 page" else "$pages pages"})"
        } else {
            "Done"
        }
        return if (driveCopyRemoved) head else "$head. Drive copy removed later"
    }

    // ------------------------------------------------------------ errors

    /** User-facing message for a failed Google request. Never contains tokens, file names or the server's own text. */
    fun messageFor(httpCode: Int, reason: String?): String {
        val r = reason.orEmpty()
        return when {
            r == "accessNotConfigured" || r == "SERVICE_DISABLED" -> "Drive API is not enabled"
            r == "storageQuotaExceeded" -> "Your Google Drive storage is full"
            r == "exportSizeLimitExceeded" -> "Converted PDF is over 10 MB"
            httpCode == 401 -> "Reconnect your Google account"
            httpCode == 429 || r == "rateLimitExceeded" || r == "userRateLimitExceeded" ||
                r == "dailyLimitExceeded" -> "Google is busy. Try again soon"
            httpCode == 400 -> "Can't convert. Locked or damaged?"
            httpCode == 403 -> "Google denied the request"
            httpCode in 500..599 -> "Google problem. Try again"
            else -> "Google could not finish"
        }
    }

    /** True when trying the same request again can succeed (busy or temporary server problem). */
    fun isRetryable(httpCode: Int, reason: String?): Boolean {
        val r = reason.orEmpty()
        if (r == "storageQuotaExceeded" || r == "exportSizeLimitExceeded" || r == "accessNotConfigured") return false
        if (r == "rateLimitExceeded" || r == "userRateLimitExceeded") return true
        return httpCode == 429 || httpCode in 500..599
    }

    /** Reads a Retry-After header given in seconds. Dates and nonsense give null. */
    fun parseRetryAfter(value: String?): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: return null
        return if (seconds in 0..3600) seconds else null
    }

    /**
     * Wait before attempt number [attempt] (1 = the wait after the first failure).
     * Doubles each time from one second, never above 30 seconds. A Retry-After from the server wins when it is longer.
     * [jitter] is a number from 0.0 to 1.0 that spreads the waits by up to 25 percent (pass Math.random()).
     */
    fun backoffMillis(attempt: Int, retryAfterSeconds: Long? = null, jitter: Double = 0.0): Long {
        val step = (attempt - 1).coerceIn(0, 5)
        val base = minOf(1000L shl step, 30_000L)
        val spread = (base * 0.25 * jitter.coerceIn(0.0, 1.0)).toLong()
        val own = base + spread
        val server = (retryAfterSeconds ?: 0L) * 1000L
        return maxOf(own, minOf(server, 60_000L))
    }
}
