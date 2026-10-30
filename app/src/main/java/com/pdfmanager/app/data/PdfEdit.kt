package com.pdfmanager.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.util.Matrix
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/** A mark the user placed on a page. Positions are fractions (0..1) of the page as it looks on screen, origin top-left. */
sealed interface PageMark {
    val page: Int
    data class Highlight(override val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int) : PageMark
    data class Note(override val page: Int, val x: Float, val y: Float, val text: String) : PageMark
}

class PaperSize(val label: String, val rect: PDRectangle)

/** Drawing on pages, resizing and poster tiling. */
object PdfEdit {

    val paperSizes = listOf(
        PaperSize("A3", PDRectangle.A3), PaperSize("A4", PDRectangle.A4), PaperSize("A5", PDRectangle.A5),
        PaperSize("Letter", PDRectangle.LETTER), PaperSize("Legal", PDRectangle.LEGAL)
    )

    private fun load(context: Context, uri: Uri): PDDocument =
        (context.contentResolver.openInputStream(uri) ?: error("Cannot open the file")).use { PdfOps.load(it) }

    // ------------------------------------------------------------ drawing in "screen" coordinates

    /**
     * The page as the person sees it (rotation applied): size [w] x [h], and the matrix a b c d e f that turns
     * a point drawn in that view (origin bottom-left) into the page's own coordinates.
     */
    private class View(val w: Float, val h: Float, val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
        fun matrix() = Matrix(a, b, c, d, e, f)
        fun toPageX(x: Float, y: Float) = a * x + c * y + e
        fun toPageY(x: Float, y: Float) = b * x + d * y + f
    }

    private fun view(page: PDPage): View {
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val w = box.width
        val h = box.height
        val ox = box.lowerLeftX
        val oy = box.lowerLeftY
        return when (rot) {
            90 -> View(h, w, 0f, 1f, -1f, 0f, w + ox, oy)
            180 -> View(w, h, -1f, 0f, 0f, -1f, w + ox, h + oy)
            270 -> View(h, w, 0f, -1f, 1f, 0f, ox, h + oy)
            else -> View(w, h, 1f, 0f, 0f, 1f, ox, oy)
        }
    }

