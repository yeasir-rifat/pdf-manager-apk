package com.pdfmanager.app.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A failed Drive request. [message] is written for the user: it never holds a token, a file name or the
 * server's own text. ToolJob shows it as "Failed: <message>".
 */
class DriveException(
    message: String,
    /** HTTP status, or 0 when there was none (no connection, bad answer). */
    val httpCode: Int = 0,
    /** Google's short reason code, e.g. exportSizeLimitExceeded. */
    val reason: String? = null,
    /** True when the request never got an answer (no internet, timeout, dropped connection). */
    val offline: Boolean = false,
    val retryAfterSeconds: Long? = null,
) : IOException(message) {
    /** True when trying again can help. */
    val retryable: Boolean get() = offline || OfficeHelpers.isRetryable(httpCode, reason)
}

/** Gives the access token. [forceRefresh] is true after Google answered 401: ask for a new one. */
fun interface DriveTokens {
    fun token(forceRefresh: Boolean): String
}

/** Where the requests go. Unit tests point this at a local server. */
class DriveEndpoints(
    val api: String = "https://www.googleapis.com/drive/v3",
    val upload: String = "https://www.googleapis.com/upload/drive/v3",
)

/** A problem on the phone's side (reading the picked file, writing the cache file). It is not a network problem. */
private class LocalIo(val error: IOException) : RuntimeException(error)

/**
 * The four Drive calls the Office-to-PDF tools need, on plain HttpURLConnection and streaming (a big file
 * is never held in memory). No Android classes, so it is covered by ordinary unit tests.
 *
 * Every call tries up to [OfficeHelpers.MAX_ATTEMPTS] times for busy or temporary problems, waits between
 * the tries ([sleeper], [jitter] are replaced in tests), and after a 401 asks once for a new token.
 * Nothing is written to a log.
 */
