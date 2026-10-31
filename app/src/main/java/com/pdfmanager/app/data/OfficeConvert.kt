package com.pdfmanager.app.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A finished conversion. [pages] is 0 when the page count could not be read. */
class OfficeResult(val uri: Uri, val pages: Int, val driveCopyRemoved: Boolean) {
    /** The text for JobStatus. */
    fun message(): String = OfficeHelpers.resultMessage(pages, driveCopyRemoved)
}

/**
 * Word, Excel and PowerPoint to PDF through the user's own Google Drive: upload with conversion, export as PDF,
 * delete the Drive file, then save the PDF. The same shape as PdfConvert.
 *
 * Call [toPdf] from ToolJob.launch (a background thread) after GoogleDriveAuth gave a token. It blocks until done.
 * Everything that must be undone sits in one try / finally: the Drive file is deleted and the cache file is
 * removed whatever happens. If the Drive file cannot be deleted at once, its entry stays in the journal and
 * DriveLeftovers deletes it on a later run.
 */
object OfficeConvert {

    /** Folder in the app cache for the PDF that came back from Google, until it is saved. */
    const val TEMP_DIR = "office-tmp"

    private const val JOURNAL_FILE = "drive-temp-journal.txt"

    /** Android leaves noBackupFilesDir out of backups and device transfer, so the journal is never copied. */
    fun journal(context: Context): DriveTempJournal =
        DriveTempJournal(File(context.noBackupFilesDir, JOURNAL_FILE))

    /** The token of this run. After a 401 a new one is asked from Google Play services (silent if the user agreed before). */
    private class SessionTokens(private val context: Context, first: String) : DriveTokens {
        @Volatile private var current = first

        override fun token(forceRefresh: Boolean): String {
            if (forceRefresh) {
                current = GoogleDriveAuth.tokenBlocking(context)
                    ?: throw DriveException(OfficeHelpers.messageFor(401, null), 401)
            }
            return current
        }
    }

    /**
     * Tries again to delete temporary Drive files that an earlier run could not delete. Blocking: call it off the main
     * thread. It does nothing (and uses no network) when the journal is empty. With no [firstToken] it only asks Google
     * Play services for a silent token, so it never opens a screen; without an earlier agreement it simply returns 0.
     */
    fun retryLeftovers(context: Context, firstToken: String? = null): Int {
        val j = journal(context)
        if (j.pending().isEmpty()) return 0
        val token = firstToken ?: GoogleDriveAuth.tokenBlocking(context) ?: return 0
        return DriveLeftovers.retry(DriveClient(SessionTokens(context, token)), j, System.currentTimeMillis())
    }

    /** Settings: deletes every temporary file of this app in the user's Drive. Blocking. Returns how many were deleted. */
    fun removeAllTemp(context: Context, accessToken: String): Int =
        DriveLeftovers.sweepAll(DriveClient(SessionTokens(context, accessToken)), journal(context), System.currentTimeMillis())

    /**
     * Converts [source] (a Word, Excel or PowerPoint file called [sourceName]) to a PDF in Documents/PDF Manager.
     * [onProgress] gets short texts for ToolJob.progress. Problems come as exceptions whose message is for the user
     * (ToolJob shows "Failed: ..."). [client] is only replaced in tests.
     */
    fun toPdf(
        context: Context, source: Uri, kind: OfficeKind, sourceName: String?, accessToken: String,
        onProgress: (String) -> Unit = {}, client: DriveClient? = null,
    ): OfficeResult {
        onProgress("Checking file…")
        val size = sizeOf(context, source)
        val header = readHeader(context, source)
        OfficeHelpers.checkFile(kind, sourceName, size, header)?.let { throw IllegalStateException(it) }

        val drive = client ?: DriveClient(SessionTokens(context, accessToken))
        val journal = journal(context)
        val marker = DriveTempJournal.newMarker()
        val tmpDir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
        val tmp = File(tmpDir, "$marker.pdf")

        var fileId: String? = null
        var driveAttempted = false
        var driveGone = false
        journal.begin(marker, System.currentTimeMillis())
        try {
            onProgress("Uploading and converting…")
            fileId = drive.uploadForConversion(kind, sourceName, size, marker) { openInput(context, source) }
            journal.setFileId(marker, fileId)

            onProgress("Downloading PDF…")
            drive.exportPdf(fileId, tmp)

            // The Drive copy is removed as soon as the PDF is here, not after the local save
            onProgress("Cleaning up…")
            driveAttempted = true
            driveGone = removeFromDrive(drive, marker, fileId)
            if (driveGone) journal.complete(marker)

            if (!tmp.isFile || tmp.length() <= 0L || !OfficeHelpers.looksLikePdf(readFileHead(tmp))) {
                throw DriveException(DriveClient.MSG_BAD_PDF)
            }
            val pages = pageCount(tmp)

            onProgress("Saving…")
            val name = OfficeHelpers.outputName(kind, sourceName, stampNow())
            // The PDF is already checked and complete, so a failed network can no longer leave a half-written output file
            val uri = OutputStore.savePdf(context, name) { os -> FileInputStream(tmp).use { it.copyTo(os) } }
            return OfficeResult(uri, pages, driveGone)
        } finally {
            if (!driveAttempted) {
                // Failed before the normal delete: undo what may exist in Drive (one quick try only)
                if (removeFromDrive(drive, marker, fileId, quick = fileId == null) && fileId != null) journal.complete(marker)
            }
            // Older leftovers (checked while this run's own marker is still "running", so it is skipped)
            runCatching { DriveLeftovers.retry(drive, journal, System.currentTimeMillis()) }
            journal.release(marker)
            runCatching { tmp.delete() }
        }
    }

    /**
     * Deletes the Drive file [fileId] (when known) and every file that carries this run's [marker]: a copy made by an
     * upload that was tried twice, or one whose answer was lost. Returns true when all of it is confirmed gone.
     * With [quick] there is one single try, so a failed run does not keep the user waiting.
     */
    private fun removeFromDrive(drive: DriveClient, marker: String, fileId: String?, quick: Boolean = false): Boolean =
        try {
            if (fileId != null) drive.delete(fileId)
            val rest = drive.findTempFiles(marker, attempts = if (quick) 1 else OfficeHelpers.MAX_ATTEMPTS)
            rest.forEach { drive.delete(it) }
            true
        } catch (e: Exception) {
            false
        }

    // ------------------------------------------------------------ the picked file

    private fun openInput(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("Cannot open the file")

    /** Size in bytes: from the provider when it says, otherwise counted (up to one byte over the limit). */
    private fun sizeOf(context: Context, uri: Uri): Long {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L
        if (fromProvider > 0L) return fromProvider
        var total = 0L
        openInput(context, uri).use { input ->
            val buf = ByteArray(64 * 1024)
            while (total <= OfficeHelpers.MAX_FILE_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
        }
        return total
    }

    private fun readHeader(context: Context, uri: Uri): ByteArray = openInput(context, uri).use { readHead(it) }

    private fun readFileHead(file: File): ByteArray = FileInputStream(file).use { readHead(it) }

    private fun readHead(input: InputStream): ByteArray {
        val buf = ByteArray(1024)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return buf.copyOf(n)
    }

    private fun pageCount(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
    }.getOrDefault(0)

    private fun stampNow(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
