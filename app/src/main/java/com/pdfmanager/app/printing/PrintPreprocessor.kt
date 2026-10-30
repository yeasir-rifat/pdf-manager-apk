package com.pdfmanager.app.printing

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import com.pdfmanager.app.data.MarkArt
import com.pdfmanager.app.data.PdfLayout
import com.pdfmanager.app.data.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * Step 9. Builds a temporary PDF for printing when the user asked for something the Android
 * print system cannot do by itself: pages per sheet, booklet, watermark, page numbers,
 * reverse order or manual two-sided printing.
 *
 * - The original file is only read, never changed.
 * - Output goes to the app cache folder (cache/print-tmp). The caller deletes it when the print
 *   job ends ([PdfPrintDocumentAdapter] does this), and old leftovers are removed on every run.
 * - Order of work: pick pages, add watermark and page numbers, arrange N-up or booklet, and last
 *   reorder the sheets (reverse / manual two-sided).
 *   So page numbers appear on every small page, and booklet pages follow the chosen pages.
 *
 * Runs file and PDF work: call it from a background thread.
 */
object PrintPreprocessor {

    /** [pageCount] sheets will be printed now; [passTotal] is the whole pass (they differ when resuming). */
    class Prepared(val file: File, val pageCount: Int, val passTotal: Int = pageCount) {
        val uri: Uri get() = Uri.fromFile(file)
    }

    /** Folder name inside the app cache that holds the temporary print PDFs. */
    const val TEMP_DIR = "print-tmp"
    private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000
    private const val MAX_WATERMARK_HEIGHT = 170f

    fun prepare(
        context: Context,
        source: Uri,
        selectedPages: List<Int>?,
        settings: PrintSettings,
        /** Which pass of guided two-sided printing to build. Null means a normal job. */
        pass: DuplexPass? = null,
        /** Sheets of this pass that already came out (resume after a stop). */
        skipSheets: Int = 0
    ): Prepared {
        val dir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
        removeStale(dir)

        val watermark = settings.watermarkText.trim()
        require(PrintTransform.watermarkError(watermark) == null) { "Invalid watermark text" }
        val needsMarks = watermark.isNotEmpty() || settings.pageNumbers
        val needsStage1 = selectedPages != null || needsMarks
        val arrange = settings.layout != LayoutChoice.NORMAL
        val reorder = PageOrder.needsReorder(settings.reverseOrder, settings.manualDuplex) || PrintTransform.copiesApply(settings)

        // Every temporary file made here, so all of them can be removed if something fails.
        val temps = mutableListOf<File>()
        try {
            var currentUri = source
            var current: File? = null
            var pages = 0
            var passTotal = 0
            if (needsStage1) {
                val f1 = File.createTempFile("print-", ".pdf", dir)
                temps += f1
                val input = context.contentResolver.openInputStream(source)
                    ?: error("The PDF is no longer available")
                pages = input.use { ins ->
                    PdfOps.load(ins).use { doc -> writeMarked(doc, selectedPages, watermark, settings.pageNumbers, f1) }
                }
                current = f1
                currentUri = Uri.fromFile(f1)
            }
            if (arrange) {
                val f2 = File.createTempFile("print-", ".pdf", dir)
                temps += f2
                pages = FileOutputStream(f2).use { out ->
                    when (settings.layout) {
                        LayoutChoice.BOOKLET -> PdfLayout.booklet(context, currentUri, out)
                        else -> {
                            val layout = PdfLayout.nupOptions.firstOrNull { it.perSheet == settings.pagesPerSheet }
                                ?: PdfLayout.nupOptions[1]
                            PdfLayout.nup(context, currentUri, out, layout, settings.layoutBorder)
                        }
                    }
                }
                current = f2
                currentUri = Uri.fromFile(f2)
            }
            if (reorder) {
                // Last stage: works on the final sheets, so reverse and manual two-sided
                // follow what is really printed (after N-up or booklet).
                val f3 = File.createTempFile("print-", ".pdf", dir)
                temps += f3
                val input = context.contentResolver.openInputStream(currentUri)
                    ?: error("The PDF is no longer available")
                val written = input.use { ins ->
                    PdfOps.load(ins).use { doc -> writeOrdered(doc, settings, pass, skipSheets, f3) }
                }
                pages = written.first
                passTotal = written.second
                current = f3
            }
            val result = current ?: error("Nothing to prepare")
            temps.filter { it != result }.forEach { it.delete() }
            return Prepared(result, pages, if (passTotal > 0) passTotal else pages)
        } catch (e: Throwable) {
            temps.forEach { it.delete() }
            throw e
        }
    }

