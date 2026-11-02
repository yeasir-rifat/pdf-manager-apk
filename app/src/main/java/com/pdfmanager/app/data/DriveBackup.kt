package com.pdfmanager.app.data

/**
 * Keeps one backup.json in the user's hidden Google Drive app data folder. A new backup is created first and the
 * old ones are deleted after it, so a failed upload never leaves the user without a backup. No Android classes:
 * covered by unit tests against a local fake server.
 */
class DriveBackup(private val drive: DriveClient) {

    /** The newest backup, or null when there is none. Throws [DriveException] when it cannot be read. */
    fun latest(): BackupData? {
        val id = drive.findAppDataFiles(BackupData.FILE_NAME).firstOrNull() ?: return null
        val text = drive.readAppDataText(id, BackupData.MAX_BYTES)
        return BackupJson.read(text) ?: throw DriveException(MSG_UNREADABLE)
    }

    /** Uploads [data] as the new backup, then removes the older copies. */
    fun save(data: BackupData) {
        val old = drive.findAppDataFiles(BackupData.FILE_NAME)
        val newId = drive.createAppDataJson(BackupData.FILE_NAME, BackupJson.write(data))
        // A copy that cannot be deleted now is picked up (as an older one) by the next backup
        old.filter { it != newId }.forEach { runCatching { drive.delete(it) } }
    }

    /** Deletes every backup. Returns false when there was none. */
    fun deleteAll(): Boolean {
        val ids = drive.findAppDataFiles(BackupData.FILE_NAME)
        ids.forEach { drive.delete(it) }
        return ids.isNotEmpty()
    }

    companion object {
        const val MSG_UNREADABLE = "The backup could not be read"
    }
}
