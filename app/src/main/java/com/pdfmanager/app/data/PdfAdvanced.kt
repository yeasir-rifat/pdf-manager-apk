package com.pdfmanager.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CountingOutputStream(private val inner: OutputStream) : OutputStream() {
    var count = 0L
        private set
    override fun write(b: Int) { inner.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
    override fun flush() = inner.flush()
    override fun close() = inner.close()
}

private fun openStream(context: Context, uri: Uri) =
    context.contentResolver.openInputStream(uri) ?: error("Cannot open the PDF")

/** Shrinks a PDF by re-encoding its embedded images as smaller JPEGs. Text and vectors are untouched. */
object PdfCompressor {
    enum class Level(val label: String, val quality: Int, val maxDim: Int) {
        LIGHT("Light", 80, 2200),
        MEDIUM("Medium", 60, 1600),
        STRONG("Strong", 40, 1100)
    }

    /** Strength 0..100 -> JPEG quality. 0 = almost no change, 100 = smallest file. */
    fun qualityFor(strength: Int): Int = (95 - strength.coerceIn(0, 100) * 0.75).roundToInt().coerceIn(20, 95)

    /** Strength 0..100 -> longest allowed image side in pixels. */
    fun maxDimFor(strength: Int): Int = 2600 - strength.coerceIn(0, 100) * 16

    fun compress(context: Context, uri: Uri, out: OutputStream, level: Level): String =
        compress(context, uri, out, level.quality, level.maxDim)

    fun compressAtStrength(context: Context, uri: Uri, out: OutputStream, strength: Int): String =
        compress(context, uri, out, qualityFor(strength), maxDimFor(strength))

    fun compress(context: Context, uri: Uri, out: OutputStream, quality: Int, maxDim: Int): String {
        val originalSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        val counter = CountingOutputStream(out)
        var replaced = 0
        var total = 0

        openStream(context, uri).use { input ->
            PdfOps.load(input).use { doc ->
                val cache = IdentityHashMap<COSBase, PDImageXObject?>()
                val visited = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())

                fun process(res: PDResources?) {
                    if (res == null) return
                    for (name in res.xObjectNames.toList()) {
                        val x = runCatching { res.getXObject(name) }.getOrNull() ?: continue
                        when (x) {
                            is PDImageXObject -> {
                                total++
                                val key = x.cosObject
                                val newImg = if (cache.containsKey(key)) cache[key]
                                else recompress(doc, x, quality, maxDim).also { cache[key] = it }
                                if (newImg != null) { res.put(name, newImg); replaced++ }
                            }
                            is PDFormXObject -> if (visited.add(x.cosObject)) process(x.resources)
                            else -> {}
                        }
                    }
                }
                for (page in doc.pages) process(page.resources)
                doc.save(counter)
            }
        }

        if (replaced == 0) {
            return "No images could be reduced. PDFs that are mostly text are already compact."
        }
        val newSize = counter.count
        val saved = if (originalSize > 0) ((1 - newSize.toDouble() / originalSize) * 100).toInt() else 0
        return "Reduced from ${Formatter.formatFileSize(context, originalSize)} to " +
            "${Formatter.formatFileSize(context, newSize)} (-$saved%). Optimized $replaced of $total images."
    }

    /** Strengths at which the estimate is measured; values in between are interpolated. */
    private val STRENGTH_POINTS = intArrayOf(0, 25, 50, 75, 100)

    /** What compression is expected to do to one PDF, measured by trial-encoding its biggest images. */
    class Estimate(
        val originalSize: Long,
        private val eligibleBytes: Long,
        private val ratios: DoubleArray,
    ) {
        /** False when the PDF has no images worth shrinking (for example a text-only PDF). */
        val reducible: Boolean get() = eligibleBytes > 0 && ratios.size == STRENGTH_POINTS.size

        fun sizeAt(strength: Int): Long {
            if (!reducible) return originalSize
            val f = strength.coerceIn(0, 100) / 25.0
            val i = f.toInt().coerceAtMost(STRENGTH_POINTS.size - 2)
            val ratio = ratios[i] + (ratios[i + 1] - ratios[i]) * (f - i)
            return (originalSize - eligibleBytes * (1 - ratio)).toLong().coerceIn(0L, originalSize)
        }
    }

    private fun canRecompress(img: PDImageXObject): Boolean =
        runCatching { !img.isStencil && img.softMask == null && img.mask == null }.getOrDefault(false)

    private fun encodedSize(src: Bitmap, quality: Int, maxDim: Int): Long {
        var bmp = src
        val longest = max(src.width, src.height)
        if (longest > maxDim) {
            val s = maxDim.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(
                src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true
            )
        }
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        if (bmp !== src) bmp.recycle()
        return baos.size().toLong()
    }

    /**
     * Estimates the result size without saving anything. It re-encodes the three biggest images at
     * five strengths, and applies the measured ratios to all images. The number is approximate.
     */
    fun analyze(context: Context, uri: Uri, cancelled: () -> Boolean = { false }): Estimate {
        val originalSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        val found = ArrayList<Pair<PDImageXObject, Long>>()
        var estimate = Estimate(originalSize, 0L, DoubleArray(0))

        openStream(context, uri).use { input ->
            PdfOps.load(input).use { doc ->
                val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
                val visited = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())

                fun collect(res: PDResources?) {
                    if (res == null || cancelled()) return
                    for (name in res.xObjectNames.toList()) {
                        val x = runCatching { res.getXObject(name) }.getOrNull() ?: continue
                        if (x is PDImageXObject) {
                            if (seen.add(x.cosObject) && canRecompress(x)) {
                                val raw = runCatching {
                                    x.cosObject.createRawInputStream().use { it.readBytes().size.toLong() }
                                }.getOrDefault(0L)
                                if (raw > 0) found.add(x to raw)
                            }
                        } else if (x is PDFormXObject) {
                            if (visited.add(x.cosObject)) collect(x.resources)
                        }
                    }
                }
                for (page in doc.pages) collect(page.resources)
                if (cancelled()) return@use

                val eligibleBytes = found.sumOf { it.second }
                val samples = found.sortedByDescending { it.second }.filter { it.second >= 15_000 }.take(3)
                val sumEff = DoubleArray(STRENGTH_POINTS.size)
                var sumRaw = 0.0
                for ((img, raw) in samples) {
                    if (cancelled()) break
                    val src = runCatching { img.image }.getOrNull() ?: continue
                    sumRaw += raw
                    for ((k, st) in STRENGTH_POINTS.withIndex()) {
                        val bytes = runCatching { encodedSize(src, qualityFor(st), maxDimFor(st)) }.getOrDefault(raw)
                        // Same rule as the real run: a result that is not clearly smaller keeps the original
                        sumEff[k] += if (bytes >= raw * 0.9) raw.toDouble() else bytes.toDouble()
                    }
                }
                if (sumRaw > 0) {
                    val ratios = DoubleArray(STRENGTH_POINTS.size) { (sumEff[it] / sumRaw).coerceIn(0.0, 1.0) }
                    for (k in 1 until ratios.size) ratios[k] = min(ratios[k], ratios[k - 1])
                    estimate = Estimate(originalSize, eligibleBytes, ratios)
                }
            }
        }
        return estimate
    }

    private fun recompress(doc: PDDocument, img: PDImageXObject, quality: Int, maxDim: Int): PDImageXObject? =
        runCatching {
            // Masked or transparent images would lose their mask when saved as plain JPEG
            if (img.isStencil || img.softMask != null || img.mask != null) return@runCatching null
            val originalBytes = img.cosObject.createRawInputStream().use { it.readBytes().size }
            val src = img.image ?: return@runCatching null
            var bmp = src
            val longest = max(src.width, src.height)
            if (longest > maxDim) {
                val s = maxDim.toFloat() / longest
                bmp = Bitmap.createScaledBitmap(
                    src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true
                )
            }
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            if (bmp !== src) bmp.recycle()
            val bytes = baos.toByteArray()
            if (bytes.size >= originalBytes * 0.9) null
            else JPEGFactory.createFromStream(doc, ByteArrayInputStream(bytes))
        }.getOrNull()
}

