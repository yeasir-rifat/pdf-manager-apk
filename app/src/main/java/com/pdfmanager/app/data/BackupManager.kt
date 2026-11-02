package com.pdfmanager.app.data

import android.content.Context
import android.os.Build

/**
 * Collects what the backup holds from this phone and puts a backup back. The Drive side is [DriveBackup].
 * Blocking and database calls: run them off the main thread.
 */
object BackupManager {
    private const val PREFS = "backup"
    private const val LAST_AT = "last_at"
    private const val PENDING_FAVORITES = "pending_favorites"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When this phone last saved a backup (milliseconds), or 0. */
    fun lastBackupAt(c: Context): Long = prefs(c).getLong(LAST_AT, 0L)

    fun setLastBackupAt(c: Context, millis: Long) = prefs(c).edit().putLong(LAST_AT, millis).apply()

    private const val AUTO = "auto"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun autoEnabled(c: Context): Boolean = prefs(c).getBoolean(AUTO, false)
    fun setAuto(c: Context, on: Boolean) = prefs(c).edit().putBoolean(AUTO, on).apply()

    /**
     * Backs up when Auto backup is on, somebody is signed in and the last backup is over a day old. Silent: it only
     * uses a token Google gives without asking, so it never opens a screen and does nothing before the first manual
     * Backup. Blocking: call it off the main thread. Returns true when a backup was saved.
     */
    suspend fun autoBackupIfDue(c: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (!autoEnabled(c) || now - lastBackupAt(c) < DAY_MS) return false
        val email = GoogleAccount.current(c)?.email ?: return false
        val token = GoogleDriveAuth.tokenBlocking(c, OfficeHelpers.DRIVE_APPDATA_SCOPE, email) ?: return false
        val data = collect(c)
        drive(c, token, email).save(data)
        setLastBackupAt(c, data.createdAtMillis)
        return true
    }

    /** What was brought back by [restore]. */
    class Summary(val bookmarkedFiles: Int, val favorites: Int, val settings: Int)

    /** The access token of one run. After a 401 a new one is asked from Google Play services (silent after the first agreement). */
    private class Tokens(private val c: Context, first: String, private val email: String?) : DriveTokens {
        @Volatile private var current = first

        override fun token(forceRefresh: Boolean): String {
            if (forceRefresh) {
                current = GoogleDriveAuth.tokenBlocking(c, OfficeHelpers.DRIVE_APPDATA_SCOPE, email)
                    ?: throw DriveException(OfficeHelpers.messageFor(401, null), 401)
            }
            return current
        }
    }

    fun drive(c: Context, token: String, accountEmail: String?): DriveBackup =
        DriveBackup(DriveClient(Tokens(c.applicationContext, token, accountEmail)))

    // ------------------------------------------------------------ collecting

    suspend fun collect(c: Context): BackupData {
        val dao = AppDatabase.get(c).dao()
        val favorites = dao.favoriteMeta().map { FileKey.of(it.name, it.sizeBytes) }.toSet()
        val settings = LinkedHashMap<String, Any>()
        BackupSettings.all.forEach { spec ->
            val p = c.getSharedPreferences(spec.file, Context.MODE_PRIVATE)
            if (!p.contains(spec.key)) return@forEach
            runCatching {
                settings[spec.id] = when (spec.type) {
                    BackupSettings.Type.BOOL -> p.getBoolean(spec.key, false)
                    BackupSettings.Type.INT -> p.getInt(spec.key, 0)
                    BackupSettings.Type.STRING -> p.getString(spec.key, "").orEmpty()
                }
            }
        }
        // Old Uri-hash entries of files that are on the phone are moved to their stable keys first
        ReaderState.migrateAll(c, dao.allMeta())
        return BackupData(
            createdAtMillis = System.currentTimeMillis(),
            device = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").take(80),
            settings = settings,
            favorites = favorites,
            bookmarks = ReaderState.allBookmarks(c),
            positions = ReaderState.allPositions(c),
        )
    }

    // ------------------------------------------------------------ restoring

    /**
     * Puts the backup [d] back. Bookmarks and favorites are added to what is here, last read pages only fill gaps,
     * settings are replaced. A favorite whose file is not on this phone yet is kept and applied by [afterScan].
     */
    suspend fun restore(c: Context, d: BackupData): Summary {
        ReaderState.restore(c, d.bookmarks, d.positions)

        var settingsApplied = 0
        val editors = HashMap<String, android.content.SharedPreferences.Editor>()
        d.settings.forEach { (id, value) ->
            val spec = BackupSettings.spec(id) ?: return@forEach
            val e = editors.getOrPut(spec.file) { c.getSharedPreferences(spec.file, Context.MODE_PRIVATE).edit() }
            when (value) {
                is Boolean -> e.putBoolean(spec.key, value)
                is Int -> e.putInt(spec.key, value)
                is String -> e.putString(spec.key, value)
                else -> return@forEach
            }
            settingsApplied++
        }
        editors.values.forEach { it.apply() }

        val pending = prefs(c).getStringSet(PENDING_FAVORITES, emptySet()).orEmpty().toMutableSet()
        pending += d.favorites
        prefs(c).edit().putStringSet(PENDING_FAVORITES, pending).apply()
        applyPendingFavorites(c)

        return Summary(d.bookmarks.size, d.favorites.size, settingsApplied)
    }

    /** Called after each library scan: moves old reading data to stable keys and applies favorites that were waiting for their file. */
    suspend fun afterScan(c: Context) {
        val dao = AppDatabase.get(c).dao()
        ReaderState.migrateAll(c, dao.allMeta())
        applyPendingFavorites(c)
    }

    private suspend fun applyPendingFavorites(c: Context) {
        val p = prefs(c)
        val pending = p.getStringSet(PENDING_FAVORITES, emptySet()).orEmpty()
        if (pending.isEmpty()) return
        val dao = AppDatabase.get(c).dao()
        val byKey = dao.allMeta().groupBy({ FileKey.of(it.name, it.sizeBytes) }, { it.uri })
        val matched = pending.filter { it in byKey }
        matched.flatMap { byKey.getValue(it) }.chunked(500).forEach { dao.setFavorite(it, true) }
        // What matched is done; the rest waits for its file. A favorite removed later is not brought back.
        p.edit().putStringSet(PENDING_FAVORITES, pending - matched.toSet()).apply()
    }
}
