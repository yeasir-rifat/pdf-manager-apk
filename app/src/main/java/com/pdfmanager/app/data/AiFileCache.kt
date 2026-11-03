package com.pdfmanager.app.data

import java.security.MessageDigest

/**
 * Remembers the Files API address of PDFs that were uploaded, so a second question, or reopening the viewer,
 * does not upload again. Google keeps an uploaded file for 48 hours, so a link counts for [TTL_MS] (47 hours).
 *
 * It lives in the private ai_settings file (through [AiStore]), so Clear AI data removes it, Android backup skips it,
 * and it is not part of [BackupSettings]. A link only works for the key that uploaded the file, so the key's
 * fingerprint is part of every cache key. The key itself is never stored here.
 */
class AiFileCache(private val store: AiStore, private val now: () -> Long = System::currentTimeMillis) {

    companion object {
        const val TTL_MS = 47L * 60 * 60 * 1000
        const val STORE_KEY = "uploaded_files"
        const val MAX_ENTRIES = 20

        fun of(context: android.content.Context) = AiFileCache(SharedPrefsAiStore(context))

        /** [fileKey] comes from [FileKey.of]. The same file under another API key is another entry. */
        fun key(fileKey: String, apiKey: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder()
            for (i in 0 until 5) hex.append(String.format("%02x", d[i].toInt() and 0xff))
            return "${fileKey}_$hex"
        }
    }

    private class Entry(val key: String, val expiresAt: Long, val uri: String)

    private fun read(): MutableList<Entry> {
        val out = ArrayList<Entry>()
        for (line in store.getString(STORE_KEY).orEmpty().lines()) {
            val p = line.split('|', limit = 3)
            if (p.size != 3) continue
            val exp = p[1].toLongOrNull() ?: continue
            out += Entry(p[0], exp, p[2])
        }
        return out
    }

    private fun write(entries: List<Entry>) {
        if (entries.isEmpty()) store.remove(STORE_KEY)
        else store.putString(STORE_KEY, entries.joinToString("\n") { "${it.key}|${it.expiresAt}|${it.uri}" })
    }

    /** The link for [key], or null when there is none or it is older than [TTL_MS]. An expired entry is dropped. */
    fun get(key: String): String? {
        val all = read()
        val hit = all.firstOrNull { it.key == key } ?: return null
        if (now() >= hit.expiresAt) {
            write(all.filter { it.key != key })
            return null
        }
        return hit.uri
    }

    /** Keeps [uri] under [key] for [TTL_MS]. Expired entries go, and only the newest [MAX_ENTRIES] stay. */
    fun put(key: String, uri: String) {
        if (uri.isBlank() || uri.any { it == '\n' || it == '\r' || it == '|' }) return
        val t = now()
        val kept = read().filter { it.key != key && it.expiresAt > t }.toMutableList()
        kept += Entry(key, t + TTL_MS, uri)
        write(kept.takeLast(MAX_ENTRIES))
    }

    /** Forgets [key], for a link that Google no longer accepts. */
    fun remove(key: String) {
        val all = read()
        if (all.any { it.key == key }) write(all.filter { it.key != key })
    }
}
