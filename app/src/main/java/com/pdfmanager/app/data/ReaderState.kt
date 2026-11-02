package com.pdfmanager.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * The saved reading state of every PDF: the page the reader stopped on ("positions") and the bookmarks
 * ("bookmarks"). Both are stored under [FileKey.of] (file name + size). Earlier versions used a hash of the Uri;
 * those entries are moved over the first time the file is seen, so nobody loses a bookmark.
 */
object ReaderState {
    private const val POSITIONS = "positions"
    private const val BOOKMARKS = "bookmarks"

    private fun positions(c: Context) = c.getSharedPreferences(POSITIONS, Context.MODE_PRIVATE)
    private fun bookmarks(c: Context) = c.getSharedPreferences(BOOKMARKS, Context.MODE_PRIVATE)

    /**
     * The key of the file at [uriString], and the old entries of that file moved to it. Falls back to the old
     * Uri-hash key when the name or size cannot be read. Quick (one small content query), but not for a hot loop.
     */
    fun keyFor(c: Context, uriString: String): String {
        val key = runCatching { nameAndSize(c, Uri.parse(uriString)) }.getOrNull()
            ?.let { (name, size) -> FileKey.of(name, size) }
            ?: return FileKey.legacy(uriString)
        moveLegacy(c, FileKey.legacy(uriString), key)
        return key
    }

    private fun nameAndSize(c: Context, uri: Uri): Pair<String, Long>? {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) return null
            val name = cur.getString(0) ?: return null
            val size = if (cur.isNull(1)) -1L else cur.getLong(1)
            if (size < 0) return null
            return name to size
        }
        return null
    }

    /** Copies what was saved under [old] to [new] (never over something already saved there) and removes [old]. */
    private fun moveLegacy(c: Context, old: String, new: String) {
        if (old == new) return
        val pos = positions(c)
        val bm = bookmarks(c)
        val hasPos = pos.contains(old)
        val hasBm = bm.contains(old)
        if (!hasPos && !hasBm) return
        if (hasPos) {
            val e = pos.edit()
            if (!pos.contains(new)) e.putInt(new, pos.getInt(old, 0))
            e.remove(old).apply()
        }
        if (hasBm) {
            val merged = BackupJson.mergeBookmarks(
                mapOf(new to pagesOf(bm.getString(new, ""))), mapOf(new to pagesOf(bm.getString(old, "")))
            )[new].orEmpty()
            val e = bm.edit()
            if (merged.isEmpty()) e.remove(new) else e.putString(new, merged.joinToString(","))
            e.remove(old).apply()
        }
    }

    /**
     * Moves the old entries of every known PDF to its new key, in one pass. Called after each library scan, so
     * a backup also holds the files that were never opened since the update.
     */
    fun migrateAll(c: Context, files: List<PdfMeta>) {
        val pos = positions(c)
        val bm = bookmarks(c)
        if (pos.all.isEmpty() && bm.all.isEmpty()) return
        files.forEach { f -> moveLegacy(c, FileKey.legacy(f.uri), FileKey.of(f.name, f.sizeBytes)) }
    }

    /** A file was renamed from [oldName] to [newName]: its saved page and bookmarks follow it. */
    fun renamed(c: Context, oldName: String, newName: String, sizeBytes: Long) {
        val old = FileKey.of(oldName, sizeBytes)
        val new = FileKey.of(newName, sizeBytes)
        if (old == new) return
        val pos = positions(c)
        if (pos.contains(old)) {
            val e = pos.edit()
            if (!pos.contains(new)) e.putInt(new, pos.getInt(old, 0))
            e.remove(old).apply()
        }
        val bm = bookmarks(c)
        if (bm.contains(old)) {
            val merged = BackupJson.mergeBookmarks(
                mapOf(new to pagesOf(bm.getString(new, ""))), mapOf(new to pagesOf(bm.getString(old, "")))
            )[new].orEmpty()
            val e = bm.edit()
            if (merged.isEmpty()) e.remove(new) else e.putString(new, merged.joinToString(","))
            e.remove(old).apply()
        }
    }

    /** Everything stored under a stable key, for the backup. */
    fun allPositions(c: Context): Map<String, Int> =
        positions(c).all.mapNotNull { (k, v) -> if (FileKey.isStable(k) && v is Int) k to v else null }.toMap()

    fun allBookmarks(c: Context): Map<String, List<Int>> =
        bookmarks(c).all.mapNotNull { (k, v) ->
            val pages = pagesOf(v as? String)
            if (FileKey.isStable(k) && pages.isNotEmpty()) k to pages else null
        }.toMap()

    /** Adds [data] to what is stored (see [BackupJson.mergeBookmarks] and [BackupJson.mergePositions]). */
    fun restore(c: Context, bookmarksIn: Map<String, List<Int>>, positionsIn: Map<String, Int>) {
        val mergedBm = BackupJson.mergeBookmarks(allBookmarks(c), bookmarksIn)
        val eb = bookmarks(c).edit()
        mergedBm.forEach { (k, pages) -> eb.putString(k, pages.joinToString(",")) }
        eb.apply()
        val mergedPos = BackupJson.mergePositions(allPositions(c), positionsIn)
        val ep = positions(c).edit()
        mergedPos.forEach { (k, v) -> ep.putInt(k, v) }
        ep.apply()
    }

    private fun pagesOf(text: String?): List<Int> =
        (text ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }.distinct().sorted()
}
