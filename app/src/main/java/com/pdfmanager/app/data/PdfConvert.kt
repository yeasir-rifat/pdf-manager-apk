package com.pdfmanager.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.IdentityHashMap

/** Text read from a PDF: the text, how many pages were read and how many of them had no text. */
class TextExtraction(val text: String, val pagesRead: Int, val emptyPages: Int)

/** Result of taking the pictures out of a PDF. */
class ImageExtraction(val saved: Int, val skippedSmall: Int, val skippedFailed: Int, val pagesRead: Int)

class PaperOption(val label: String, val width: Int, val height: Int)

/** PDF to Text, Extract images and Text to PDF. Page numbers are 0-based. */
object PdfConvert {

    private fun open(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: error("Cannot open the file")

    private fun load(context: Context, uri: Uri): PDDocument =
        try {
            open(context, uri).use { PdfOps.load(it) }
        } catch (e: InvalidPasswordException) {
            throw IllegalStateException("This PDF is locked. Unlock it first with Lock / Unlock")
        }

    // ------------------------------------------------------------ PDF to Text

    /**
     * Reads the text of [pages] (0-based, in the given order). With [byPosition] the words are sorted by their
     * place on the page; otherwise they come in the order the PDF draws them. [onProgress] gets (pages done, pages).
     * PDFs that are only pictures (scans) have no text and are refused: the app has no text recognition.
     */
    fun pdfToText(
        context: Context, uri: Uri, pages: List<Int>, byPosition: Boolean, marks: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): TextExtraction {
        require(pages.isNotEmpty()) { "Pick at least one page" }
        val doc = load(context, uri)
        try {
            val total = doc.numberOfPages
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(byPosition)
            stripper.setLineSeparator("\n")
            val parts = ArrayList<Pair<Int, String>>(pages.size)
            var empty = 0
            pages.forEachIndexed { k, p ->
                require(p in 0 until total) { "Wrong page number" }
                stripper.setStartPage(p + 1)
                stripper.setEndPage(p + 1)
                val text = ConvertHelpers.normalizeText(stripper.getText(doc))
                if (text.isBlank()) empty++
                parts += (p + 1) to text
                onProgress(k + 1, pages.size)
            }
            check(empty < pages.size) { "No text found. This PDF may be made of scanned pictures" }
            return TextExtraction(ConvertHelpers.assembleText(parts, marks), pages.size, empty)
        } finally {
            runCatching { doc.close() }
        }
    }

    // ------------------------------------------------------------ Extract images

    /** Collects the pictures of one resource dictionary, also those inside form XObjects. Each picture only once. */
    private fun collectImages(
        res: PDResources?, seen: MutableSet<COSBase>, forms: MutableSet<COSBase>, depth: Int, into: MutableList<PDImageXObject>
    ) {
        if (res == null || depth > 8) return
        for (name in res.xObjectNames.toList()) {
            val x = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            when (x) {
                is PDImageXObject -> if (seen.add(x.cosObject)) into += x
                is PDFormXObject -> if (forms.add(x.cosObject)) collectImages(x.resources, seen, forms, depth + 1, into)
                else -> {}
            }
        }
    }

    /** JPEG has no transparency: transparent areas are put on white instead of turning black. */
    private fun onWhite(src: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return flat
    }

    private fun saveOne(context: Context, img: PDImageXObject, png: Boolean, fileName: String) {
        val bmp = img.image
        val flat = if (!png && bmp.hasAlpha()) onWhite(bmp) else bmp
        try {
            OutputStore.saveImage(context, fileName, if (png) "image/png" else "image/jpeg") { os ->
                val ok = flat.compress(
                    if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 95, os
                )
                if (!ok) throw IOException("Could not encode the picture")
            }
        } finally {
            // The decoded picture may be cached inside the PDF library, so only the copy made here is freed
            if (flat !== bmp) flat.recycle()
        }
    }

    /**
     * Saves the pictures found on [pages] into Pictures/PDF Manager as PNG or JPG (re-encoded; JPG quality 95).
     * A picture used on several pages is saved once, on the first page. Pictures whose both sides are shorter
     * than [minPx] are skipped (0 keeps all). [onProgress] gets (pages done, pages, pictures saved).
     */
    fun extractImages(
        context: Context, uri: Uri, pages: List<Int>, png: Boolean, minPx: Int, sourceName: String,
        onProgress: (done: Int, total: Int, saved: Int) -> Unit = { _, _, _ -> }
    ): ImageExtraction {
        require(pages.isNotEmpty()) { "Pick at least one page" }
        val doc = load(context, uri)
        try {
            val total = doc.numberOfPages
            val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
            val forms = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
            val ext = if (png) "png" else "jpg"
            var saved = 0
            var small = 0
            var failed = 0
            pages.forEachIndexed { k, p ->
                require(p in 0 until total) { "Wrong page number" }
                val found = ArrayList<PDImageXObject>()
                collectImages(doc.getPage(p).resources, seen, forms, 0, found)
                var onPage = 0
                for (img in found) {
                    val w = runCatching { img.width }.getOrDefault(0)
                    val h = runCatching { img.height }.getOrDefault(0)
                    if (w <= 0 || h <= 0) { failed++; continue }
                    if (ConvertHelpers.isSmall(w, h, minPx)) { small++; continue }
                    if (ConvertHelpers.tooManyPixels(w, h)) { failed++; continue }
                    val name = ConvertHelpers.imageFileName(sourceName, p + 1, onPage + 1, ext)
                    val ok = try {
                        saveOne(context, img, png, name)
                        true
                    } catch (e: Exception) {
                        false
                    } catch (e: OutOfMemoryError) {
                        false
                    }
                    if (ok) { onPage++; saved++ } else failed++
                }
                onProgress(k + 1, pages.size, saved)
            }
            if (saved == 0) {
                throw IllegalStateException(
                    when {
                        failed > 0 -> "No picture could be saved ($failed could not be read)"
                        small > 0 -> "No picture of this size. Choose a smaller size"
                        else -> "No pictures found in these pages"
                    }
                )
            }
            return ImageExtraction(saved, small, failed, pages.size)
        } finally {
            runCatching { doc.close() }
        }
    }

    // ------------------------------------------------------------ Text to PDF

    val papers = listOf(PaperOption("A4", 595, 842), PaperOption("A5", 420, 595), PaperOption("Letter", 612, 792))

    const val FONT_SANS = 0
    const val FONT_SERIF = 1
    const val FONT_MONO = 2

    /** Reads a text file chosen by the person. Throws a readable error when it is too big. */
    fun readTextFile(context: Context, uri: Uri): String {
        val bytes = open(context, uri).use { ConvertHelpers.readLimited(it, ConvertHelpers.MAX_TEXT_BYTES) }
        val text = ConvertHelpers.normalizeText(ConvertHelpers.decodeText(bytes))
        check(text.length <= ConvertHelpers.MAX_TEXT_CHARS) {
            "This text is too long (over ${ConvertHelpers.count(ConvertHelpers.MAX_TEXT_CHARS)} characters)"
        }
        return text
    }

    /**
     * Writes [text] as a PDF on [paper]: [fontPt] sized text inside [marginPt] margins, broken into lines and
     * pages. The text is drawn by Android's own text engine, so Bangla and other complex scripts are shaped
     * correctly (the phone's fonts are used). Returns the page count; [onProgress] gets (page, pages).
     */
    fun textToPdf(
        text: String, out: OutputStream, paper: PaperOption, fontPt: Int, marginPt: Int, family: Int,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Int {
        val body = ConvertHelpers.normalizeText(text).trimEnd()
        require(body.isNotBlank()) { "Write or choose some text first" }
        check(body.length <= ConvertHelpers.MAX_TEXT_CHARS) {
            "This text is too long (over ${ConvertHelpers.count(ConvertHelpers.MAX_TEXT_CHARS)} characters)"
        }
        val contentW = paper.width - 2 * marginPt
        val contentH = paper.height - 2 * marginPt
        require(contentW >= 100 && contentH >= 100) { "The margins leave no room for text" }

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = fontPt.toFloat()
            typeface = when (family) {
                FONT_SERIF -> Typeface.SERIF
                FONT_MONO -> Typeface.MONOSPACE
                else -> Typeface.SANS_SERIF
            }
        }
        val layout = StaticLayout.Builder.obtain(body, 0, body.length, paint, contentW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.2f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        val lines = layout.lineCount
        val tops = IntArray(lines) { layout.getLineTop(it) }
        val bottoms = IntArray(lines) { layout.getLineTop(it + 1) }
        val spans = ConvertHelpers.pageBreaks(tops, bottoms, contentH)

        val doc = PdfDocument()
        try {
            spans.forEachIndexed { i, s ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(paper.width, paper.height, i + 1).create())
                val canvas = page.canvas
                val top = tops[s.start]
                canvas.save()
                canvas.translate(marginPt.toFloat(), (marginPt - top).toFloat())
                // Only the lines of this page: the next lines must not show at the bottom edge
                canvas.clipRect(0f, top.toFloat(), contentW.toFloat(), bottoms[s.endExclusive - 1].toFloat())
                layout.draw(canvas)
                canvas.restore()
                doc.finishPage(page)
                onProgress(i + 1, spans.size)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
        return spans.size
    }
}