    /** Appends drawing to a page. Inside [block] the origin is the bottom-left corner of the page as shown. */
    private fun overlay(doc: PDDocument, page: PDPage, block: (PDPageContentStream, View) -> Unit) {
        val v = view(page)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.transform(v.matrix())
            block(cs, v)
        }
    }

    // ------------------------------------------------------------ watermark / signature image

    /** Puts [art] on every page: [sizeFrac] of the page width, centered, turned [angleDeg] counter-clockwise. */
    fun watermark(context: Context, uri: Uri, out: OutputStream, art: Bitmap, sizeFrac: Float, opacity: Float, angleDeg: Float): Int {
        val doc = load(context, uri)
        try {
            val img = LosslessFactory.createFromImage(doc, art)
            val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = opacity }
            for (page in doc.pages) {
                overlay(doc, page) { cs, v ->
                    val w = v.w * sizeFrac
                    val h = w * art.height / art.width
                    cs.setGraphicsStateParameters(gs)
                    cs.transform(Matrix.getTranslateInstance(v.w / 2f, v.h / 2f))
                    cs.transform(Matrix.getRotateInstance(Math.toRadians(angleDeg.toDouble()), 0f, 0f))
                    cs.drawImage(img, -w / 2f, -h / 2f, w, h)
                }
            }
            doc.save(out)
            return doc.numberOfPages
        } finally {
            runCatching { doc.close() }
        }
    }

    /** Places [art] on the chosen [pages]. [cx], [cy] are the center as fractions of the page (origin top-left). */
    fun placeImage(context: Context, uri: Uri, out: OutputStream, art: Bitmap, pages: Set<Int>, cx: Float, cy: Float, widthFrac: Float): Int {
        val doc = load(context, uri)
        try {
            val img = LosslessFactory.createFromImage(doc, art)
            var done = 0
            for (i in pages.sorted()) {
                if (i !in 0 until doc.numberOfPages) continue
                overlay(doc, doc.getPage(i)) { cs, v ->
                    val w = v.w * widthFrac
                    val h = w * art.height / art.width
                    val x = (cx * v.w - w / 2f).coerceIn(0f, max(0f, v.w - w))
                    val y = (v.h - cy * v.h - h / 2f).coerceIn(0f, max(0f, v.h - h))
                    cs.drawImage(img, x, y, w, h)
                }
                done++
            }
            require(done > 0) { "No page to sign" }
            doc.save(out)
            return done
        } finally {
            runCatching { doc.close() }
        }
    }

    // ------------------------------------------------------------ page numbers

    /**
     * Writes a number on every page. [pos]: 0..2 top left/center/right, 3..5 bottom left/center/right.
     * [format]: 0 = "5", 1 = "Page 5", 2 = "5 / 12".
     */
    fun pageNumbers(context: Context, uri: Uri, out: OutputStream, pos: Int, start: Int, format: Int, sizePt: Float): Int {
        val doc = load(context, uri)
        try {
            val font = PDType1Font.HELVETICA
            val total = doc.numberOfPages
            val last = start + total - 1
            doc.pages.forEachIndexed { i, page ->
                val n = start + i
                val text = when (format) {
                    1 -> "Page $n"
                    2 -> "$n / $last"
                    else -> "$n"
                }
                overlay(doc, page) { cs, v ->
                    val tw = font.getStringWidth(text) / 1000f * sizePt
                    val margin = 28f
                    val x = when (pos % 3) {
                        0 -> margin
                        1 -> (v.w - tw) / 2f
                        else -> v.w - margin - tw
                    }
                    val y = if (pos < 3) v.h - margin - sizePt * 0.8f else margin
                    cs.beginText()
                    cs.setFont(font, sizePt)
                    cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
                    cs.newLineAtOffset(x, y)
                    cs.showText(text)
                    cs.endText()
                }
            }
            doc.save(out)
            return total
        } finally {
            runCatching { doc.close() }
        }
    }

    // ------------------------------------------------------------ highlights and notes

    fun applyMarks(context: Context, uri: Uri, out: OutputStream, marks: List<PageMark>): Int {
        val doc = load(context, uri)
        try {
            val total = doc.numberOfPages
            for ((pageNo, list) in marks.groupBy { it.page }) {
                if (pageNo !in 0 until total) continue
                val page = doc.getPage(pageNo)
                val highlights = list.filterIsInstance<PageMark.Highlight>()
                if (highlights.isNotEmpty()) {
                    val gs = PDExtendedGraphicsState().apply { setBlendMode(BlendMode.MULTIPLY) }
                    overlay(doc, page) { cs, v ->
                        cs.setGraphicsStateParameters(gs)
                        for (hl in highlights) {
                            val r = ((hl.color shr 16) and 0xFF) / 255f
                            val g = ((hl.color shr 8) and 0xFF) / 255f
                            val b = (hl.color and 0xFF) / 255f
                            cs.setNonStrokingColor(r, g, b)
                            val x = min(hl.left, hl.right) * v.w
                            val w = kotlin.math.abs(hl.right - hl.left) * v.w
                            val h = kotlin.math.abs(hl.bottom - hl.top) * v.h
                            val y = v.h - max(hl.top, hl.bottom) * v.h
                            cs.addRect(x, y, w, h)
                            cs.fill()
                        }
                    }
                }
                val notes = list.filterIsInstance<PageMark.Note>()
                if (notes.isNotEmpty()) {
                    val v = view(page)
                    val annots = page.annotations
                    for (n in notes) {
                        val vx = n.x * v.w
                        val vy = v.h - n.y * v.h
                        val px = v.toPageX(vx, vy)
                        val py = v.toPageY(vx, vy)
                        val a = PDAnnotationText()
                        a.contents = n.text
                        a.name = PDAnnotationText.NAME_NOTE
                        a.rectangle = PDRectangle(px, py - 20f, 20f, 20f)
                        a.color = PDColor(floatArrayOf(1f, 0.82f, 0.1f), PDDeviceRGB.INSTANCE)
                        annots.add(a)
                    }
                    page.annotations = annots
                }
            }
            doc.save(out)
            return marks.size
        } finally {
            runCatching { doc.close() }
        }
    }

    // ------------------------------------------------------------ resize paper

    private fun visualSize(p: PDPage): Pair<Float, Float> {
        val box = p.cropBox
        val rot = ((p.rotation % 360) + 360) % 360
        return if (rot == 90 || rot == 270) box.height to box.width else box.width to box.height
    }

    /** Draws a source page into a rectangle (tx, ty = where the page's bottom-left lands, scale = size factor). */
    private fun drawPage(cs: PDPageContentStream, form: com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject, sp: PDPage, scale: Float, x: Float, y: Float) {
        val box = sp.cropBox
        val rot = ((sp.rotation % 360) + 360) % 360
        val tx = if (rot == 0) x - box.lowerLeftX * scale else x
        val ty = if (rot == 0) y - box.lowerLeftY * scale else y
        cs.saveGraphicsState()
        cs.transform(Matrix(scale, 0f, 0f, scale, tx, ty))
        cs.drawForm(form)
        cs.restoreGraphicsState()
    }

    /** Every page is scaled onto [target]. Landscape pages get a landscape sheet. [fill] crops instead of leaving margins. */
    fun resize(context: Context, uri: Uri, out: OutputStream, target: PDRectangle, fill: Boolean): Int {
        val src = load(context, uri)
        val dst = PDDocument(PdfOps.memory())
        try {
            val layer = LayerUtility(dst)
            val long = max(target.width, target.height)
            val short = min(target.width, target.height)
            for (i in 0 until src.numberOfPages) {
                val sp = src.getPage(i)
                val (pw, ph) = visualSize(sp)
                val landscape = pw > ph
                val tw = if (landscape) long else short
                val th = if (landscape) short else long
                val page = PDPage(PDRectangle(tw, th))
                dst.addPage(page)
                val scale = if (fill) max(tw / pw, th / ph) else min(tw / pw, th / ph)
                val x = (tw - pw * scale) / 2f
                val y = (th - ph * scale) / 2f
                val form = layer.importPageAsForm(src, i)
                PDPageContentStream(dst, page).use { cs ->
                    cs.addRect(0f, 0f, tw, th)
                    cs.clip()
                    drawPage(cs, form, sp, scale, x, y)
                }
            }
            dst.save(out)
            return dst.numberOfPages
        } finally {
            runCatching { dst.close() }
            runCatching { src.close() }
        }
    }

    // ------------------------------------------------------------ poster

    /**
     * Spreads one page over [cols] x [rows] sheets of [sheet] size. Neighbouring sheets share [overlapPt]
     * so they can be glued. Sheets are in reading order (left to right, top to bottom).
     */
    fun poster(context: Context, uri: Uri, out: OutputStream, pageIndex: Int, cols: Int, rows: Int, overlapPt: Float, sheet: PDRectangle = PDRectangle.A4): Int {
        val src = load(context, uri)
        val dst = PDDocument(PdfOps.memory())
        try {
            val sp = src.getPage(pageIndex.coerceIn(0, src.numberOfPages - 1))
            val (pw, ph) = visualSize(sp)
            val landscape = pw > ph
            val tw = if (landscape) max(sheet.width, sheet.height) else min(sheet.width, sheet.height)
            val th = if (landscape) min(sheet.width, sheet.height) else max(sheet.width, sheet.height)
            val totalW = cols * tw - (cols - 1) * overlapPt
            val totalH = rows * th - (rows - 1) * overlapPt
            val scale = min(totalW / pw, totalH / ph)
            val ox = (totalW - pw * scale) / 2f
            val oy = (totalH - ph * scale) / 2f   // gap above the page, measured from the top
            val layer = LayerUtility(dst)
            val form = layer.importPageAsForm(src, pageIndex.coerceIn(0, src.numberOfPages - 1))
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val page = PDPage(PDRectangle(tw, th))
                    dst.addPage(page)
                    val x0 = c * (tw - overlapPt)
                    val y0 = r * (th - overlapPt)
                    PDPageContentStream(dst, page).use { cs ->
                        cs.addRect(0f, 0f, tw, th)
                        cs.clip()
                        drawPage(cs, form, sp, scale, ox - x0, th - oy - ph * scale + y0)
                    }
                }
            }
            dst.save(out)
            return dst.numberOfPages
        } finally {
            runCatching { dst.close() }
            runCatching { src.close() }
        }
    }
}

/** Pictures used for watermarks. Text is drawn by Android itself, so every script (Bangla included) works. */
object MarkArt {

    fun text(text: String, color: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 200f
            typeface = Typeface.DEFAULT_BOLD
        }
        val pad = 24
        val w = (paint.measureText(text).toInt() + pad * 2).coerceIn(48, 4000)
        val fm = paint.fontMetrics
        val h = (fm.descent - fm.ascent).toInt() + pad * 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, pad.toFloat(), pad - fm.ascent, paint)
        return bmp
    }

    /** A picked image, shrunk so its longest side is at most [maxSide] pixels. */
    fun image(context: Context, uri: Uri, maxSide: Int = 1400): Bitmap? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()
}