    /**
     * Rewrites [doc] with its pages in the order given by [PageOrder] and adds blank pages where
     * asked. Returns (sheets written now, sheets in the whole pass).
     */
    private fun writeOrdered(
        doc: PDDocument,
        settings: PrintSettings,
        pass: DuplexPass?,
        skipSheets: Int,
        target: File
    ): Pair<Int, Int> {
        val count = doc.numberOfPages
        val guided = settings.manualDuplex == ManualDuplexChoice.ON
        val full = if (guided) {
            PageOrder.duplexPass(count, pass ?: DuplexPass.FIRST, settings.stackStyle)
        } else {
            PageOrder.repeated(PageOrder.sequence(count, settings.reverseOrder), if (PrintTransform.copiesApply(settings)) settings.copies else 1)
        }
        val sequence = PageOrder.resume(full, skipSheets) ?: error("No pages to print")
        val turn = guided && pass == DuplexPass.SECOND && settings.turnSecondPass
        val original = (0 until count).map { doc.getPage(it) }
        for (i in count - 1 downTo 0) doc.removePage(i)
        // A blank page takes the size of the last page, so it fits the same paper.
        val blankSize = original.last().mediaBox
        val used = HashSet<Int>()
        for (index in sequence) {
            if (index == PageOrder.BLANK) {
                doc.addPage(PDPage(blankSize))
            } else if (used.add(index)) {
                val page = original[index]
                if (turn) page.rotation = ((page.rotation % 360) + 180) % 360
                doc.addPage(page)
            } else {
                // Another copy of a page that is already in the document.
                doc.importPage(original[index])
            }
        }
        FileOutputStream(target).use { out ->
            doc.save(out)
            out.flush()
        }
        return sequence.size to full.size
    }

