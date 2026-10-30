package com.pdfmanager.app.data

import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The text of one PDF, folded for searching (see [SearchHelpers.fold]). [stamp] says which version of the
 * file it was read from; [status] is one of the SearchHelpers.STATUS_* values. Files without usable text
 * are stored with empty text, so they are not read again until they change.
 */
@Entity(tableName = "pdf_text")
data class PdfTextEntity(
    @PrimaryKey val uri: String,
    val stamp: String,
    val status: Int,
    val text: String
)

data class TextStamp(val uri: String, val stamp: String)

/** A file that matched a search: where the first word is ([pos], 1-based) and the text around it ([raw]). */
data class TextHit(val uri: String, val pos: Int, val raw: String)

@Dao
abstract class TextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(item: PdfTextEntity)

    @Query("SELECT uri, stamp FROM pdf_text")
    abstract suspend fun stamps(): List<TextStamp>

    @Query("DELETE FROM pdf_text WHERE uri IN (:uris)")
    abstract suspend fun deleteByUris(uris: List<String>)

    @Query("DELETE FROM pdf_text")
    abstract suspend fun clear()

    /** Built by [TextIndexer.hitQuery]. Runs again whenever the index changes. */
    @RawQuery(observedEntities = [PdfTextEntity::class])
    abstract fun observeHits(query: SupportSQLiteQuery): Flow<List<TextHit>>
}

/**
 * The text index lives in its own database file, not in pdf_manager.db: it is only a speed-up that can be
 * rebuilt at any time, it can get large, and it holds the contents of the person's PDFs, so it is left out of
 * Android's cloud backup (res/xml/backup_rules.xml, data_extraction_rules.xml). A changed schema simply
 * starts a new, empty index.
 */
@Database(entities = [PdfTextEntity::class], version = 1, exportSchema = false)
abstract class TextIndexDb : RoomDatabase() {
    abstract fun dao(): TextDao

    companion object {
        const val FILE_NAME = "pdf_text.db"
        @Volatile private var instance: TextIndexDb? = null

        fun get(context: Context): TextIndexDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TextIndexDb::class.java, FILE_NAME)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}

/** Reads the text of every PDF in the library into [TextIndexDb], one file at a time. */
object TextIndexer {

    fun hitQuery(terms: List<String>): SupportSQLiteQuery =
        SimpleSQLiteQuery(SearchHelpers.hitSql(terms.size), SearchHelpers.hitArgs(terms))

    /**
     * Reads what is new or changed since the last run, newest files first. Rows of files that are gone are
     * removed. [onProgress] gets (files done, files to do). Can be cancelled between two files; what was
     * finished stays, so the next run goes on from there.
     */
    suspend fun run(
        context: Context, library: PdfDao, text: TextDao,
        onProgress: (done: Int, total: Int) -> Unit
    ) {
        val pdfs = library.allMeta()
        val stored = text.stamps().associate { it.uri to it.stamp }
        val live = pdfs.mapTo(HashSet()) { it.uri }
        stored.keys.filter { it !in live }.chunked(500).forEach { text.deleteByUris(it) }

        val pending = pdfs
            .filter { stored[it.uri] != SearchHelpers.stampOf(it.sizeBytes, it.modifiedSec) }
            .sortedByDescending { it.modifiedSec }
        if (pending.isEmpty()) return
        onProgress(0, pending.size)
        pending.forEachIndexed { i, p ->
            currentCoroutineContext().ensureActive()
            val row = withContext(Dispatchers.IO) { readOne(context, p) }
            text.put(row)
            onProgress(i + 1, pending.size)
        }
    }

    private fun readOne(context: Context, p: PdfMeta): PdfTextEntity {
        val stamp = SearchHelpers.stampOf(p.sizeBytes, p.modifiedSec)
        fun row(status: Int, text: String = "") = PdfTextEntity(p.uri, stamp, status, text)
        if (p.sizeBytes > SearchHelpers.MAX_FILE_BYTES) return row(SearchHelpers.STATUS_FAILED)
        return try {
            val stream = context.contentResolver.openInputStream(Uri.parse(p.uri))
                ?: return row(SearchHelpers.STATUS_FAILED)
            val doc = stream.use { PdfOps.load(it) }
            try {
                val stripper = PDFTextStripper()
                stripper.setLineSeparator("\n")
                val pages = minOf(doc.numberOfPages, SearchHelpers.MAX_PAGES)
                val raw = StringBuilder()
                var page = 1
                // A little more raw text than the limit: folding removes spaces and line breaks
                while (page <= pages && raw.length < SearchHelpers.MAX_CHARS * 3 / 2) {
                    stripper.setStartPage(page)
                    stripper.setEndPage(page)
                    raw.append(stripper.getText(doc)).append('\n')
                    page++
                }
                val folded = SearchHelpers.fold(raw.toString()).take(SearchHelpers.MAX_CHARS)
                if (folded.isEmpty()) row(SearchHelpers.STATUS_NO_TEXT) else row(SearchHelpers.STATUS_OK, folded)
            } finally {
                runCatching { doc.close() }
            }
        } catch (e: InvalidPasswordException) {
            row(SearchHelpers.STATUS_FAILED)
        } catch (e: Exception) {
            row(SearchHelpers.STATUS_FAILED)
        } catch (e: OutOfMemoryError) {
            row(SearchHelpers.STATUS_FAILED)
        }
    }
}
