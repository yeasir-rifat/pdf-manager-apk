package com.pdfmanager.app.printing

import android.print.PrintJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** What the print service says about a job right now. */
enum class JobStatus { WAITING, BLOCKED, FAILED, COMPLETED, CANCELLED }

/**
 * Step 10. Watches one print job and reports problems the print service tells us about.
 * This only passes on what Android reports. It cannot see the printer itself, and if the
 * service reports nothing, nothing is shown. Android's own print notification stays the
 * main place for status, cancel and retry.
 */
object PrintJobWatcher {

    private const val POLL_MS = 1500L
    private const val MAX_WATCH_MS = 15 * 60 * 1000L
    private const val MAX_TRACK_MS = 3 * 60 * 60 * 1000L

    /** Turns the raw flags of a [PrintJob] into one status. Pure, so it can be unit tested. */
    fun statusOf(cancelled: Boolean, failed: Boolean, completed: Boolean, blocked: Boolean): JobStatus = when {
        failed -> JobStatus.FAILED
        cancelled -> JobStatus.CANCELLED
        completed -> JobStatus.COMPLETED
        blocked -> JobStatus.BLOCKED
        else -> JobStatus.WAITING
    }

    fun messageFor(status: JobStatus): String? = when (status) {
        JobStatus.FAILED -> "Printing failed. Check the printer, then try again from Android's print notification or this screen."
        JobStatus.BLOCKED -> "The printer needs attention (for example paper, ink or a connection). Check Android's print notification."
        JobStatus.COMPLETED -> "The print service reports the job as finished."
        JobStatus.CANCELLED, JobStatus.WAITING -> null
    }

    private fun read(job: PrintJob): JobStatus = statusOf(job.isCancelled, job.isFailed, job.isCompleted, job.isBlocked)

    /**
     * Polls [job] until it fails, finishes, is cancelled, or about 15 minutes pass. [onProblem]
     * gets a message for FAILED, BLOCKED (once per blocked period) and COMPLETED. Stops when the
     * calling coroutine is cancelled, for example when the user leaves the screen. If Android
     * stops answering for this job, watching just ends quietly.
     */
    suspend fun watch(job: PrintJob, onProblem: suspend (String) -> Unit) {
        var waited = 0L
        var wasBlocked = false
        while (coroutineContext.isActive && waited < MAX_WATCH_MS) {
            val status = try {
                withContext(Dispatchers.Default) { read(job) }
            } catch (e: Exception) {
                return
            }
            when (status) {
                JobStatus.FAILED, JobStatus.COMPLETED -> {
                    messageFor(status)?.let { onProblem(it) }
                    return
                }
                JobStatus.CANCELLED -> return
                JobStatus.BLOCKED -> if (!wasBlocked) {
                    wasBlocked = true
                    messageFor(status)?.let { onProblem(it) }
                }
                JobStatus.WAITING -> wasBlocked = false
            }
            delay(POLL_MS)
            waited += POLL_MS
        }
    }

    /**
     * Step 13. Follows [job] until it ends and returns how it ended (COMPLETED, FAILED or CANCELLED),
     * or null if Android stopped answering or about 3 hours passed. [onStatus] is called when the
     * status changes while the job is still running (WAITING or BLOCKED). Used by the background
     * service, so it keeps going when the print screen is closed.
     */
    suspend fun track(job: PrintJob, onStatus: suspend (JobStatus) -> Unit): JobStatus? {
        var waited = 0L
        var last: JobStatus? = null
        while (coroutineContext.isActive && waited < MAX_TRACK_MS) {
            val status = try {
                withContext(Dispatchers.Default) { read(job) }
            } catch (e: Exception) {
                return null
            }
            when (status) {
                JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED -> return status
                else -> if (status != last) {
                    last = status
                    onStatus(status)
                }
            }
            delay(POLL_MS)
            waited += POLL_MS
        }
        return null
    }
}
