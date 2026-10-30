package com.pdfmanager.app.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

/** Finds every PDF on the device through MediaStore. */
class PdfScanner(private val context: Context) {
    fun scan(): List<PdfEntity> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        val result = mutableListOf<PdfEntity>()
        context.contentResolver.query(
            collection, projection, selection, arrayOf("application/pdf"), null
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                result += PdfEntity(
                    uri = uri.toString(),
                    name = c.getString(nameCol) ?: "Unnamed.pdf",
                    sizeBytes = c.getLong(sizeCol),
                    modifiedSec = c.getLong(dateCol)
                )
            }
        }
        return result
    }
}
