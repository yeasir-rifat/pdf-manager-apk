package com.pdfmanager.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The folder the person picked in Settings to save results into. Null means the default
 * (Documents/PDF Manager and Pictures/PDF Manager). The folder is kept as a persisted
 * storage-access tree Uri, so no storage permission is needed.
 */
object SaveFolder {
    private const val KEY = "save_tree"
    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** The chosen folder, or null when none is set or the permission to it is gone. */
    fun get(c: Context): Uri? {
        val raw = prefs(c).getString(KEY, null) ?: return null
        val uri = Uri.parse(raw)
        val granted = c.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (granted) uri else null
    }

    fun set(c: Context, tree: Uri) {
        val old = prefs(c).getString(KEY, null)?.let(Uri::parse)
        runCatching {
            c.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        prefs(c).edit().putString(KEY, tree.toString()).apply()
        if (old != null && old != tree) release(c, old)
    }

    fun clear(c: Context) {
        val old = prefs(c).getString(KEY, null)?.let(Uri::parse)
        prefs(c).edit().remove(KEY).apply()
        if (old != null) release(c, old)
    }

    private fun release(c: Context, uri: Uri) {
        runCatching {
            c.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /** A short readable path of the chosen folder, such as "Download/Scans". */
    fun label(c: Context): String? {
        val tree = get(c) ?: return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val path = id.substringAfter(':', "").trim('/')
        return path.ifEmpty { id.substringBefore(':').let { if (it == "primary") "Internal storage" else it } }
    }
}