/** Page layouts: several pages per sheet, and booklet imposition. */
object PdfLayout {
    class NupLayout(val perSheet: Int, val cols: Int, val rows: Int, val landscape: Boolean)

    val nupOptions = listOf(
        NupLayout(2, 2, 1, true),
        NupLayout(4, 2, 2, false),
        NupLayout(6, 3, 2, true),
        NupLayout(9, 3, 3, false)
    )

    private const val A4_W = 595f
    private const val A4_H = 842f

    fun nup(context: Context, uri: Uri, out: OutputStream, layout: NupLayout, border: Boolean): Int =
        openStream(context, uri).use { input ->
            PdfOps.load(input).use { src ->
                val dest = PDDocument(PdfOps.memory())
                try {
                    val layer = LayerUtility(dest)
                    val n = src.numberOfPages
                    val (w, h) = if (layout.landscape) A4_H to A4_W else A4_W to A4_H
                    var i = 0
                    while (i < n) {
                        val idx = (0 until layout.perSheet).map { k -> (i + k).takeIf { it < n } }
                        drawSheet(dest, layer, src, w, h, layout.cols, layout.rows, idx, 20f, 8f, border)
                        i += layout.perSheet
                    }
                    dest.save(out)
                    dest.numberOfPages
                } finally {
                    dest.close()
                }
            }
        }