class DriveClient(
    private val tokens: DriveTokens,
    private val endpoints: DriveEndpoints = DriveEndpoints(),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val jitter: () -> Double = { Math.random() },
    private val apiReadMs: Int = 60_000,
    private val transferReadMs: Int = 180_000,
) {
    companion object {
        /** Property set on every temporary file, so that all of them can be found. */
        const val PROP_ALL = "pdfm"

        /** Property with the random marker of one conversion. */
        const val PROP_JOB = "pdfm_job"

        const val MSG_NO_INTERNET = "No internet connection"
        const val MSG_TIMEOUT = "The connection timed out, try again"
        const val MSG_BAD_PDF = "Google returned an invalid PDF"

        /** An exported file bigger than this is refused (Google itself stops at 10 MB). */
        const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024

        private const val CONNECT_MS = 20_000
        private const val MAX_LIST_PAGES = 20
        private val FILE_ID = Regex("[A-Za-z0-9_-]{5,200}")

        fun isValidFileId(id: String): Boolean = FILE_ID.matches(id)
    }

    // ------------------------------------------------------------ public calls

    /**
     * Uploads an Office file and lets Drive convert it to Docs, Sheets or Slides, then returns the new file's id.
     * The conversion happens inside this request. The Drive name is neutral and the marker is stored in
     * appProperties, so a file whose answer was lost can still be found again.
     * [size] is the exact byte count of the source. [openSource] is called again for every new try.
     */
    fun uploadForConversion(
        kind: OfficeKind, sourceName: String?, size: Long, marker: String, openSource: () -> InputStream,
    ): String {
        require(size > 0L) { "Empty file" }
        require(DriveTempJournal.isValidMarker(marker)) { "Bad marker" }
        val mime = OfficeHelpers.sourceMime(sourceName) ?: throw IllegalStateException("This is not a valid ${kind.label} file")
        val metadata = buildString {
            append("{\"name\":").append(DriveJson.quote(OfficeHelpers.neutralDriveName(marker.take(12))))
            append(",\"mimeType\":").append(DriveJson.quote(kind.googleMime))
            append(",\"appProperties\":{")
            append(DriveJson.quote(PROP_ALL)).append(":\"1\",")
            append(DriveJson.quote(PROP_JOB)).append(':').append(DriveJson.quote(marker))
            append("}}")
        }
        return call(OfficeHelpers.MAX_ATTEMPTS) { token ->
            val session = startSession(token, metadata, mime, size)
            val source = try {
                openSource()
            } catch (e: IOException) {
                throw LocalIo(e)
            }
            source.use { putFile(session, mime, size, it) }
        }
    }

    /** Exports the converted file as PDF into [dest] (replaced if it exists). Returns the number of bytes. */
    fun exportPdf(fileId: String, dest: File): Long {
        requireId(fileId)
        return call(OfficeHelpers.MAX_ATTEMPTS) { token ->
            val url = "${endpoints.api}/files/$fileId/export?mimeType=${enc(OfficeHelpers.PDF_MIME)}"
            val c = open(url, "GET", token, transferReadMs)
            try {
                val code = c.responseCode
                if (code !in 200..299) throw failure(c, code)
                c.inputStream.use { copyNetToFile(it, dest) }
            } finally {
                c.disconnect()
            }
        }
    }

    /** Deletes the file for good (not into the trash). A file that is already gone (404) counts as deleted. */
    fun delete(fileId: String) {
        requireId(fileId)
        call(OfficeHelpers.MAX_ATTEMPTS) { token ->
            val c = open("${endpoints.api}/files/$fileId", "DELETE", token, apiReadMs)
            try {
                val code = c.responseCode
                if (code !in 200..299 && code != 404) throw failure(c, code)
            } finally {
                c.disconnect()
            }
        }
    }

    /**
     * Ids of the temporary files this app made: those of one conversion when [marker] is given, otherwise all.
     * With the drive.file scope Drive only shows the app its own files, so this is safe.
     * [attempts] = 1 makes a single quick try (used right after a failure, so the user is not kept waiting).
     */
    fun findTempFiles(marker: String? = null, attempts: Int = OfficeHelpers.MAX_ATTEMPTS): List<String> {
        val q = if (marker == null) {
            "appProperties has { key='$PROP_ALL' and value='1' }"
        } else {
            require(DriveTempJournal.isValidMarker(marker)) { "Bad marker" }
            "appProperties has { key='$PROP_JOB' and value='$marker' }"
        }
        val ids = ArrayList<String>()
        var pageToken: String? = null
        var pages = 0
        do {
            val token = pageToken
            val url = buildString {
                append(endpoints.api).append("/files?q=").append(enc(q))
                append("&fields=").append(enc("nextPageToken,files(id)"))
                append("&pageSize=100")
                if (token != null) append("&pageToken=").append(enc(token))
            }
            val body = call(attempts) { t ->
                val c = open(url, "GET", t, apiReadMs)
                try {
                    val code = c.responseCode
                    if (code !in 200..299) throw failure(c, code)
                    c.inputStream.use { readCapped(it, 1024 * 1024) }
                } finally {
                    c.disconnect()
                }
            }
            ids += DriveJson.fileIds(body)
            pageToken = DriveJson.stringField(body, "nextPageToken")?.takeIf { it.isNotEmpty() }
            pages++
        } while (pageToken != null && pages < MAX_LIST_PAGES)
        return ids
    }

    // ------------------------------------------------------------ one request with tries

    private fun <T> call(maxAttempts: Int, block: (token: String) -> T): T {
        var attempt = 0
        var refreshed = false
        var force = false
        while (true) {
            attempt++
            val failure: DriveException = try {
                return block(tokens.token(force))
            } catch (e: DriveException) {
                e
            } catch (e: LocalIo) {
                throw e.error
            } catch (e: java.net.SocketTimeoutException) {
                DriveException(MSG_TIMEOUT, offline = true)
            } catch (e: IOException) {
                DriveException(MSG_NO_INTERNET, offline = true)
            }
            if (failure.httpCode == 401 && !refreshed) {
                // The token ran out: one more try with a new one. This does not use up a normal try.
                refreshed = true
                force = true
                attempt--
                continue
            }
            if (!failure.retryable || attempt >= maxAttempts) throw failure
            sleeper(OfficeHelpers.backoffMillis(attempt, failure.retryAfterSeconds, jitter()))
        }
    }

    // ------------------------------------------------------------ HTTP pieces

    private fun requireId(fileId: String) {
        require(isValidFileId(fileId)) { "Bad file id" }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun open(url: String, method: String, token: String?, readMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = CONNECT_MS
        c.readTimeout = readMs
        // A redirect would carry the token to another place, so none is followed
        c.instanceFollowRedirects = false
        c.useCaches = false
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    /** Builds the exception for an answer that was not 2xx. Only Google's short reason code is used. */
    private fun failure(c: HttpURLConnection, code: Int): DriveException {
        val body = runCatching { c.errorStream?.use { readCapped(it, 16 * 1024) } }.getOrNull().orEmpty()
        val reason = DriveJson.errorReason(body)
        return DriveException(
            OfficeHelpers.messageFor(code, reason), code, reason,
            retryAfterSeconds = OfficeHelpers.parseRetryAfter(c.getHeaderField("Retry-After")),
        )
    }

    private fun originOf(url: String): String {
        val u = URL(url)
        val port = if (u.port == -1) u.defaultPort else u.port
        return "${u.protocol}://${u.host.lowercase()}:$port"
    }

    /** Asks Drive for an upload address. Nothing exists in Drive until the file itself is sent. */
    private fun startSession(token: String, metadata: String, mime: String, size: Long): String {
        val c = open("${endpoints.upload}/files?uploadType=resumable&fields=id", "POST", token, apiReadMs)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.setRequestProperty("X-Upload-Content-Type", mime)
            c.setRequestProperty("X-Upload-Content-Length", size.toString())
            val bytes = metadata.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            if (code !in 200..299) throw failure(c, code)
            val location = c.getHeaderField("Location")
            // The upload address must stay on the same server; the file is sent there
            val ok = location != null && runCatching { originOf(location) == originOf(endpoints.upload) }.getOrDefault(false)
            if (!ok) throw DriveException(OfficeHelpers.messageFor(0, null), code)
            return location!!
        } finally {
            c.disconnect()
        }
    }

    /** Sends the file to the upload address. The answer comes after Drive has converted it. Returns the new id. */
    private fun putFile(sessionUrl: String, mime: String, size: Long, source: InputStream): String {
        // The address already identifies the upload, so no token is sent along
        val c = open(sessionUrl, "PUT", null, transferReadMs)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", mime)
            c.setFixedLengthStreamingMode(size)
            c.outputStream.use { copyLocalToNet(source, it, size) }
            val code = c.responseCode
            if (code !in 200..299) throw failure(c, code)
            val body = c.inputStream.use { readCapped(it, 64 * 1024) }
            val id = DriveJson.stringField(body, "id")
            if (id == null || !isValidFileId(id)) throw DriveException(OfficeHelpers.messageFor(0, null), code)
            return id
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------ copying

    private fun copyLocalToNet(src: InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = try {
                src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            } catch (e: IOException) {
                throw LocalIo(e)
            }
            if (n < 0) throw LocalIo(IOException("The file changed while it was being sent"))
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun copyNetToFile(input: InputStream, dest: File): Long {
        val out = try {
            FileOutputStream(dest)
        } catch (e: IOException) {
            throw LocalIo(e)
        }
        return out.use { sink ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_DOWNLOAD_BYTES) throw DriveException(MSG_BAD_PDF)
                try {
                    sink.write(buf, 0, n)
                } catch (e: IOException) {
                    throw LocalIo(e)
                }
            }
            total
        }
    }

    private fun readCapped(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
