package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DriveTempJournalTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun journal(): DriveTempJournal = DriveTempJournal(File(tmp.root, "j/journal.txt"))

    @Test fun newMarkersAreValidAndDifferent() {
        val a = DriveTempJournal.newMarker()
        val b = DriveTempJournal.newMarker()
        assertTrue(DriveTempJournal.isValidMarker(a))
        assertEquals(32, a.length)
        assertTrue(a != b)
    }

    @Test fun markerValidation() {
        assertFalse(DriveTempJournal.isValidMarker("short"))
        assertFalse(DriveTempJournal.isValidMarker("abc def ghi jkl"))
        assertFalse(DriveTempJournal.isValidMarker("abcdefgh'--"))
        assertTrue(DriveTempJournal.isValidMarker("abcdef0123456789"))
    }

    @Test fun beginSetIdAndCompleteRoundTrip() {
        val j = journal()
        val m = DriveTempJournal.newMarker()
        j.begin(m, 1000L)
        assertEquals(1, j.all().size)
        assertNull(j.all()[0].fileId)
        j.setFileId(m, "file_ID-12345")
        assertEquals("file_ID-12345", j.all()[0].fileId)
        assertEquals(1000L, j.all()[0].createdMillis)
        j.complete(m)
        assertTrue(j.all().isEmpty())
    }

    @Test fun runningEntriesAreNotPendingUntilReleased() {
        val j = journal()
        val m = DriveTempJournal.newMarker()
        j.begin(m, 5L)
        assertTrue(j.pending().isEmpty())
        j.release(m)
        assertEquals(listOf(m), j.pending().map { it.marker })
    }

    @Test fun entriesSurviveANewInstance() {
        val m = DriveTempJournal.newMarker()
        val first = journal()
        first.begin(m, 7L)
        first.setFileId(m, "abcde12345")
        first.release(m)
        val second = journal()
        val e = second.pending().single()
        assertEquals(m, e.marker)
        assertEquals("abcde12345", e.fileId)
    }

    @Test fun pendingIsOldestFirst() {
        val j = journal()
        val a = DriveTempJournal.newMarker()
        val b = DriveTempJournal.newMarker()
        j.begin(a, 200L); j.begin(b, 100L)
        j.release(a); j.release(b)
        assertEquals(listOf(b, a), j.pending().map { it.marker })
    }

    @Test fun badLinesAreSkipped() {
        val f = File(tmp.root, "bad.txt")
        val good = DriveTempJournal.newMarker()
        f.writeText("garbage\nshort\tx\t1\n$good\t-\t42\n$good\t-\t43\nabcdefgh1234\tbad id!\t5\nabcdefgh1234\t-\tnotanumber\n")
        val list = DriveTempJournal(f).all()
        assertEquals(1, list.size)
        assertEquals(42L, list[0].createdMillis)
    }

    @Test fun invalidMarkerAndIdAreIgnored() {
        val j = journal()
        j.begin("bad marker", 1L)
        assertTrue(j.all().isEmpty())
        val m = DriveTempJournal.newMarker()
        j.begin(m, 1L)
        j.setFileId(m, "../etc")
        assertNull(j.all()[0].fileId)
        j.release(m)
    }

    @Test fun missingFileMeansEmpty() {
        assertTrue(journal().all().isEmpty())
        assertTrue(journal().pending().isEmpty())
    }

    @Test fun journalHoldsNoNameOrContent() {
        val f = File(tmp.root, "j.txt")
        val j = DriveTempJournal(f)
        val m = DriveTempJournal.newMarker()
        j.begin(m, 9L)
        j.setFileId(m, "abcde12345")
        j.release(m)
        assertEquals("$m\tabcde12345\t9\n", f.readText())
    }
}
