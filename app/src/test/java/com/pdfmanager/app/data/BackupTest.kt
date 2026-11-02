package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private val k1 = FileKey.of("Book.pdf", 1234)
    private val k2 = FileKey.of("Other.pdf", 99)

    @Test fun fileKeyIsStableAndIgnoresCase() {
        assertEquals(k1, FileKey.of("  book.PDF ", 1234))
        assertNotEquals(k1, FileKey.of("Book.pdf", 1235))
        assertNotEquals(k1, k2)
        assertTrue(FileKey.isStable(k1))
        assertFalse(FileKey.isStable(FileKey.legacy("content://x/1")))
        assertFalse(FileKey.isStable("f_xyz"))
    }

    @Test fun backupRoundTrips() {
        val data = BackupData(
            createdAtMillis = 1_700_000_000_000L,
            device = "Acme \"Phone\" 5",
            settings = mapOf("ui.theme" to 2, "ui.dynamic" to false, "ui.sort" to "NAME", "viewer.night" to true),
            favorites = setOf(k2, k1),
            bookmarks = mapOf(k1 to listOf(5, 2, 9)),
            positions = mapOf(k1 to 7, k2 to 0),
        )
        val back = BackupJson.read(BackupJson.write(data))
        assertNotNull(back)
        back!!
        assertEquals(1_700_000_000_000L, back.createdAtMillis)
        assertEquals("Acme \"Phone\" 5", back.device)
        assertEquals(2, back.settings["ui.theme"])
        assertEquals(false, back.settings["ui.dynamic"])
        assertEquals("NAME", back.settings["ui.sort"])
        assertEquals(true, back.settings["viewer.night"])
        assertEquals(setOf(k1, k2), back.favorites)
        assertEquals(listOf(2, 5, 9), back.bookmarks[k1])
        assertEquals(mapOf(k1 to 7, k2 to 0), back.positions)
    }

    @Test fun readRejectsGarbageAndFutureVersions() {
        assertNull(BackupJson.read("not json"))
        assertNull(BackupJson.read("[]"))
        assertNull(BackupJson.read("{\"version\":99}"))
        assertNull(BackupJson.read("{}"))
    }

    @Test fun readSkipsUnknownAndWrongTypedEntries() {
        val text = """{"version":1,"createdAt":5,"device":"x",
            "settings":{"ui.theme":"dark","ui.sort":"NEWEST","ui.save_tree":"content://secret","nope.key":1,"ui.accent":1.5},
            "favorites":["bad", "$k1", 3],
            "bookmarks":{"bad":[1],"$k1":[0,3,3,"x",200000]},
            "positions":{"$k1":-4,"$k2":12}}"""
        val d = BackupJson.read(text)!!
        // theme is a string where an Int is expected, accent is not a whole number, the save folder is not allowed
        assertEquals(mapOf("ui.sort" to "NEWEST"), d.settings)
        assertEquals(setOf(k1), d.favorites)
        assertEquals(mapOf(k1 to listOf(3)), d.bookmarks)
        assertEquals(mapOf(k2 to 12), d.positions)
    }

    @Test fun saveFolderIsNeverPartOfABackup() {
        assertTrue(BackupSettings.all.none { it.key == "save_tree" })
    }

    @Test fun bookmarksAreUnitedAndPositionsOnlyFillGaps() {
        val bm = BackupJson.mergeBookmarks(mapOf(k1 to listOf(1, 4)), mapOf(k1 to listOf(4, 2), k2 to listOf(8)))
        assertEquals(listOf(1, 2, 4), bm[k1])
        assertEquals(listOf(8), bm[k2])
        val pos = BackupJson.mergePositions(mapOf(k1 to 3), mapOf(k1 to 10, k2 to 6))
        assertEquals(3, pos[k1])
        assertEquals(6, pos[k2])
    }

    // ------------------------------------------------------------ Drive side, against a local fake server

    private fun sampleData() = BackupData(1L, "dev", mapOf("ui.theme" to 1), setOf(k1), mapOf(k1 to listOf(2)), mapOf(k1 to 4))

    @Test fun savingCreatesInAppDataFolderThenDeletesOldCopies() {
        val server = FakeDriveServer { r ->
            when {
                r.method == "GET" && r.path.endsWith("/files") -> FakeReply(200, "{\"files\":[{\"id\":\"oldId12345\"}]}")
                r.method == "POST" && r.path.endsWith("/files") -> FakeReply(200, "{\"id\":\"newId12345\"}")
                r.method == "DELETE" -> FakeReply(204)
                else -> FakeReply(404)
            }
        }
        try {
            DriveBackup(TestClientParts().client(server)).save(sampleData())
            val reqs = server.requests.toList()
            val list = reqs.first { it.method == "GET" }
            assertTrue(list.query!!.contains("spaces=appDataFolder"))
            val create = reqs.first { it.method == "POST" }
            assertTrue(create.query!!.contains("uploadType=multipart"))
            assertTrue(create.headers["content-type"]!!.startsWith("multipart/related; boundary="))
            val body = String(create.body, Charsets.UTF_8)
            assertTrue(body.contains("\"parents\":[\"appDataFolder\"]"))
            assertTrue(body.contains("\"name\":\"backup.json\""))
            assertTrue(body.contains("\"version\":1"))
            val deletes = reqs.filter { it.method == "DELETE" }
            assertEquals(listOf("/drive/v3/files/oldId12345"), deletes.map { it.path })
            // the new file is created before the old one is deleted
            assertTrue(reqs.indexOf(create) < reqs.indexOf(deletes.first()))
        } finally {
            server.stop()
        }
    }

    @Test fun latestReadsTheNewestBackupAndHandlesNone() {
        val json = BackupJson.write(sampleData())
        var empty = false
        val server = FakeDriveServer { r ->
            when {
                r.method == "GET" && r.path.endsWith("/files") ->
                    FakeReply(200, if (empty) "{\"files\":[]}" else "{\"files\":[{\"id\":\"fileA12345\"},{\"id\":\"fileB12345\"}]}")
                r.method == "GET" && r.path.endsWith("/files/fileA12345") -> FakeReply(200, json)
                else -> FakeReply(404)
            }
        }
        try {
            val backup = DriveBackup(TestClientParts().client(server))
            val d = backup.latest()!!
            assertEquals(setOf(k1), d.favorites)
            assertTrue(server.requests.any { it.query == "alt=media" })
            empty = true
            assertNull(backup.latest())
        } finally {
            server.stop()
        }
    }

    @Test fun unreadableBackupGivesAClearError() {
        val server = FakeDriveServer { r ->
            if (r.path.endsWith("/files")) FakeReply(200, "{\"files\":[{\"id\":\"fileA12345\"}]}") else FakeReply(200, "garbage")
        }
        try {
            val e = runCatching { DriveBackup(TestClientParts().client(server)).latest() }.exceptionOrNull()
            assertTrue(e is DriveException)
            assertEquals(DriveBackup.MSG_UNREADABLE, e!!.message)
        } finally {
            server.stop()
        }
    }

    @Test fun deleteAllRemovesEveryCopy() {
        val server = FakeDriveServer { r ->
            when {
                r.method == "GET" -> FakeReply(200, "{\"files\":[{\"id\":\"fileA12345\"},{\"id\":\"fileB12345\"}]}")
                r.method == "DELETE" -> FakeReply(204)
                else -> FakeReply(404)
            }
        }
        try {
            assertTrue(DriveBackup(TestClientParts().client(server)).deleteAll())
            assertEquals(2, server.requests.count { it.method == "DELETE" })
        } finally {
            server.stop()
        }
    }
}
