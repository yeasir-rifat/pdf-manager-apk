package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DriveLeftoversTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun journal() = DriveTempJournal(File(tmp.root, "journal.txt"))

    @Test fun entryWithIdIsDeletedAndClosed() {
        val server = FakeDriveServer { r ->
            if (r.method == "GET") FakeReply(200, """{"files":[]}""") else FakeReply(204)
        }
        try {
            val j = journal()
            val m = DriveTempJournal.newMarker()
            j.begin(m, 0L); j.setFileId(m, "abcde12345"); j.release(m)
            val parts = TestClientParts()
            val closed = DriveLeftovers.retry(parts.client(server), j, 10L)
            assertEquals(1, closed)
            assertTrue(j.all().isEmpty())
            assertTrue(server.requests.any { it.method == "DELETE" && it.path.endsWith("/files/abcde12345") })
        } finally {
            server.stop()
        }
    }

    @Test fun failedDeleteKeepsTheEntry() {
        val server = FakeDriveServer { FakeReply(503) }
        try {
            val j = journal()
            val m = DriveTempJournal.newMarker()
            j.begin(m, 0L); j.setFileId(m, "abcde12345"); j.release(m)
            val closed = DriveLeftovers.retry(TestClientParts().client(server), j, 10L)
            assertEquals(0, closed)
            assertEquals(1, j.all().size)
        } finally {
            server.stop()
        }
    }

    @Test fun youngEntryWithoutIdIsKeptWhenNothingIsFound() {
        val server = FakeDriveServer { FakeReply(200, """{"files":[]}""") }
        try {
            val j = journal()
            val m = DriveTempJournal.newMarker()
            j.begin(m, 1_000L); j.release(m)
            assertEquals(0, DriveLeftovers.retry(TestClientParts().client(server), j, 1_000L + 10_000L))
            assertEquals(1, j.all().size)
        } finally {
            server.stop()
        }
    }

    @Test fun oldEntryWithoutIdIsClosedWhenNothingIsFound() {
        val server = FakeDriveServer { FakeReply(200, """{"files":[]}""") }
        try {
            val j = journal()
            val m = DriveTempJournal.newMarker()
            j.begin(m, 1_000L); j.release(m)
            val now = 1_000L + DriveLeftovers.MIN_AGE_FOR_NO_ID_MS
            assertEquals(1, DriveLeftovers.retry(TestClientParts().client(server), j, now))
            assertTrue(j.all().isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test fun entryWithoutIdDeletesWhatTheMarkerFinds() {
        val server = FakeDriveServer { r ->
            if (r.method == "GET") FakeReply(200, """{"files":[{"id":"found11111"}]}""") else FakeReply(204)
        }
        try {
            val j = journal()
            val m = DriveTempJournal.newMarker()
            j.begin(m, 1_000L); j.release(m)
            assertEquals(1, DriveLeftovers.retry(TestClientParts().client(server), j, 1_001L))
            assertTrue(server.requests.any { it.method == "DELETE" && it.path.endsWith("/files/found11111") })
            assertTrue(j.all().isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test fun runningConversionsAreLeftAlone() {
        val server = FakeDriveServer { FakeReply(204) }
        try {
            val j = journal()
            val m = DriveTempJournal.newMarker()
            j.begin(m, 0L); j.setFileId(m, "abcde12345")   // still running: not released
            assertEquals(0, DriveLeftovers.retry(TestClientParts().client(server), j, 99L))
            assertTrue(server.requests.isEmpty())
            j.release(m)
        } finally {
            server.stop()
        }
    }

    @Test fun sweepAllDeletesEveryMarkedFileAndClosesEntries() {
        val server = FakeDriveServer { r ->
            if (r.method == "GET" && r.query!!.contains("pdfm")) {
                // The journal retry asks by job marker (nothing), the sweep asks by the common property (two files)
                if (java.net.URLDecoder.decode(r.query, "UTF-8").contains("key='pdfm' and")) {
                    FakeReply(200, """{"files":[{"id":"aaaaa11111"},{"id":"bbbbb22222"}]}""")
                } else FakeReply(200, """{"files":[]}""")
            } else FakeReply(204)
        }
        try {
            val j = journal()
            val m = DriveTempJournal.newMarker()
            j.begin(m, 0L); j.setFileId(m, "aaaaa11111"); j.release(m)
            val n = DriveLeftovers.sweepAll(TestClientParts().client(server), j, 10L)
            assertEquals(2, n)
            assertTrue(j.all().isEmpty())
            val deleted = server.requests.filter { it.method == "DELETE" }.map { it.path.substringAfterLast('/') }
            assertTrue(deleted.containsAll(listOf("aaaaa11111", "bbbbb22222")))
        } finally {
            server.stop()
        }
    }

    @Test fun sweepAllIsRefusedWhileAConversionRuns() {
        val server = FakeDriveServer { FakeReply(200, """{"files":[]}""") }
        val j = journal()
        val m = DriveTempJournal.newMarker()
        j.begin(m, 0L)
        try {
            try {
                DriveLeftovers.sweepAll(TestClientParts().client(server), j, 1L)
                org.junit.Assert.fail("Expected a refusal")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("running"))
            }
            assertTrue(server.requests.isEmpty())
            assertTrue(DriveTempJournal.anyRunning())
        } finally {
            j.release(m)
            server.stop()
        }
        assertTrue(!DriveTempJournal.anyRunning())
    }
}
