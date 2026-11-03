package com.pdfmanager.app.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Where the requests go. Unit tests point this at a local server. */
class GeminiEndpoints(
    val api: String = "https://generativelanguage.googleapis.com/v1beta",
    /** The Files API upload address. By default it is the api address with /upload in front of the version. */
    val upload: String = api.replace("/v1beta", "/upload/v1beta"),
)

/** What Google answered. Any HTTP code comes back here, errors included. [GeminiErrors] turns it into a short message. */
class GeminiHttpResult(val code: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    val ok: Boolean get() = code in 200..299
}

/**
 * The request never got an answer: no internet, a dropped connection or a timeout.
 * The message is fixed text, so it never holds the key, the URL or the server's own text.
 */
class GeminiNetworkException(val timeout: Boolean) : IOException(if (timeout) "Timed out" else "No internet")

/**
 * One generateContent call on plain HttpURLConnection, like DriveClient. The key goes in the
 * x-goog-api-key header and never in the URL, because URLs end up in logs. Nothing is written to a log.
 *
 * [generate] runs on Dispatchers.IO. Cancelling the coroutine disconnects the socket at once, so a Stop tap
 * does not wait for Google. No retry loops: one call, one answer.
 */
class GeminiClient(
    private val apiKey: String,
    private val endpoints: GeminiEndpoints = GeminiEndpoints(),
    private val connectMs: Int = CONNECT_MS,
    private val readMs: Int = READ_MS,
) {
    companion object {
        const val CONNECT_MS = 20_000
        const val READ_MS = 120_000

        /** Google needs a moment to read a big file. Ask every 2 seconds, give up after about 60. */
        const val POLL_MS = 2_000L
        const val POLL_MAX_MS = 60_000L

        private const val STATE_ACTIVE = "ACTIVE"
        private const val STATE_FAILED = "FAILED"
        private val FILE_NAME = Regex("files/[A-Za-z0-9_-]{1,60}")

        /** An answer bigger than this is cut off. A real answer is far smaller. */
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

        private val MODEL_ID = Regex("[A-Za-z0-9._-]{1,100}")

        /** True when [id] is safe to put in the URL path. Custom model ids come from a text field. */
        fun isValidModelId(id: String): Boolean = MODEL_ID.matches(id)
    }

    /**
     * Sends [body] (from [GeminiRequest]) to [model] and returns the HTTP code and text.
     * Throws [GeminiNetworkException] when there was no answer, IllegalArgumentException for a bad model id or
     * an empty key, and CancellationException when the caller cancels.
     */
    suspend fun generate(model: String, body: String): GeminiHttpResult {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return send(generateUrl(model), "POST", jsonHeaders(), true) { c ->
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
        }
    }

    /**
     * Like [generate], but the body is written by [writeBody] straight into the connection (chunked), so a PDF
     * is encoded while it is sent and never held as one big string. [writeBody] must not close the stream.
     * Besides the exceptions of [generate], whatever [writeBody] throws that is not an IOException
     * (for example [GeminiFileException]) comes out unchanged.
     */
    suspend fun generateStreaming(model: String, writeBody: (OutputStream) -> Unit): GeminiHttpResult =
        send(generateUrl(model), "POST", jsonHeaders(), true) { c ->
            c.setChunkedStreamingMode(0)
            c.outputStream.use { writeBody(it) }
        }

    private fun generateUrl(model: String, stream: Boolean = false): String {
        require(isValidModelId(model)) { "Bad model id" }
        return "${endpoints.api}/models/$model:" + if (stream) "streamGenerateContent?alt=sse" else "generateContent"
    }

    /**
     * Like [generateStreaming], but asks for a streamed answer (`streamGenerateContent?alt=sse`). The data of each
     * event is passed to [onEvent] from a background thread while it arrives, and the returned result has an empty body.
     * A code outside 200..299 is returned with its error body, as with [generate]. A stream that breaks after it began
     * throws [GeminiNetworkException], and the caller keeps what [onEvent] already got.
     */
    suspend fun generateSse(model: String, writeBody: (OutputStream) -> Unit, onEvent: (String) -> Unit): GeminiHttpResult =
        send(generateUrl(model, stream = true), "POST", jsonHeaders() + ("Accept" to "text/event-stream"), true, onEvent) { c ->
            c.setChunkedStreamingMode(0)
            c.outputStream.use { writeBody(it) }
        }

    private fun jsonHeaders() = mapOf(
        "Content-Type" to "application/json; charset=utf-8",
        "Accept" to "application/json",
    )

    /** How long to wait for Google to finish reading an uploaded file. */
    class Poll(val intervalMs: Long = POLL_MS, val maxMs: Long = POLL_MAX_MS)

    /** The file as the Files API knows it. */
    private class RemoteFile(val name: String, val uri: String, val state: String?)

    /**
     * Uploads [file] to the Files API (resumable: start, then one request with the bytes and finalize) and waits until
     * Google says ACTIVE, at most [Poll.maxMs]. [onProgress] gets 0 to 100 while the bytes go out, only when the number
     * changes, from a background thread. The upload address Google returns must be on the same server as the
     * configured upload endpoint, otherwise nothing is sent there. The display name is fixed text, never the file name.
     * Throws [GeminiNetworkException] and CancellationException like [generate].
     */
    suspend fun uploadPdf(file: File, onProgress: (Int) -> Unit = {}, poll: Poll = Poll()): GeminiUpload {
        val size = file.length()
        if (size <= 0L) return GeminiUpload.Failed(GeminiError.File)

        val startBody = "{\"file\":{\"display_name\":\"pdf\"}}".toByteArray(Charsets.UTF_8)
        val start = send(
            "${endpoints.upload}/files", "POST",
            mapOf(
                "X-Goog-Upload-Protocol" to "resumable",
                "X-Goog-Upload-Command" to "start",
                "X-Goog-Upload-Header-Content-Length" to size.toString(),
                "X-Goog-Upload-Header-Content-Type" to "application/pdf",
                "Content-Type" to "application/json; charset=utf-8",
            ),
            true,
        ) { c ->
            c.setFixedLengthStreamingMode(startBody.size)
            c.outputStream.use { it.write(startBody) }
        }
        GeminiErrors.fromHttp(start.code)?.let { return GeminiUpload.Failed(it) }
        val uploadUrl = start.headers["x-goog-upload-url"]
        if (uploadUrl == null || !sameServer(uploadUrl, endpoints.upload)) return GeminiUpload.Failed(GeminiError.Busy)

        onProgress(0)
        var lastPercent = 0
        // The address already carries the upload id, so the key is not sent a second time
        val sent = send(
            uploadUrl, "POST",
            mapOf("X-Goog-Upload-Offset" to "0", "X-Goog-Upload-Command" to "upload, finalize", "Accept" to "application/json"),
            false,
        ) { c ->
            c.setFixedLengthStreamingMode(size)
            c.outputStream.use { out ->
                FileInputStream(file).use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        val pct = (total * 100 / size).toInt().coerceIn(0, 100)
                        if (pct != lastPercent) { lastPercent = pct; onProgress(pct) }
                    }
                }
            }
        }
        GeminiErrors.fromHttp(sent.code)?.let { return GeminiUpload.Failed(it) }
        var remote = parseFile(sent.body) ?: return GeminiUpload.Failed(GeminiError.Busy)
        if (!sameServer(remote.uri, endpoints.api)) return GeminiUpload.Failed(GeminiError.Busy)

        var waited = 0L
        while (true) {
            when (remote.state) {
                null, STATE_ACTIVE -> return GeminiUpload.Done(remote.uri)
                STATE_FAILED -> return GeminiUpload.Failed(GeminiError.File)
            }
            if (waited >= poll.maxMs) return GeminiUpload.Failed(GeminiError.Timeout)
            delay(poll.intervalMs)
            waited += poll.intervalMs.coerceAtLeast(1)
            currentCoroutineContext().ensureActive()
            val r = send("${endpoints.api}/${remote.name}", "GET", mapOf("Accept" to "application/json"), true, writeRequest = null)
            GeminiErrors.fromHttp(r.code)?.let { return GeminiUpload.Failed(it) }
            remote = parseFile(r.body) ?: return GeminiUpload.Failed(GeminiError.Busy)
            if (!sameServer(remote.uri, endpoints.api)) return GeminiUpload.Failed(GeminiError.Busy)
        }
    }

    /** Reads `{"file":{"name":"files/abc","uri":"...","state":"ACTIVE"}}` (or the same object without the wrapper). */
    @Suppress("UNCHECKED_CAST")
    private fun parseFile(body: String): RemoteFile? {
        val root = DriveJson.parseObject(body) ?: return null
        val f = (root["file"] as? Map<String, Any?>) ?: root
        val name = f["name"] as? String ?: return null
        val uri = f["uri"] as? String ?: return null
        if (!FILE_NAME.matches(name) || uri.isBlank()) return null
        return RemoteFile(name, uri, f["state"] as? String)
    }

    private fun sameServer(a: String, b: String): Boolean = try {
        val x = URL(a)
        val y = URL(b)
        x.protocol == y.protocol && x.host.equals(y.host, ignoreCase = true) &&
            (if (x.port == -1) x.defaultPort else x.port) == (if (y.port == -1) y.defaultPort else y.port)
    } catch (e: java.net.MalformedURLException) {
        false
    }

    private suspend fun send(
        url: String,
        method: String,
        headers: Map<String, String>,
        sendKey: Boolean,
        onSse: ((String) -> Unit)? = null,
        writeRequest: ((HttpURLConnection) -> Unit)?,
    ): GeminiHttpResult =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val call = Call()
                cont.invokeOnCancellation { call.cancel() }
                try {
                    val result = call.run(url, method, headers, sendKey, onSse, writeRequest)
                    if (cont.isActive) cont.resume(result)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }

    /** One request. [cancel] may be called from any thread. */
    private inner class Call {
        @Volatile private var connection: HttpURLConnection? = null
        @Volatile private var cancelled = false

        fun cancel() {
            cancelled = true
            connection?.disconnect()
        }

        fun run(
            url: String,
            method: String,
            headers: Map<String, String>,
            sendKey: Boolean,
            onSse: ((String) -> Unit)?,
            writeRequest: ((HttpURLConnection) -> Unit)?,
        ): GeminiHttpResult {
            require(apiKey.isNotBlank()) { "No API key" }
            val c = URL(url).openConnection() as HttpURLConnection
            connection = c
            if (cancelled) c.disconnect()
            try {
                c.requestMethod = method
                c.connectTimeout = connectMs
                c.readTimeout = readMs
                c.instanceFollowRedirects = false
                c.doOutput = writeRequest != null
                if (sendKey) c.setRequestProperty("x-goog-api-key", apiKey)
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                writeRequest?.invoke(c)
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = if (code in 200..299 && onSse != null) {
                    // A streamed answer: each event goes to the caller while it arrives
                    stream?.use { GeminiSse.read(it, onSse) }
                    ""
                } else {
                    stream?.use { readCapped(it) } ?: ""
                }
                val seen = HashMap<String, String>()
                for ((k, v) in c.headerFields) if (k != null && v.isNotEmpty()) seen[k.lowercase()] = v.first()
                return GeminiHttpResult(code, text, seen)
            } catch (e: SocketTimeoutException) {
                throw GeminiNetworkException(timeout = true)
            } catch (e: IOException) {
                // A disconnect from cancel() also lands here. The caller has already stopped listening.
                throw GeminiNetworkException(timeout = false)
            } finally {
                c.disconnect()
            }
        }
    }

    private fun readCapped(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < MAX_RESPONSE_BYTES) {
            val n = input.read(buf, 0, minOf(buf.size, MAX_RESPONSE_BYTES - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}

/** What an upload came to: the address to use in a request, or one short error. */
sealed interface GeminiUpload {
    class Done(val fileUri: String) : GeminiUpload
    class Failed(val error: GeminiError) : GeminiUpload
}
