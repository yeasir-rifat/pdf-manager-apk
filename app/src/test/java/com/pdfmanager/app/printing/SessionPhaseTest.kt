package com.pdfmanager.app.printing

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPhaseTest {

    @Test fun pass1FinishedMovesToReadyForPass2() =
        assertEquals(SessionPhase.READY_2, SessionPhase.after(SessionPhase.PRINTING_1, JobStatus.COMPLETED))

    @Test fun pass2FinishedEndsTheSession() =
        assertEquals(SessionPhase.NONE, SessionPhase.after(SessionPhase.PRINTING_2, JobStatus.COMPLETED))

    @Test fun cancelOrFailureStopsTheRunningPass() {
        assertEquals(SessionPhase.STOPPED_1, SessionPhase.after(SessionPhase.PRINTING_1, JobStatus.CANCELLED))
        assertEquals(SessionPhase.STOPPED_1, SessionPhase.after(SessionPhase.PRINTING_1, JobStatus.FAILED))
        assertEquals(SessionPhase.STOPPED_2, SessionPhase.after(SessionPhase.PRINTING_2, JobStatus.CANCELLED))
        assertEquals(SessionPhase.STOPPED_2, SessionPhase.after(SessionPhase.PRINTING_2, JobStatus.FAILED))
    }

    @Test fun waitingAndBlockedChangeNothing() {
        assertEquals(SessionPhase.PRINTING_1, SessionPhase.after(SessionPhase.PRINTING_1, JobStatus.WAITING))
        assertEquals(SessionPhase.PRINTING_2, SessionPhase.after(SessionPhase.PRINTING_2, JobStatus.BLOCKED))
    }

    @Test fun otherPhasesStayWhenAJobEnds() {
        assertEquals(SessionPhase.READY_2, SessionPhase.after(SessionPhase.READY_2, JobStatus.CANCELLED))
        assertEquals(SessionPhase.STOPPED_1, SessionPhase.after(SessionPhase.STOPPED_1, JobStatus.COMPLETED))
    }

    @Test fun passOfPhase() {
        assertEquals(DuplexPass.FIRST, SessionPhase.passOf(SessionPhase.PRINTING_1))
        assertEquals(DuplexPass.FIRST, SessionPhase.passOf(SessionPhase.STOPPED_1))
        assertEquals(DuplexPass.SECOND, SessionPhase.passOf(SessionPhase.READY_2))
        assertEquals(DuplexPass.SECOND, SessionPhase.passOf(SessionPhase.STOPPED_2))
    }
}