    /** Reorders pages for saddle-stitch printing on landscape sheets. Returns the sheet-side count. */
    fun booklet(context: Context, uri: Uri, out: OutputStream): Int =
        openStream(context, uri).use { input ->
            PdfOps.load(input).use { src ->
                val dest = PDDocument(PdfOps.memory())
                try {
                    val layer = LayerUtility(dest)
                    val n = src.numberOfPages
                    val padded = ((n + 3) / 4) * 4
                    fun page(i: Int): Int? = i.takeIf { it < n }
                    for (s in 0 until padded / 4) {
                        val front = listOf(page(padded - 1 - 2 * s), page(2 * s))
                        val back = listOf(page(2 * s + 1), page(padded - 2 - 2 * s))
                        drawSheet(dest, layer, src, A4_H, A4_W, 2, 1, front, 10f, 0f, false)
                        drawSheet(dest, layer, src, A4_H, A4_W, 2, 1, back, 10f, 0f, false)
                    }
                    dest.save(out)
                    dest.numberOfPages
                } finally {
                    dest.close()
                }
            }
        }

    private fun drawSheet(
        dest: PDDocument, layer: LayerUtility, src: PDDocument,
        sheetW: Float, sheetH: Float, cols: Int, rows: Int, indices: List<Int?>,
        margin: Float, gap: Float, border: Boolean
    ) {
        val sheet = PDPage(PDRectangle(sheetW, sheetH))
        dest.addPage(sheet)
        val cellW = (sheetW - 2 * margin - (cols - 1) * gap) / cols
        val cellH = (sheetH - 2 * margin - (rows - 1) * gap) / rows
        PDPageContentStream(dest, sheet).use { cs ->
            indices.forEachIndexed { k, idx ->
                if (idx == null) return@forEachIndexed // blank slot (booklet padding)
                val r = k / cols
                val c = k % cols
                val cellX = margin + c * (cellW + gap)
                val cellY = sheetH - margin - (r + 1) * cellH - r * gap
                val sp = src.getPage(idx)
                val box = sp.cropBox
                val rot = ((sp.rotation % 360) + 360) % 360
                val pw = if (rot == 90 || rot == 270) box.height else box.width
                val ph = if (rot == 90 || rot == 270) box.width else box.height
                val scale = min(cellW / pw, cellH / ph)
                val dw = pw * scale
                val dh = ph * scale
                val ox = cellX + (cellW - dw) / 2f
                val oy = cellY + (cellH - dh) / 2f
                val form = layer.importPageAsForm(src, idx)
                cs.saveGraphicsState()
                val tx = if (rot == 0) ox - box.lowerLeftX * scale else ox
                val ty = if (rot == 0) oy - box.lowerLeftY * scale else oy
                cs.transform(Matrix(scale, 0f, 0f, scale, tx, ty))
                cs.drawForm(form)
                cs.restoreGraphicsState()
                if (border) {
                    cs.setStrokingColor(0.6f, 0.6f, 0.6f)
                    cs.setLineWidth(0.5f)
                    cs.addRect(ox, oy, dw, dh)
                    cs.stroke()
                }
            }
        }
    }
}