    /**
     * A 4-page sample PDF for checking how a printer handles two-sided printing by hand.
     * Every page says which side of which sheet it belongs to and where its top is.
     * The caller deletes the file (it lives in cache/print-tmp too).
     */
    fun makeTestPdf(context: Context): File {
        val dir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
        val file = File.createTempFile("test-", ".pdf", dir)
        try {
            PDDocument().use { doc ->
                for (n in 1..4) {
                    val page = PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)
                    doc.addPage(page)
                    val sheet = (n + 1) / 2
                    val side = if (n % 2 == 1) "FRONT" else "BACK"
                    PDPageContentStream(doc, page).use { cs ->
                        val h = page.mediaBox.height
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA_BOLD, 18f)
                        cs.newLineAtOffset(50f, h - 50f)
                        cs.showText("TOP OF PAGE")
                        cs.endText()
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA_BOLD, 72f)
                        cs.newLineAtOffset(50f, h / 2f)
                        cs.showText("PAGE $n")
                        cs.endText()
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, 24f)
                        cs.newLineAtOffset(50f, h / 2f - 50f)
                        cs.showText("Sheet $sheet, $side")
                        cs.endText()
                    }
                }
                FileOutputStream(file).use { out -> doc.save(out); out.flush() }
            }
            return file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    /** Deletes a temporary file made by [prepare]. Safe to call more than once. */
    fun discard(file: File?) {
        runCatching { file?.delete() }
    }

    private fun removeStale(dir: File) {
        val limit = System.currentTimeMillis() - STALE_AFTER_MS
        dir.listFiles()?.forEach { if (it.lastModified() < limit) runCatching { it.delete() } }
    }

    /** Keeps only [selected] pages (0-based), draws the marks, and saves to [target]. Returns the page count. */
    private fun writeMarked(
        doc: PDDocument,
        selected: List<Int>?,
        watermark: String,
        pageNumbers: Boolean,
        target: File
    ): Int {
        if (selected != null) {
            val keep = selected.toSet()
            for (i in doc.numberOfPages - 1 downTo 0) {
                if (i !in keep) doc.removePage(i)
            }
        }
        val total = doc.numberOfPages
        if (watermark.isNotEmpty() || pageNumbers) {
            // The watermark is drawn by Android itself as a picture, so every script (Bangla included) works.
            // The built-in PDF fonts only know plain English letters.
            var bitmap: Bitmap? = null
            try {
                var art: PDImageXObject? = null
                if (watermark.isNotEmpty()) {
                    val bmp = MarkArt.text(watermark, Color.rgb(115, 115, 115))
                    bitmap = bmp
                    art = LosslessFactory.createFromImage(doc, bmp)
                }
                for (i in 0 until total) drawMarks(doc, doc.getPage(i), i + 1, total, art, pageNumbers)
            } finally {
                bitmap?.recycle()
            }
        }
        FileOutputStream(target).use { out ->
            doc.save(out)
            out.flush()
        }
        return total
    }

    private fun drawMarks(doc: PDDocument, page: PDPage, number: Int, total: Int, art: PDImageXObject?, pageNumbers: Boolean) {
        val box = page.cropBox
        val w = box.width
        val h = box.height
        val rot = ((page.rotation % 360) + 360) % 360
        val sideways = rot == 90 || rot == 270
        // Size of the page as it is seen on screen / paper (after the page's own rotation).
        val seenW = if (sideways) h else w
        val seenH = if (sideways) w else h
        // Maps "upright" drawing coordinates onto the page's own coordinates, so marks are never rotated.
        val toPage = when (rot) {
            90 -> Matrix(0f, 1f, -1f, 0f, w + box.lowerLeftX, box.lowerLeftY)
            180 -> Matrix(-1f, 0f, 0f, -1f, w + box.lowerLeftX, h + box.lowerLeftY)
            270 -> Matrix(0f, -1f, 1f, 0f, box.lowerLeftX, h + box.lowerLeftY)
            else -> Matrix(1f, 0f, 0f, 1f, box.lowerLeftX, box.lowerLeftY)
        }
        // resetContext = true wraps the page's own drawing in q/Q so our transform starts clean.
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            if (art != null) {
                val aspect = art.width.toFloat() / art.height.toFloat()
                // Up to 1.2x the shorter side of the page along the diagonal, but never taller than a 120 pt text line.
                var drawW = min(seenW, seenH) * 1.2f
                var drawH = drawW / aspect
                if (drawH > MAX_WATERMARK_HEIGHT) {
                    drawH = MAX_WATERMARK_HEIGHT
                    drawW = drawH * aspect
                }
                val gs = PDExtendedGraphicsState().also { it.nonStrokingAlphaConstant = 0.25f }
                cs.saveGraphicsState()
                cs.transform(toPage)
                cs.setGraphicsStateParameters(gs)
                cs.transform(Matrix.getTranslateInstance(seenW / 2f, seenH / 2f))
                cs.transform(Matrix.getRotateInstance(Math.toRadians(45.0), 0f, 0f))
                cs.drawImage(art, -drawW / 2f, -drawH / 2f, drawW, drawH)
                cs.restoreGraphicsState()
            }
            if (pageNumbers) {
                val font = PDType1Font.HELVETICA
                val label = "$number / $total"
                val size = 10f
                val textWidth = font.getStringWidth(label) / 1000f * size
                cs.saveGraphicsState()
                cs.transform(toPage)
                cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
                cs.beginText()
                cs.setFont(font, size)
                cs.newLineAtOffset((seenW - textWidth) / 2f, 18f)
                cs.showText(label)
                cs.endText()
                cs.restoreGraphicsState()
            }
        }
    }
}
