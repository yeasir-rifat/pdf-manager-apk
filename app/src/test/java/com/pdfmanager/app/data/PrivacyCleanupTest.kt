package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrivacyCleanupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, modified: Long) =
        File(dir, name).apply { writeText("x"); setLastModified(modified) }

    @Test fun deletesOnlyFilesOlderThanCutoff() {
        val dir = tmp.newFolder("thumbs")
        val old = file(dir, "old.jpg", 1_000_000L)
        val fresh = file(dir, "fresh.jpg", 9_000_000L)
        assertEquals(1, PrivacyCleanup.deleteOlderThan(dir, 5_000_000L))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test fun futureCutoffClearsEveryFile() {
        val dir = tmp.newFolder("print-tmp")
        file(dir, "a.pdf", 1_000L); file(dir, "b.pdf", 2_000L)
        assertEquals(2, PrivacyCleanup.deleteOlderThan(dir, Long.MAX_VALUE))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun leavesSubfoldersAlone() {
        val dir = tmp.newFolder("cache")
        val sub = File(dir, "keep").apply { mkdirs() }
        val inner = file(sub, "x.pdf", 1_000L)
        assertEquals(0, PrivacyCleanup.deleteOlderThan(dir, Long.MAX_VALUE))
        assertTrue(inner.exists())
    }

    @Test fun missingFolderIsFine() {
        assertEquals(0, PrivacyCleanup.deleteOlderThan(File(tmp.root, "nope"), Long.MAX_VALUE))
    }
}
