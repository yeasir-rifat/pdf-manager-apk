package com.pdfmanager.app.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PrintJobWatcherTest {
    private fun s(c: Boolean = false, f: Boolean = false, d: Boolean = false, b: Boolean = false) =
        PrintJobWatcher.statusOf(c, f, d, b)

    @Test fun idleJobIsWaiting() = assertEquals(JobStatus.WAITING, s())
    @Test fun failedWinsOverEverything() = assertEquals(JobStatus.FAILED, s(c = true, f = true, d = true, b = true))
    @Test fun cancelledBeatsCompletedAndBlocked() = assertEquals(JobStatus.CANCELLED, s(c = true, d = true, b = true))
    @Test fun completedBeatsBlocked() = assertEquals(JobStatus.COMPLETED, s(d = true, b = true))
    @Test fun blockedAlone() = assertEquals(JobStatus.BLOCKED, s(b = true))

    @Test fun messagesOnlyForProblemsAndFinish() {
        assertNotNull(PrintJobWatcher.messageFor(JobStatus.FAILED))
        assertNotNull(PrintJobWatcher.messageFor(JobStatus.BLOCKED))
        assertNotNull(PrintJobWatcher.messageFor(JobStatus.COMPLETED))
        assertNull(PrintJobWatcher.messageFor(JobStatus.CANCELLED))
        assertNull(PrintJobWatcher.messageFor(JobStatus.WAITING))
    }
}
