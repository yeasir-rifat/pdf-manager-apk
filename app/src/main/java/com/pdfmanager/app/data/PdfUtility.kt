package com.pdfmanager.app.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Calendar

/** What a PDF stores about itself: the four editable fields and the names of everything that is present. */
class MetadataSnapshot(val fields: MetaFields, val found: List<String>)

/** Result of Repair PDF. [skipped] are 1-based page numbers that could not be read. */
class RepairResult(val pagesFound: Int, val pagesKept: Int, val skipped: List<Int>, val opens: Boolean)

/** Edit metadata and Repair PDF. */
object PdfUtility {

    private const val TMP_DIR = "repair-tmp"

    private fun open(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: error("Cannot open the file")

    /**
     * Opens a PDF for changing. A locked PDF is refused (use Lock / Unlock first), and so is a file that
     * cannot be read at all.
     */
    private fun loadForEdit(context: Context, uri: Uri): PDDocument {
        val doc = try {
            open(context, uri).use { PdfOps.load(it) }
        } catch (e: InvalidPasswordException) {
            throw IllegalStateException("This PDF is locked. Unlock it first with Lock / Unlock")
        } catch (e: IOException) {
            throw IllegalStateException("Cannot read this PDF. It is too damaged to open")
        }
        if (doc.isEncrypted) {
            runCatching { doc.close() }
            throw IllegalStateException("This PDF is protected. Unlock it first with Lock / Unlock")
        }
        return doc
    }

    // ------------------------------------------------------------ Edit metadata

    fun readMetadata(context: Context, uri: Uri): MetadataSnapshot {
        val doc = loadForEdit(context, uri)
        try {
            val info = doc.documentInformation
            val fields = MetaFields(
                info.title.orEmpty(), info.author.orEmpty(), info.subject.orEmpty(), info.keywords.orEmpty()
            )
            val standard = setOf(
                "Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate", "Trapped"
            )
            val custom = info.metadataKeys.count { it !in standard }
            val found = listOfNotNull(
                "Title".takeIf { fields.title.isNotBlank() },
                "Author".takeIf { fields.author.isNotBlank() },
                "Subject".takeIf { fields.subject.isNotBlank() },
                "Keywords".takeIf { fields.keywords.isNotBlank() },
                "Creator".takeIf { !info.creator.isNullOrBlank() },
                "Producer".takeIf { !info.producer.isNullOrBlank() },
                "Dates".takeIf { info.creationDate != null || info.modificationDate != null },
                "Custom ×$custom".takeIf { custom > 0 },
                "XMP".takeIf { doc.documentCatalog.metadata != null }
            )
            return MetadataSnapshot(fields, found)
        } finally {
            runCatching { doc.close() }
        }
    }

    /**
     * Writes a copy of the PDF with new [edit] values (only the fields that differ from [old] are touched,
     * a blank value removes the field), or, when [edit] is null, with the whole information dictionary
     * and the XMP block removed. Returns the names of what was changed or removed.
     *
     * An XMP block is dropped when fields are edited: viewers prefer it to the information dictionary,
     * so an old copy would keep showing the old title.
     */
    fun writeMetadata(context: Context, uri: Uri, out: OutputStream, old: MetaFields, edit: MetaFields?): List<String> {
        val doc = loadForEdit(context, uri)
        try {
            val changed: List<String>
            if (edit == null) {
                changed = readNames(doc)
                doc.documentInformation = PDDocumentInformation()
                doc.documentCatalog.metadata = null
            } else {
                val before = MetadataHelpers.cleaned(old)
                val after = MetadataHelpers.cleaned(edit)
                val info = doc.documentInformation
                if (after.title != before.title) info.title = after.title.ifEmpty { null }
                if (after.author != before.author) info.author = after.author.ifEmpty { null }
                if (after.subject != before.subject) info.subject = after.subject.ifEmpty { null }
                if (after.keywords != before.keywords) info.keywords = after.keywords.ifEmpty { null }
                info.modificationDate = Calendar.getInstance()
                val names = MetadataHelpers.changedNames(old, edit).toMutableList()
                if (doc.documentCatalog.metadata != null) {
                    doc.documentCatalog.metadata = null
                    names += "XMP removed"
                }
                changed = names
            }
            doc.save(out)
            return changed
        } finally {
            runCatching { doc.close() }
        }
    }

    private fun readNames(doc: PDDocument): List<String> {
        val info = doc.documentInformation
        val n = info.metadataKeys.size + if (doc.documentCatalog.metadata != null) 1 else 0
        return if (n == 0) emptyList() else listOf("$n items")
    }

    // ------------------------------------------------------------ Repair PDF

    /**
     * Quick fix ([rebuild] = false): reads the whole file with PDFBox's forgiving reader, which rebuilds a
     * broken cross-reference table, and writes it out again with a clean structure. Everything is kept.
     *
     * Rebuild pages ([rebuild] = true): copies the pages one by one into a new file and leaves out the ones
     * that cannot be read. Bookmarks, links and form fields are not kept.
     *
     * The result is written to a temporary file first and opened with the system viewer's own reader, so the
     * message can say honestly whether the file now opens. The temporary file is always deleted.
     */
    fun repair(
        context: Context, uri: Uri, rebuild: Boolean, out: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): RepairResult {
        val src = loadForEdit(context, uri)
        val dir = File(context.cacheDir, TMP_DIR).apply { mkdirs() }
        val tmp = File.createTempFile("repair", ".pdf", dir)
        try {
            val pages = ArrayList<PDPage>()
            // A damaged page tree can fail half way: the pages read so far are still used
            try {
                for (p in src.pages) pages += p
            } catch (e: Exception) {
                // keep what was read
            }
            if (pages.isEmpty()) throw IllegalStateException("No page could be read from this PDF")

            var kept = pages.size
            val skipped = ArrayList<Int>()
            if (!rebuild) {
                FileOutputStream(tmp).use { src.save(it) }
            } else {
                kept = 0
                val dest = PDDocument(PdfOps.memory())
                try {
                    pages.forEachIndexed { i, page ->
                        try {
                            dest.importPage(page)
                            kept++
                        } catch (e: Exception) {
                            skipped += i + 1
                        } catch (e: OutOfMemoryError) {
                            skipped += i + 1
                        }
                        onProgress(i + 1, pages.size)
                    }
                    if (kept == 0) throw IllegalStateException("No page could be read from this PDF")
                    FileOutputStream(tmp).use { dest.save(it) }
                } finally {
                    runCatching { dest.close() }
                }
            }
            val opens = opensInViewer(tmp)
            FileInputStream(tmp).use { it.copyTo(out) }
            return RepairResult(pages.size, kept, skipped, opens)
        } finally {
            runCatching { src.close() }
            tmp.delete()
        }
    }

    /** True when Android's own PDF reader (the one the viewer uses) can open the file and its first page. */
    private fun opensInViewer(file: File): Boolean = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.pageCount > 0 && r.openPage(0).use { true }
            }
        }
    } catch (e: Exception) {
        false
    }
}
