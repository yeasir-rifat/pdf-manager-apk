package com.pdfmanager.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

/** Only the columns the scanner compares, so a sync does not read whole rows. */
data class PdfMeta(val uri: String, val name: String, val sizeBytes: Long, val modifiedSec: Long)

/** A PDF found on the device. folderId == null means "Unfiled". */
@Entity(tableName = "pdfs")
data class PdfEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedSec: Long,
    val folderId: Long? = null,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lastOpened: Long = 0
)
