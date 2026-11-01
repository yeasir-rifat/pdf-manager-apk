package com.pdfmanager.app.data

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

object PdfOps {

    /** Keeps up to ~48 MB of a document in memory and moves the rest to temporary files. */
    fun memory(): MemoryUsageSetting = MemoryUsageSetting.setupMixed(48L * 1024 * 1024)

    fun load(input: java.io.InputStream): PDDocument = PDDocument.load(input, memory())
    fun load(input: java.io.InputStream, password: String): PDDocument = PDDocument.load(input, password, memory())

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun open(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: error("Cannot open ${displayName(context, uri)}")

    /** [onProgress] gets 0f..1f after each source file is added. */
    fun merge(context: Context, uris: List<Uri>, out: OutputStream, onProgress: (Float) -> Unit = {}) {
        val merger = PDFMergerUtility()
        val sources = mutableListOf<PDDocument>()
        val dest = PDDocument(memory())
        try {
            uris.forEachIndexed { i, u ->
                val doc = open(context, u).use { load(it) }
                sources += doc
                merger.appendDocument(dest, doc)
                onProgress((i + 1f) / uris.size)
            }
            dest.save(out)
        } finally {
            sources.forEach { runCatching { it.close() } }
            runCatching { dest.close() }
        }
    }

    fun imagesToPdf(context: Context, uris: List<Uri>, out: OutputStream, onProgress: (Float) -> Unit = {}): Int {
        val doc = PdfDocument()
        var pages = 0
        try {
            uris.forEachIndexed { i, u ->
                onProgress(i.toFloat() / uris.size)
                val bmp = decodeImage(context, u) ?: return@forEachIndexed
                // A4 in points; landscape pages for wide images
                val landscape = bmp.width > bmp.height
                val pw = if (landscape) 842 else 595
                val ph = if (landscape) 595 else 842
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, ++pages).create())
                val scale = min(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                val left = (pw - w) / 2f
                val top = (ph - h) / 2f
                page.canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
                bmp.recycle()
            }
            check(pages > 0) { "No readable images selected" }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
        return pages
    }

    private fun decodeImage(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = runCatching {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    /** Renders every page to a JPEG in Pictures/PDF Manager. Returns the page count. */
    fun pdfToImages(context: Context, uri: Uri, baseName: String, onProgress: (Float) -> Unit = {}): Int {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open the PDF")
        var count = 0
        pfd.use {
            PdfRenderer(it).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val scale = min(3f, 2400f / page.width)
                        val bmp = Bitmap.createBitmap(
                            (page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888
                        )
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val name = String.format(java.util.Locale.US, "%s_page_%03d.jpg", baseName, i + 1)
                        OutputStore.saveImage(context, name, "image/jpeg") { os ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)
                        }
                        bmp.recycle()
                        count++
                    }
                    onProgress((i + 1f) / renderer.pageCount)
                }
            }
        }
        return count
    }

    /**
     * Rotates every page by [rotateDeg] and crops the given fractions (0..0.4) off each visual side.
     * Margins are given in the page's current visual orientation and mapped to the unrotated crop box.
     */
    fun cropRotate(
        context: Context, uri: Uri, out: OutputStream, rotateDeg: Int,
        top: Float, bottom: Float, left: Float, right: Float
    ) {
        open(context, uri).use { input ->
            load(input).use { doc ->
                for (page in doc.pages) {
                    if (top + bottom + left + right > 0f) {
                        val rot = ((page.rotation % 360) + 360) % 360
                        val (l, r, t, b) = when (rot) {
                            90 -> listOf(top, bottom, right, left)
                            180 -> listOf(right, left, bottom, top)
                            270 -> listOf(bottom, top, left, right)
                            else -> listOf(left, right, top, bottom)
                        }
                        val box = page.cropBox
                        val w = box.width
                        val h = box.height
                        page.cropBox = PDRectangle(
                            box.lowerLeftX + w * l, box.lowerLeftY + h * b,
                            w * (1f - l - r), h * (1f - t - b)
                        )
                    }
                    if (rotateDeg != 0) page.rotation = (page.rotation + rotateDeg) % 360
                }
                doc.save(out)
            }
        }
    }

    /** Short "12 pages · 2.1 MB" text shown under a picked PDF. */
    fun describe(context: Context, uri: Uri): String {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        val pages = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd -> PdfRenderer(fd).use { it.pageCount } }
        }.getOrNull()
        return listOfNotNull(
            pages?.let { if (it == 1) "1 page" else "$it pages" },
            size?.let { Formatter.formatShortFileSize(context, it) }
        ).joinToString(" · ")
    }

    /**
     * Renders one page for the crop preview.
     * Returns the bitmap and the document's page count, or null if the file cannot be rendered.
     */
    fun renderPreview(context: Context, uri: Uri, pageIndex: Int, widthPx: Int): Pair<Bitmap, Int>? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use { renderer ->
                val index = pageIndex.coerceIn(0, renderer.pageCount - 1)
                renderer.openPage(index).use { page ->
                    val height = (page.height * widthPx.toFloat() / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp to renderer.pageCount
                }
            }
        }
    }.getOrNull()

    fun lock(context: Context, uri: Uri, out: OutputStream, password: String) {
        open(context, uri).use { input ->
            load(input).use { doc ->
                val policy = StandardProtectionPolicy(password, password, AccessPermission())
                policy.encryptionKeyLength = 128
                doc.protect(policy)
                doc.save(out)
            }
        }
    }

    /** True when the file needs a password just to be opened (a file with only an owner password opens freely). */
    fun needsPassword(context: Context, uri: Uri): Boolean = try {
        open(context, uri).use { load(it).close() }
        false
    } catch (e: InvalidPasswordException) {
        true
    } catch (e: Exception) {
        false
    }

    private const val UNLOCKED_DIR = "view-unlocked"
    private const val UNLOCKED_MAX_AGE_MS = 2L * 60 * 60 * 1000

    /**
     * Opens a password-protected PDF with [password] and saves a copy without the password to the app's private
     * cache, so the viewer can show it. The original is never changed. Throws IllegalStateException("Wrong password").
     * Copies older than two hours are removed on the next call and when the app starts.
     */
    fun unlockForViewing(context: Context, uri: Uri, password: String): java.io.File {
        val dir = java.io.File(context.cacheDir, UNLOCKED_DIR).apply { mkdirs() }
        cleanupUnlocked(context)
        val target = java.io.File.createTempFile("view-", ".pdf", dir)
        try {
            open(context, uri).use { input ->
                load(input, password).use { doc ->
                    doc.setAllSecurityToBeRemoved(true)
                    java.io.FileOutputStream(target).use { doc.save(it) }
                }
            }
        } catch (e: InvalidPasswordException) {
            target.delete()
            throw IllegalStateException("Wrong password")
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        return target
    }

    fun cleanupUnlocked(context: Context) {
        val limit = System.currentTimeMillis() - UNLOCKED_MAX_AGE_MS
        java.io.File(context.cacheDir, UNLOCKED_DIR).listFiles()?.forEach { if (it.lastModified() < limit) runCatching { it.delete() } }
    }

    fun unlock(context: Context, uri: Uri, out: OutputStream, password: String) {
        try {
            open(context, uri).use { input ->
                load(input, password).use { doc ->
                    doc.setAllSecurityToBeRemoved(true)
                    doc.save(out)
                }
            }
        } catch (e: InvalidPasswordException) {
            throw IllegalStateException("Wrong password")
        }
    }
}
