package com.pdfmanager.app.data

import java.security.MessageDigest

/**
 * A key for one PDF that stays the same on every phone: it comes from the file name and the file size, not from
 * the Uri (the Uri is different on each phone, so a key made from it cannot be restored from a backup).
 * Bookmarks and the last read page are stored under this key.
 *
 * Two different files with the same name and the same byte count share a key. That is rare and only means they
 * share bookmarks. A file that is renamed gets a new key; [ReaderState.renamed] moves its saved data across.
 */
object FileKey {
    /** Every key made here starts with this, so it can be told apart from an old Uri-hash key (a plain number). */
    const val PREFIX = "f_"

    /** The key for a file called [name] with [sizeBytes] bytes. The name is compared without case. */
    fun of(name: String, sizeBytes: Long): String {
        val text = name.trim().lowercase() + "|" + sizeBytes
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(PREFIX)
        for (i in 0 until 10) hex.append(String.format("%02x", digest[i].toInt() and 0xff))
        return hex.toString()
    }

    /** True for a key made by [of]. */
    fun isStable(key: String): Boolean = key.length == PREFIX.length + 20 && key.startsWith(PREFIX)

    /** The key that earlier versions of the app used. It only works on the phone it was made on. */
    fun legacy(uriString: String): String = uriString.hashCode().toString()
}
