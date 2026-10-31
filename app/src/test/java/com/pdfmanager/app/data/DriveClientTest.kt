package com.pdfmanager.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder

class DriveClientTest {

    @get:Rule val tmp = TemporaryFolder()

    private val docx = ByteArray(5000) { (it % 251).toByte() }
    private val marker = "0123456789abcdef0123456789abcdef"

    private fun upload(c: DriveClient, bytes: ByteArray = docx, open: () -> java.io.InputStream = { ByteArrayInputStream(bytes) }) =
        c.uploadForConversion(OfficeKind.WORD, "Report.docx", bytes.size.toLong(), marker, open)

    private fun failureOf(block: () -> Unit): DriveException {
        try {
            block()
        } catch (e: DriveException) {
            return e
        }
        fail("Expected a DriveException")
        throw IllegalStateException()
    }

    // ------------------------------------------------------------ upload

    @Test fun uploadStartsSessionThenSendsTheFile() {
        lateinit var server: FakeDriveServer
        server = FakeDriveServer { r ->
            if (r.method == "POST") FakeReply(200, headers = mapOf("Location" to "${server.base}/session/1"))
            else FakeReply(200, """{"id":"newfile12345"}""")
        }
        try {
            val parts = TestClientParts()
            assertEquals("newfile12345", upload(parts.client(server)))

            val post = server.requests[0]
            assertEquals("POST", post.method)
            assertEquals("/upload/drive/v3/files", post.path)
            assertTrue(post.query!!.contains("uploadType=resumable"))
            assertEquals("Bearer tok1", post.headers["authorization"])
            assertEquals(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                post.headers["x-upload-content-type"],
            )
            assertEquals("5000", post.headers["x-upload-content-length"])

            val meta = DriveJson.parseObject(String(post.body, Charsets.UTF_8))!!
            assertEquals("application/vnd.google-apps.document", meta["mimeType"])
            assertEquals("pdfm-temp-" + marker.take(12), meta["name"])
            val props = meta["appProperties"] as Map<*, *>
            assertEquals("1", props["pdfm"])
            assertEquals(marker, props["pdfm_job"])
            // The real file name never goes to Drive
            assertFalse(String(post.body, Charsets.UTF_8).contains("Report"))

            val put = server.requests[1]
            assertEquals("PUT", put.method)
            assertEquals("/session/1", put.path)
            assertNull(put.headers["authorization"])
            assertArrayEquals(docx, put.body)
        } finally {
            server.stop()
        }
    }

    @Test fun uploadRefusesAnAddressOnAnotherServer() {
        val server = FakeDriveServer { FakeReply(200, headers = mapOf("Location" to "http://example.invalid/steal")) }
        try {
            val e = failureOf { upload(TestClientParts().client(server)) }
            assertEquals("Google could not finish this request", e.message)
            assertEquals(1, server.requests.size)
        } finally {
            server.stop()
        }
    }

    @Test fun uploadWithoutAnIdInTheAnswerFails() {
        lateinit var server: FakeDriveServer
        server = FakeDriveServer { r ->
            if (r.method == "POST") FakeReply(200, headers = mapOf("Location" to "${server.base}/s"))
            else FakeReply(200, """{"name":"x"}""")
        }
        try {
            failureOf { upload(TestClientParts().client(server)) }
        } finally {
            server.stop()
        }
    }

