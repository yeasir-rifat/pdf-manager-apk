package com.pdfmanager.app.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** A result file this app saved earlier. */
class OutputFile(val uri: Uri, val name: String, val modifiedSec: Long, val size: Long)

/** Saves results into Documents/PDF Manager (PDFs) or Pictures/PDF Manager (images). */
object OutputStore {
    private const val APP_DIR = "PDF Manager"

    /** PDFs saved in Documents/PDF Manager, newest first. */
    fun recentPdfs(context: Context, limit: Int): List<OutputFile> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?" to
                arrayOf("Documents/$APP_DIR/%", "application/pdf")
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?" to
                arrayOf("%/Documents/$APP_DIR/%", "application/pdf")
        }
        val out = ArrayList<OutputFile>()
        context.contentResolver.query(
            collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out.add(
                    OutputFile(
                        android.content.ContentUris.withAppendedId(collection, c.getLong(0)),
                        c.getString(1) ?: continue, c.getLong(2), c.getLong(3)
                    )
                )
            }
        }
        if (SaveFolder.get(context) != null) {
            return (out + customPdfs(context)).sortedByDescending { it.modifiedSec }.take(limit)
        }
        return out
    }

    fun savePdf(context: Context, fileName: String, write: (OutputStream) -> Unit): Uri =
        save(
            context, fileName, "application/pdf",
            "Documents/$APP_DIR", Environment.DIRECTORY_DOCUMENTS,
            MediaStore.Files.getContentUri("external"), write
        )

    /** Plain text (UTF-8) in Documents/PDF Manager. */
    fun saveText(context: Context, fileName: String, write: (OutputStream) -> Unit): Uri =
        save(
            context, fileName, "text/plain",
            "Documents/$APP_DIR", Environment.DIRECTORY_DOCUMENTS,
            MediaStore.Files.getContentUri("external"), write
        )

    fun saveImage(context: Context, fileName: String, mime: String, write: (OutputStream) -> Unit): Uri =
        save(
            context, fileName, mime,
            "Pictures/$APP_DIR", Environment.DIRECTORY_PICTURES,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, write
        )

    private fun save(
        context: Context, fileName: String, mime: String, relativePath: String,
        legacyDirType: String, collection: Uri, write: (OutputStream) -> Unit
    ): Uri {
        // A folder picked in Settings wins; if it cannot be written (deleted, access removed), fall back to the default
        SaveFolder.get(context)?.let { tree ->
            saveToTree(context, tree, fileName, mime, write)?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("Could not create the output file")
            try {
                (resolver.openOutputStream(uri) ?: error("Could not open the output file")).use(write)
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(legacyDirType), APP_DIR).apply { mkdirs() }
        var file = File(dir, fileName)
        // Never overwrite an existing file: add " (1)", " (2)"... like the system does on newer Android
        if (file.exists()) {
            val base = fileName.substringBeforeLast('.', fileName)
            val ext = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var n = 1
            while (File(dir, "$base ($n)$ext").exists()) n++
            file = File(dir, "$base ($n)$ext")
        }
        val savedName = file.name
        FileOutputStream(file).use(write)
        MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
        // Register the file right away so we can hand out a content:// Uri.
        // A file:// Uri cannot be shared with other apps on Android 7+.
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, file.path)
            put(MediaStore.MediaColumns.DISPLAY_NAME, savedName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.SIZE, file.length())
        }
        val inserted = runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
        return inserted ?: Uri.fromFile(file)
    }

    /** Writes into the folder the person chose. Returns null when the folder cannot be used. */
    private fun saveToTree(context: Context, tree: Uri, fileName: String, mime: String, write: (OutputStream) -> Unit): Uri? {
        val resolver = context.contentResolver
        val parent = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull() ?: return null
        val doc = runCatching { DocumentsContract.createDocument(resolver, parent, mime, fileName) }.getOrNull() ?: return null
        try {
            (resolver.openOutputStream(doc) ?: error("Could not open the output file")).use(write)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        return doc
    }

    /** PDFs in the chosen folder (top level only), as [OutputFile]s. */
    private fun customPdfs(context: Context): List<OutputFile> {
        val tree = SaveFolder.get(context) ?: return emptyList()
        val resolver = context.contentResolver
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<OutputFile>()
        runCatching {
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(4) != "application/pdf") continue
                    out.add(
                        OutputFile(
                            DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                            c.getString(1) ?: continue, c.getLong(2) / 1000, c.getLong(3)
                        )
                    )
                }
            }
        }
        return out
    }
}
