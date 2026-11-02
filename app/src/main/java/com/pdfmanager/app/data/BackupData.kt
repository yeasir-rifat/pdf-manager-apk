package com.pdfmanager.app.data

/**
 * What goes into the Google Drive backup. Plain Kotlin on purpose (no Android classes), so it runs in unit tests.
 * Not in it: the PDF files themselves and the "Save location" folder (a folder link does not work on another phone).
 */
class BackupData(
    val createdAtMillis: Long,
    val device: String,
    /** "<file>.<key>" to a Boolean, Int or String value, only for the keys listed in [BackupSettings.all]. */
    val settings: Map<String, Any>,
    /** Stable keys ([FileKey.of]) of the favorite PDFs. */
    val favorites: Set<String>,
    /** Stable key to 1-based bookmarked pages. */
    val bookmarks: Map<String, List<Int>>,
    /** Stable key to the index of the page the reader stopped on (0-based, as the viewer stores it). */
    val positions: Map<String, Int>,
) {
    companion object {
        const val VERSION = 1
        const val FILE_NAME = "backup.json"

        /** A backup bigger than this is not read (a real one is a few kilobytes). */
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

/** The preference values that a backup keeps. Anything not listed here is never written to Drive. */
object BackupSettings {
    enum class Type { BOOL, INT, STRING }

    class Spec(val file: String, val key: String, val type: Type) {
        val id: String get() = "$file.$key"
    }

    /** Theme, colors, sorting, view choices and viewer choices. Not listed: save folder, print and Office-notice flags. */
    val all: List<Spec> = listOf(
        Spec("ui", "theme", Type.INT),
        Spec("ui", "dynamic", Type.BOOL),
        Spec("ui", "pureBlack", Type.BOOL),
        Spec("ui", "accent", Type.INT),
        Spec("ui", "vibration", Type.BOOL),
        Spec("ui", "sort", Type.STRING),
        Spec("ui", "searchInside", Type.BOOL),
        Spec("ui", "ask_name", Type.BOOL),
        Spec("ui", "grid", Type.BOOL),
        Spec("ui", "toolsList", Type.BOOL),
        Spec("ui", "recentTools", Type.STRING),
        Spec("viewer", "night", Type.BOOL),
        Spec("viewer", "keep_on", Type.BOOL),
        Spec("viewer", "orientation", Type.INT),
    )

    private val byId = all.associateBy { it.id }

    fun spec(id: String): Spec? = byId[id]
}

/** Writing and reading backup.json, and combining a backup with what is already on the phone. */
object BackupJson {

    fun write(d: BackupData): String {
        val sb = StringBuilder()
        sb.append("{\"version\":").append(BackupData.VERSION)
        sb.append(",\"createdAt\":").append(d.createdAtMillis)
        sb.append(",\"device\":").append(DriveJson.quote(d.device))
        sb.append(",\"settings\":{")
        sb.append(d.settings.entries.sortedBy { it.key }.joinToString(",") { (k, v) ->
            DriveJson.quote(k) + ":" + when (v) {
                is Boolean -> v.toString()
                is Int -> v.toString()
                else -> DriveJson.quote(v.toString())
            }
        })
        sb.append("},\"favorites\":[")
        sb.append(d.favorites.sorted().joinToString(",") { DriveJson.quote(it) })
        sb.append("],\"bookmarks\":{")
        sb.append(d.bookmarks.entries.filter { it.value.isNotEmpty() }.sortedBy { it.key }.joinToString(",") { (k, pages) ->
            DriveJson.quote(k) + ":[" + pages.sorted().joinToString(",") + "]"
        })
        sb.append("},\"positions\":{")
        sb.append(d.positions.entries.sortedBy { it.key }.joinToString(",") { (k, v) -> DriveJson.quote(k) + ":" + v })
        sb.append("}}")
        return sb.toString()
    }

    /** The backup in [text], or null when it is not a backup this version understands. Unknown or wrong-typed entries are skipped. */
    fun read(text: String): BackupData? {
        val root = DriveJson.parseObject(text) ?: return null
        val version = (root["version"] as? Double)?.toInt() ?: return null
        if (version < 1 || version > BackupData.VERSION) return null
        val created = (root["createdAt"] as? Double)?.toLong() ?: 0L
        val device = root["device"] as? String ?: ""

        val settings = LinkedHashMap<String, Any>()
        (root["settings"] as? Map<*, *>)?.forEach { (k, v) ->
            val spec = (k as? String)?.let { BackupSettings.spec(it) } ?: return@forEach
            when (spec.type) {
                BackupSettings.Type.BOOL -> (v as? Boolean)?.let { settings[spec.id] = it }
                BackupSettings.Type.INT -> (v as? Double)?.let { if (it == Math.floor(it) && Math.abs(it) < 1e9) settings[spec.id] = it.toInt() }
                BackupSettings.Type.STRING -> (v as? String)?.let { if (it.length <= 2000) settings[spec.id] = it }
            }
        }

        val favorites = (root["favorites"] as? List<*>).orEmpty()
            .mapNotNull { it as? String }.filter { FileKey.isStable(it) }.toSet()

        val bookmarks = LinkedHashMap<String, List<Int>>()
        (root["bookmarks"] as? Map<*, *>)?.forEach { (k, v) ->
            val key = k as? String ?: return@forEach
            if (!FileKey.isStable(key)) return@forEach
            val pages = (v as? List<*>).orEmpty().mapNotNull { (it as? Double)?.toInt() }.filter { it in 1..100_000 }.distinct().sorted()
            if (pages.isNotEmpty()) bookmarks[key] = pages
        }

        val positions = LinkedHashMap<String, Int>()
        (root["positions"] as? Map<*, *>)?.forEach { (k, v) ->
            val key = k as? String ?: return@forEach
            val page = (v as? Double)?.toInt() ?: return@forEach
            if (FileKey.isStable(key) && page in 0..100_000) positions[key] = page
        }
        return BackupData(created, device, settings, favorites, bookmarks, positions)
    }

    /** Bookmarks: everything saved here plus everything in the backup. */
    fun mergeBookmarks(local: Map<String, List<Int>>, backup: Map<String, List<Int>>): Map<String, List<Int>> {
        val out = LinkedHashMap<String, List<Int>>(local)
        backup.forEach { (k, pages) -> out[k] = ((out[k] ?: emptyList()) + pages).distinct().sorted() }
        return out
    }

    /** Last read pages: the page on this phone wins; the backup only fills in files that have none here. */
    fun mergePositions(local: Map<String, Int>, backup: Map<String, Int>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>(local)
        backup.forEach { (k, v) -> if (k !in out) out[k] = v }
        return out
    }
}