    @Test fun conversionFailureGives400MessageAndNoRetry() {
        lateinit var server: FakeDriveServer
        server = FakeDriveServer { r ->
            if (r.method == "POST") FakeReply(200, headers = mapOf("Location" to "${server.base}/s"))
            else FakeReply(400, """{"error":{"code":400,"message":"secret file name.docx"}}""")
        }
        try {
            val parts = TestClientParts()
            val e = failureOf { upload(parts.client(server)) }
            assertEquals("Google could not convert this file (locked or damaged?)", e.message)
            assertFalse(e.message!!.contains("secret"))
            assertTrue(parts.sleeps.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test fun storageFullIsReportedAndNotRetried() {
        val server = FakeDriveServer {
            FakeReply(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""")
        }
        try {
            val parts = TestClientParts()
            val e = failureOf { upload(parts.client(server)) }
            assertEquals("Your Google Drive storage is full", e.message)
            assertEquals(1, server.requests.size)
        } finally {
            server.stop()
        }
    }

    @Test fun localReadErrorIsNotShownAsOffline() {
        lateinit var server: FakeDriveServer
        server = FakeDriveServer { r ->
            if (r.method == "POST") FakeReply(200, headers = mapOf("Location" to "${server.base}/s"))
            else FakeReply(200, """{"id":"newfile12345"}""")
        }
        try {
            val parts = TestClientParts()
            try {
                upload(parts.client(server)) { throw IOException("disk broke") }
                fail("Expected an exception")
            } catch (e: DriveException) {
                fail("A local problem must not look like a Google problem")
            } catch (e: IOException) {
                assertEquals("disk broke", e.message)
            }
            assertTrue(parts.sleeps.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test fun fileShorterThanAnnouncedIsALocalError() {
        lateinit var server: FakeDriveServer
        server = FakeDriveServer { r ->
            if (r.method == "POST") FakeReply(200, headers = mapOf("Location" to "${server.base}/s"))
            else FakeReply(200, """{"id":"newfile12345"}""")
        }
        try {
            val parts = TestClientParts()
            try {
                // announces 5000 bytes but the source only has 100
                upload(parts.client(server)) { ByteArrayInputStream(ByteArray(100)) }
                fail("Expected an exception")
            } catch (e: DriveException) {
                fail("A local problem must not look like a Google problem")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("changed"))
            }
        } finally {
            server.stop()
        }
    }

    @Test fun neitherEmptyFilesNorBadMarkersAreSent() {
        val server = FakeDriveServer { FakeReply(500) }
        try {
            val c = TestClientParts().client(server)
            try { c.uploadForConversion(OfficeKind.WORD, "a.docx", 0L, marker) { ByteArrayInputStream(ByteArray(0)) }; fail() }
            catch (e: IllegalArgumentException) { }
            try { c.uploadForConversion(OfficeKind.WORD, "a.docx", 5L, "x' or '1", { ByteArrayInputStream(ByteArray(5)) }); fail() }
            catch (e: IllegalArgumentException) { }
            assertTrue(server.requests.isEmpty())
        } finally {
            server.stop()
        }
    }

    // ------------------------------------------------------------ export

    @Test fun exportWritesThePdfToTheFile() {
        val pdf = "%PDF-1.7\nhello"
        val server = FakeDriveServer { FakeReply(200, pdf) }
        try {
            val out = File(tmp.root, "out.pdf")
            out.writeText("old content that is longer than the new one, to prove it is replaced")
            val n = TestClientParts().client(server).exportPdf("abcde12345", out)
            assertEquals(pdf.length.toLong(), n)
            assertEquals(pdf, out.readText())
            val r = server.requests.single()
            assertEquals("GET", r.method)
            assertEquals("/drive/v3/files/abcde12345/export", r.path)
            assertTrue(r.query!!.contains("mimeType=application%2Fpdf"))
            assertEquals("Bearer tok1", r.headers["authorization"])
        } finally {
            server.stop()
        }
    }

    @Test fun exportTooLargeIsNotRetried() {
        val server = FakeDriveServer {
            FakeReply(403, """{"error":{"errors":[{"reason":"exportSizeLimitExceeded"}]}}""")
        }
        try {
            val parts = TestClientParts()
            val e = failureOf { parts.client(server).exportPdf("abcde12345", File(tmp.root, "o.pdf")) }
            assertEquals("exportSizeLimitExceeded", e.reason)
            assertTrue(e.message!!.contains("10 MB"))
            assertEquals(1, server.requests.size)
            assertTrue(parts.sleeps.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test fun unauthorizedAsksForANewTokenOnce() {
        var calls = 0
        val server = FakeDriveServer { if (++calls == 1) FakeReply(401) else FakeReply(200, "%PDF-ok") }
        try {
            val parts = TestClientParts()
            val out = File(tmp.root, "o.pdf")
            parts.client(server).exportPdf("abcde12345", out)
            assertEquals(listOf(false, true), parts.refreshCalls)
            assertEquals("Bearer tok1", server.requests[0].headers["authorization"])
            assertEquals("Bearer tok2", server.requests[1].headers["authorization"])
            assertEquals("%PDF-ok", out.readText())
            assertTrue(parts.sleeps.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test fun secondUnauthorizedGivesUp() {
        val server = FakeDriveServer { FakeReply(401) }
        try {
            val parts = TestClientParts()
            val e = failureOf { parts.client(server).exportPdf("abcde12345", File(tmp.root, "o.pdf")) }
            assertEquals("Please connect your Google account again", e.message)
            assertEquals(2, server.requests.size)
        } finally {
            server.stop()
        }
    }

    @Test fun rateLimitWaitsForRetryAfterThenSucceeds() {
        var calls = 0
        val server = FakeDriveServer { if (++calls == 1) FakeReply(429, headers = mapOf("Retry-After" to "5")) else FakeReply(200, "%PDF") }
        try {
            val parts = TestClientParts()
            parts.client(server).exportPdf("abcde12345", File(tmp.root, "o.pdf"))
            assertEquals(listOf(5000L), parts.sleeps)
            assertEquals(2, server.requests.size)
        } finally {
            server.stop()
        }
    }

    @Test fun serverErrorIsTriedThreeTimesWithGrowingWaits() {
        val server = FakeDriveServer { FakeReply(503) }
        try {
            val parts = TestClientParts()
            val e = failureOf { parts.client(server).exportPdf("abcde12345", File(tmp.root, "o.pdf")) }
            assertEquals("Google had a problem, try again", e.message)
            assertEquals(3, server.requests.size)
            assertEquals(listOf(1000L, 2000L), parts.sleeps)
        } finally {
            server.stop()
        }
    }

    @Test fun userRateLimitReasonOn403IsRetried() {
        var calls = 0
        val server = FakeDriveServer {
            if (++calls < 3) FakeReply(403, """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}""")
            else FakeReply(200, "%PDF")
        }
        try {
            val parts = TestClientParts()
            parts.client(server).exportPdf("abcde12345", File(tmp.root, "o.pdf"))
            assertEquals(3, server.requests.size)
        } finally {
            server.stop()
        }
    }

    @Test fun noConnectionIsReportedAfterThreeTries() {
        val server = FakeDriveServer { FakeReply(200) }
        val endpoints = server.endpoints
        server.stop()   // nothing listens on this address any more
        val parts = TestClientParts()
        val client = DriveClient(DriveTokens { "t" }, endpoints, { parts.sleeps += it }, { 0.0 })
        val e = failureOf { client.exportPdf("abcde12345", File(tmp.root, "o.pdf")) }
        assertEquals("No internet connection", e.message)
        assertTrue(e.offline)
        assertEquals(listOf(1000L, 2000L), parts.sleeps)
    }

    @Test fun aSlowAnswerIsReportedAsATimeout() {
        val server = FakeDriveServer { Thread.sleep(700); FakeReply(200, "%PDF") }
        try {
            val parts = TestClientParts()
            val client = DriveClient(DriveTokens { "t" }, server.endpoints, { parts.sleeps += it }, { 0.0 }, apiReadMs = 200, transferReadMs = 200)
            val e = failureOf { client.exportPdf("abcde12345", File(tmp.root, "o.pdf")) }
            assertEquals("The connection timed out, try again", e.message)
            assertTrue(e.offline)
            assertEquals(listOf(1000L, 2000L), parts.sleeps)
        } finally {
            server.stop()
        }
    }

    @Test fun redirectsAreNotFollowed() {
        val server = FakeDriveServer { FakeReply(302, headers = mapOf("Location" to "http://example.invalid/x")) }
        try {
            failureOf { TestClientParts().client(server).exportPdf("abcde12345", File(tmp.root, "o.pdf")) }
            assertEquals(1, server.requests.size)
        } finally {
            server.stop()
        }
    }

    // ------------------------------------------------------------ delete

    @Test fun deleteSendsDeleteAndAccepts204() {
        val server = FakeDriveServer { FakeReply(204) }
        try {
            TestClientParts().client(server).delete("abcde12345")
            val r = server.requests.single()
            assertEquals("DELETE", r.method)
            assertEquals("/drive/v3/files/abcde12345", r.path)
        } finally {
            server.stop()
        }
    }

    @Test fun deleteOfAMissingFileCountsAsDone() {
        val server = FakeDriveServer { FakeReply(404, """{"error":{"code":404}}""") }
        try {
            TestClientParts().client(server).delete("abcde12345")
        } finally {
            server.stop()
        }
    }

    @Test fun deleteForbiddenFails() {
        val server = FakeDriveServer { FakeReply(403, """{"error":{"errors":[{"reason":"forbidden"}]}}""") }
        try {
            val e = failureOf { TestClientParts().client(server).delete("abcde12345") }
            assertEquals(403, e.httpCode)
        } finally {
            server.stop()
        }
    }

    @Test fun badFileIdsNeverReachTheNetwork() {
        val server = FakeDriveServer { FakeReply(204) }
        try {
            val c = TestClientParts().client(server)
            for (bad in listOf("../x", "a/b", "ab", "id with space", "id?x=1", "")) {
                try { c.delete(bad); fail("accepted: $bad") } catch (e: IllegalArgumentException) { }
                try { c.exportPdf(bad, File(tmp.root, "o.pdf")); fail("accepted: $bad") } catch (e: IllegalArgumentException) { }
            }
            assertTrue(server.requests.isEmpty())
        } finally {
            server.stop()
        }
    }

    // ------------------------------------------------------------ search

    @Test fun findByMarkerSendsTheQueryAndFollowsPages() {
        var calls = 0
        val server = FakeDriveServer {
            if (++calls == 1) FakeReply(200, """{"nextPageToken":"PAGE2","files":[{"id":"aaaaa11111"}]}""")
            else FakeReply(200, """{"files":[{"id":"bbbbb22222"}]}""")
        }
        try {
            val ids = TestClientParts().client(server).findTempFiles(marker)
            assertEquals(listOf("aaaaa11111", "bbbbb22222"), ids)
            val q1 = URLDecoder.decode(server.requests[0].query!!, "UTF-8")
            assertTrue(q1.contains("appProperties has { key='pdfm_job' and value='$marker' }"))
            assertTrue(q1.contains("fields=nextPageToken,files(id)"))
            assertTrue(URLDecoder.decode(server.requests[1].query!!, "UTF-8").contains("pageToken=PAGE2"))
        } finally {
            server.stop()
        }
    }

    @Test fun findAllUsesTheCommonProperty() {
        val server = FakeDriveServer { FakeReply(200, """{"files":[]}""") }
        try {
            assertEquals(emptyList<String>(), TestClientParts().client(server).findTempFiles())
            val q = URLDecoder.decode(server.requests.single().query!!, "UTF-8")
            assertTrue(q.contains("appProperties has { key='pdfm' and value='1' }"))
        } finally {
            server.stop()
        }
    }

    @Test fun quickSearchTriesOnlyOnce() {
        val server = FakeDriveServer { FakeReply(503) }
        try {
            val parts = TestClientParts()
            failureOf { parts.client(server).findTempFiles(marker, attempts = 1) }
            assertEquals(1, server.requests.size)
            assertTrue(parts.sleeps.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test fun searchRefusesABadMarker() {
        val server = FakeDriveServer { FakeReply(200, """{"files":[]}""") }
        try {
            try { TestClientParts().client(server).findTempFiles("x' or name contains '"); fail() }
            catch (e: IllegalArgumentException) { }
            assertTrue(server.requests.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test fun exceptionTextNeverHoldsTheTokenOrServerText() {
        val server = FakeDriveServer { FakeReply(500, """{"error":{"message":"tok1 leaked report.docx"}}""") }
        try {
            val e = failureOf { TestClientParts().client(server).delete("abcde12345") }
            assertNotNull(e.message)
            assertFalse(e.message!!.contains("tok1"))
            assertFalse(e.message!!.contains("report"))
        } finally {
            server.stop()
        }
    }
}
