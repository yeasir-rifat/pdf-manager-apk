package com.pdfmanager.app.data

import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/** One temporary file in the user's Drive that is not yet confirmed deleted. [fileId] is null until the upload answered. */
class JournalEntry(val marker: String, val fileId: String?, val createdMillis: Long)

/**
 * Remembers which temporary Drive files exist, so that a file whose deletion failed (offline, app killed)
 * is deleted later. It holds only a random marker, the Drive file id and a time: never a file name or content.
 *
 * It is a plain text file, one line per entry: marker TAB id-or-dash TAB millis. The caller gives a file in
 * noBackupFilesDir, which Android leaves out of backups and phone-to-phone transfer. No Android classes here.
 * A write that fails is ignored on purpose: the journal is a safety net and must never break a conversion.
 */
class DriveTempJournal(private val file: File) {

    companion object {
        private val MARKER = Regex("[A-Za-z0-9]{8,64}")
        private val LOCK = Any()

        /** Markers of conversions that are running in this process. Those are not leftovers. */
        private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun isValidMarker(s: String): Boolean = MARKER.matches(s)

        /** True while a conversion of this process is between begin and release/complete. */
        fun anyRunning(): Boolean = running.isNotEmpty()

        /** A new random marker: 32 hex characters. */
        fun newMarker(random: Random = SecureRandom()): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return bytes.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
        }
    }

    /** Writes the entry down before anything is uploaded, and marks the conversion as running. */
    fun begin(marker: String, nowMillis: Long) {
        if (!isValidMarker(marker)) return
        running.add(marker)
        synchronized(LOCK) {
            val list = read().filter { it.marker != marker }.toMutableList()
            list += JournalEntry(marker, null, nowMillis)
            write(list)
        }
    }

    /** Adds the Drive file id once the upload answered. */
    fun setFileId(marker: String, fileId: String) {
        if (!DriveClient.isValidFileId(fileId)) return
        synchronized(LOCK) {
            val list = read().map { if (it.marker == marker) JournalEntry(it.marker, fileId, it.createdMillis) else it }
            write(list)
        }
    }

    /** The Drive file is confirmed gone: forget the entry. */
    fun complete(marker: String) {
        running.remove(marker)
        synchronized(LOCK) {
            val list = read()
            if (list.any { it.marker == marker }) write(list.filter { it.marker != marker })
        }
    }

    /** The conversion has ended but its entry stays for a later retry. */
    fun release(marker: String) {
        running.remove(marker)
    }

    /** Entries that no running conversion owns: the ones to retry. Oldest first. */
    fun pending(): List<JournalEntry> =
        synchronized(LOCK) { read() }.filter { it.marker !in running }.sortedBy { it.createdMillis }

    /** Every entry, also those of running conversions. */
    fun all(): List<JournalEntry> = synchronized(LOCK) { read() }

    // ------------------------------------------------------------ file

    private fun read(): List<JournalEntry> {
        val text = try {
            if (file.isFile) file.readText(Charsets.UTF_8) else return emptyList()
        } catch (e: IOException) {
            return emptyList()
        }
        val out = ArrayList<JournalEntry>()
        for (line in text.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size != 3) continue
            val marker = parts[0]
            if (!isValidMarker(marker)) continue
            val id = parts[1].takeIf { it != "-" }
            if (id != null && !DriveClient.isValidFileId(id)) continue
            val time = parts[2].toLongOrNull() ?: continue
            if (out.none { it.marker == marker }) out += JournalEntry(marker, id, time)
        }
        return out
    }

    private fun write(entries: List<JournalEntry>) {
        try {
            if (entries.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(
                entries.joinToString("\n", postfix = "\n") { "${it.marker}\t${it.fileId ?: "-"}\t${it.createdMillis}" },
                Charsets.UTF_8,
            )
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (e: IOException) {
            // see the class note: ignored on purpose
        }
    }
}

/** Tries again to delete temporary Drive files that an earlier conversion could not delete. */
object DriveLeftovers {

    /** An entry with no file id is not closed as "nothing there" before this age: Drive's search can lag behind. */
    const val MIN_AGE_FOR_NO_ID_MS = 2L * 60 * 1000

    /** At most this many entries are handled per call, so a call stays short. */
    const val MAX_PER_RUN = 20

    /**
     * Handles the journal entries that no running conversion owns. An entry is dropped from the journal only when
     * its Drive file is confirmed gone. Returns how many entries were closed. Never throws: a failure keeps the entry.
     */
    fun retry(client: DriveClient, journal: DriveTempJournal, nowMillis: Long): Int {
        var closed = 0
        for (entry in journal.pending().take(MAX_PER_RUN)) {
            val done = try {
                handle(client, entry, nowMillis)
            } catch (e: Exception) {
                false
            }
            if (done) {
                journal.complete(entry.marker)
                closed++
            }
        }
        return closed
    }

    /**
     * Deletes every temporary file this app made in the user's Drive (found through the common property), whatever the
     * journal says, then lets [retry] close the journal entries whose files are gone. Refused while a conversion runs
     * in this process. Returns how many Drive files were deleted. Throws with a message for the user on failure.
     */
    fun sweepAll(client: DriveClient, journal: DriveTempJournal, nowMillis: Long): Int {
        check(!DriveTempJournal.anyRunning()) { "A conversion is running. Try again when it is done" }
        val ids = client.findTempFiles()
        ids.forEach { client.delete(it) }
        retry(client, journal, nowMillis)
        return ids.size
    }

    private fun handle(client: DriveClient, entry: JournalEntry, nowMillis: Long): Boolean {
        if (entry.fileId != null) client.delete(entry.fileId)
        val found = client.findTempFiles(entry.marker)
        found.forEach { client.delete(it) }
        if (entry.fileId != null || found.isNotEmpty()) return true
        return nowMillis - entry.createdMillis >= MIN_AGE_FOR_NO_ID_MS
    }
}
