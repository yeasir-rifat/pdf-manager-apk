package com.pdfmanager.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PdfDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(items: List<PdfEntity>)

    @Query(
        """UPDATE pdfs SET name = :name, sizeBytes = :size, modifiedSec = :modified
           WHERE uri = :uri AND (name != :name OR sizeBytes != :size OR modifiedSec != :modified)"""
    )
    abstract suspend fun updateMeta(uri: String, name: String, size: Long, modified: Long)

    /**
     * Brings the table in line with what is on the device, in one transaction:
     * adds new files, refreshes changed ones and removes the ones that are gone.
     * Rows that did not change are not touched, so the list does not reload for nothing.
     */
    @Transaction
    open suspend fun sync(found: List<PdfEntity>) {
        // Read what is stored once, then only write the rows that are new or really changed
        val existing = allMeta().associateBy { it.uri }
        val added = found.filter { it.uri !in existing }
        if (added.isNotEmpty()) insertAll(added)
        found.forEach { f ->
            val e = existing[f.uri] ?: return@forEach
            if (e.name != f.name || e.sizeBytes != f.sizeBytes || e.modifiedSec != f.modifiedSec) {
                updateMeta(f.uri, f.name, f.sizeBytes, f.modifiedSec)
            }
        }
        val live = found.mapTo(HashSet()) { it.uri }
        existing.keys.filter { it !in live }.chunked(500).forEach { deleteByUris(it) }
    }

    @Query("SELECT uri, name, sizeBytes, modifiedSec FROM pdfs")
    abstract suspend fun allMeta(): List<PdfMeta>

    @Query("SELECT uri FROM pdfs")
    abstract suspend fun allUris(): List<String>

    @Query("DELETE FROM pdfs WHERE uri IN (:uris)")
    abstract suspend fun deleteByUris(uris: List<String>)

    // filter: null = all, 0 = unfiled, otherwise folder id
    @Query(
        """SELECT * FROM pdfs
           WHERE (:filter IS NULL) OR (:filter = 0 AND folderId IS NULL) OR folderId = :filter OR (:filter = -1 AND favorite = 1) OR (:filter = -2 AND lastOpened > 0)
           ORDER BY name COLLATE NOCASE"""
    )
    abstract fun observePdfs(filter: Long?): Flow<List<PdfEntity>>

    @Query("UPDATE pdfs SET lastOpened = :time WHERE uri = :uri")
    abstract suspend fun touch(uri: String, time: Long)

    @Query("SELECT * FROM pdfs WHERE lastOpened > 0 ORDER BY lastOpened DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<PdfEntity>>

    @Query("SELECT * FROM pdfs ORDER BY MAX(lastOpened, modifiedSec) DESC LIMIT :limit")
    abstract fun observeNewest(limit: Int): Flow<List<PdfEntity>>

    @Query("UPDATE pdfs SET lastOpened = 0")
    abstract suspend fun clearRecent()

    @Query("UPDATE pdfs SET favorite = :value WHERE uri IN (:uris)")
    abstract suspend fun setFavorite(uris: List<String>, value: Boolean)

    @Query("UPDATE pdfs SET folderId = :folderId WHERE uri = :uri")
    abstract suspend fun moveTo(uri: String, folderId: Long?)

    @Query("UPDATE pdfs SET name = :name WHERE uri = :uri")
    abstract suspend fun rename(uri: String, name: String)

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    abstract fun observeFolders(): Flow<List<FolderEntity>>

    @Insert
    abstract suspend fun insertFolder(folder: FolderEntity)

    @Query("UPDATE pdfs SET folderId = NULL WHERE folderId = :id")
    abstract suspend fun releaseFolder(id: Long)

    @Query("DELETE FROM folders WHERE id = :id")
    abstract suspend fun deleteFolderRow(id: Long)

    @Transaction
    open suspend fun deleteFolder(id: Long) {
        releaseFolder(id)
        deleteFolderRow(id)
    }
}

@Database(entities = [FolderEntity::class, PdfEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PdfDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // Version 3 adds the last-opened time used by the recent files list
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pdfs ADD COLUMN lastOpened INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Version 2 adds the favorite flag; existing folders and assignments are kept
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pdfs ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "pdf_manager.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
