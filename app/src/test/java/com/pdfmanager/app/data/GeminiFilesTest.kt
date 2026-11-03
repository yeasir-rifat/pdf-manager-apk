package com.pdfmanager.app.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiFilesTest {

    private var server: FakeDriveServer? = null
    private val dir: File = Files.createTempDirectory("ai-files").toFile()

    @After fun stop() { server?.stop(); dir.deleteRecursively() }

    private lateinit var srv: FakeDriveServer
    private val bytes = ByteArray(300_000) { (it % 251).toByte() }
    private val pdf = File(dir, "big.pdf").also { it.writeBytes(bytes) }
    private val fast = GeminiClient.Poll(intervalMs = 5, maxMs = 500)

    private fun fileJson(state: String?, name: String = "files/abc123") =
        """{"file":{"name":"$name","uri":"${srv.base}/v1beta/$name"${if (state != null) ",\"state\":\"$state\"" else ""}}}"""

    private fun serve(
        startReply: () -> FakeReply = { FakeReply(200, "", mapOf("X-Goog-Upload-URL" to "${srv.base}/upload/v1beta/files/session1")) },
        uploadReply: () -> FakeReply = { FakeReply(200, fileJson("ACTIVE")) },
        pollReply: () -> FakeReply = { FakeReply(200, fileJson("ACTIVE")) },
    ): FakeDriveServer {
        srv = FakeDriveServer { r ->
            when {
                r.method == "POST" && r.path == "/upload/v1beta/files" -> startReply()
                r.method == "POST" && r.path == "/upload/v1beta/files/session1" -> uploadReply()
                r.method == "GET" && r.path.startsWith("/v1beta/files/") -> pollReply()
                else -> FakeReply(404)
            }
        }
        server = srv
        return srv
    }

    private fun client(s: FakeDriveServer, key: String = "k-1234") = GeminiClient(key, GeminiEndpoints("${s.base}/v1beta"))

    private fun upload(s: FakeDriveServer, onProgress: (Int) -> Unit = {}, poll: GeminiClient.Poll = fast) =
        runBlocking { client(s).uploadPdf(pdf, onProgress, poll) }

    @Test fun endpointsDeriveTheUploadAddress() {
        assertEquals("https://generativelanguage.googleapis.com/upload/v1beta", GeminiEndpoints().upload)
        assertEquals("http://x/upload/v1beta", GeminiEndpoints("http://x/v1beta").upload)
    }

    @Test fun headerSequenceOfTheResumableUpload() {
        val s = serve()
        val r = upload(s)
        assertEquals("${s.base}/v1beta/files/abc123", (r as GeminiUpload.Done).fileUri)
        assertEquals(2, s.requests.size)
        val start = s.requests[0]
        assertEquals("/upload/v1beta/files", start.path)
        assertEquals("k-1234", start.headers["x-goog-api-key"])
        assertEquals("resumable", start.headers["x-goog-upload-protocol"])
        assertEquals("start", start.headers["x-goog-upload-command"])
        assertEquals(bytes.size.toString(), start.headers["x-goog-upload-header-content-length"])
        assertEquals("application/pdf", start.headers["x-goog-upload-header-content-type"])
        val meta = DriveJson.parseObject(String(start.body, Charsets.UTF_8))!!
        @Suppress("UNCHECKED_CAST")
        assertEquals("pdf", (meta["file"] as Map<String, Any?>)["display_name"])
        val send = s.requests[1]
        assertEquals("0", send.headers["x-goog-upload-offset"])
        assertEquals("upload, finalize", send.headers["x-goog-upload-command"])
        assertEquals(bytes.size.toString(), send.headers["content-length"])
        assertArrayEquals(bytes, send.body)
    }

    @Test fun theKeyNeverGoesInAnyUrl() {
        val s = serve()
        upload(s)
        for (r in s.requests) assertFalse((r.path + (r.query ?: "")).contains("k-1234"))
    }

    @Test fun fileNameIsNeverSent() {
        val s = serve()
        upload(s)
        for (r in s.requests) assertFalse(String(r.body, Charsets.ISO_8859_1).contains("big.pdf"))
    }

    @Test fun progressRunsFromZeroTo100WithoutGoingBack() {
        val s = serve()
        val seen = ArrayList<Int>()
        upload(s, { synchronized(seen) { seen += it } })
        assertEquals(0, seen.first())
        assertEquals(100, seen.last())
        assertEquals(seen.sorted(), seen)
        assertEquals(seen.distinct(), seen)
    }

    @Test fun stateMissingCountsAsReady() {
        val s = serve(uploadReply = { FakeReply(200, fileJson(null)) })
        assertTrue(upload(s) is GeminiUpload.Done)
        assertEquals(2, s.requests.size)
    }

    @Test fun processingThenActivePollsUntilReady() {
        val polls = AtomicInteger()
        val s = serve(
            uploadReply = { FakeReply(200, fileJson("PROCESSING")) },
            pollReply = { if (polls.incrementAndGet() < 3) FakeReply(200, fileJson("PROCESSING")) else FakeReply(200, fileJson("ACTIVE")) },
        )
        val r = upload(s)
        assertTrue(r is GeminiUpload.Done)
        assertEquals(3, polls.get())
        val poll = s.requests.last()
        assertEquals("GET", poll.method)
        assertEquals("/v1beta/files/abc123", poll.path)
        assertEquals("k-1234", poll.headers["x-goog-api-key"])
    }

    @Test fun processingForeverTimesOut() {
        val s = serve(uploadReply = { FakeReply(200, fileJson("PROCESSING")) }, pollReply = { FakeReply(200, fileJson("PROCESSING")) })
        val r = upload(s, poll = GeminiClient.Poll(intervalMs = 5, maxMs = 30))
        assertEquals(GeminiError.Timeout, (r as GeminiUpload.Failed).error)
        assertTrue(s.requests.size <= 2 + 7)
    }

    @Test fun failedStateIsAFileError() {
        val s = serve(uploadReply = { FakeReply(200, fileJson("FAILED")) })
        assertEquals(GeminiError.File, (upload(s) as GeminiUpload.Failed).error)
        val s2 = serve(uploadReply = { FakeReply(200, fileJson("PROCESSING")) }, pollReply = { FakeReply(200, fileJson("FAILED")) })
        assertEquals(GeminiError.File, (upload(s2) as GeminiUpload.Failed).error)
    }

    @Test fun startErrorsUseTheShortMessages() {
        for ((code, err) in listOf(400 to GeminiError.Key, 403 to GeminiError.Denied, 429 to GeminiError.Limit, 503 to GeminiError.Busy)) {
            val s = serve(startReply = { FakeReply(code, """{"error":{"message":"secret k-1234"}}""") })
            assertEquals("code $code", err, (upload(s) as GeminiUpload.Failed).error)
            assertEquals(1, s.requests.size)
        }
    }

    @Test fun uploadErrorsUseTheShortMessages() {
        val s = serve(uploadReply = { FakeReply(429) })
        assertEquals(GeminiError.Limit, (upload(s) as GeminiUpload.Failed).error)
    }

    @Test fun missingUploadAddressIsRefused() {
        val s = serve(startReply = { FakeReply(200) })
        assertEquals(GeminiError.Busy, (upload(s) as GeminiUpload.Failed).error)
        assertEquals(1, s.requests.size)
    }

    @Test fun uploadAddressOnAnotherServerIsNeverUsed() {
        val s = serve(startReply = { FakeReply(200, "", mapOf("X-Goog-Upload-URL" to "http://127.0.0.1:1/upload/v1beta/files/evil")) })
        assertEquals(GeminiError.Busy, (upload(s) as GeminiUpload.Failed).error)
        assertEquals(1, s.requests.size)
    }

    @Test fun fileLinkOnAnotherServerIsRefused() {
        val s = serve(uploadReply = { FakeReply(200, """{"file":{"name":"files/abc123","uri":"http://127.0.0.1:1/files/abc123","state":"ACTIVE"}}""") })
        assertEquals(GeminiError.Busy, (upload(s) as GeminiUpload.Failed).error)
    }

    @Test fun oddFileNamesAreRefused() {
        val s = serve(uploadReply = { FakeReply(200, fileJson("PROCESSING", "files/../models/x")) })
        assertEquals(GeminiError.Busy, (upload(s) as GeminiUpload.Failed).error)
    }

    @Test fun answerThatIsNotJsonIsRefused() {
        val s = serve(uploadReply = { FakeReply(200, "<html>") })
        assertEquals(GeminiError.Busy, (upload(s) as GeminiUpload.Failed).error)
    }

    @Test fun emptyFileIsNotUploaded() {
        val s = serve()
        val empty = File(dir, "empty.pdf").apply { writeBytes(ByteArray(0)) }
        val r = runBlocking { client(s).uploadPdf(empty) }
        assertEquals(GeminiError.File, (r as GeminiUpload.Failed).error)
        assertTrue(s.requests.isEmpty())
    }

    @Test fun noConnectionIsANetworkException() {
        val s = serve()
        val c = GeminiClient("k", GeminiEndpoints("http://127.0.0.1:1/v1beta"))
        try {
            runBlocking { c.uploadPdf(pdf) }
            org.junit.Assert.fail("expected an exception")
        } catch (e: GeminiNetworkException) {
            assertFalse(e.timeout)
        }
        assertNull(s.requests.firstOrNull())
    }

    @Test fun remotePartWritesFileDataAndNoBytes() {
        val body = GeminiRequest.body(
            listOf(GeminiRequest.Turn(GeminiRequest.USER, "Q", GeminiRequest.PdfPart.remote("https://x/v1beta/files/abc"))),
            AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL,
        )
        @Suppress("UNCHECKED_CAST")
        val parts = ((DriveJson.parseObject(body)!!["contents"] as List<Map<String, Any?>>)[0]["parts"]) as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val fd = parts[0]["file_data"] as Map<String, Any?>
        assertEquals("application/pdf", fd["mime_type"])
        assertEquals("https://x/v1beta/files/abc", fd["file_uri"])
        assertFalse(parts[0].containsKey("inline_data"))
        assertEquals("Q", parts[1]["text"])
    }
}
