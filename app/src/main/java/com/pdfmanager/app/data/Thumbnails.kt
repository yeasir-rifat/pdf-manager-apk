package com.pdfmanager.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Small first-page previews for the library and tool screens.
 *
 * - Memory cache (size-limited) so scrolling back is instant.
 * - Disk cache (JPEG) so the next app start is instant too.
 * - At most 3 renders at once, so a long list never freezes the UI or runs out of memory.
 */
object Thumbnails {
    private const val DISK_LIMIT_BYTES = 60L * 1024 * 1024

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 10L).toInt().coerceAtLeast(8 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val gate = Semaphore(3)
    private val saves = java.util.concurrent.atomic.AtomicInteger(0)

    private fun hash(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun diskFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        return File(dir, hash(key) + ".jpg")
    }

    fun key(uri: Any, stamp: String, widthPx: Int, heightPx: Int, page: Int = 0) = "$uri|$stamp|$widthPx|$heightPx|$page"

    /** Returns a cached thumbnail without doing any work, or null. */
    fun peek(key: String): Bitmap? = memory.get(key)

    /**
     * Loads a thumbnail for a PDF's first page (or any page when [page] is set) or an image file.
     * [stamp] should change when the file changes (modified time + size) so old previews are not reused.
     */
    suspend fun load(
        context: Context,
        uri: Uri,
        isPdf: Boolean,
        widthPx: Int,
        heightPx: Int,
        stamp: String = "",
        page: Int = 0
    ): Bitmap? {
        val key = key(uri, stamp, widthPx, heightPx, page)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                memory.get(key)?.let { return@withPermit it }
                val file = diskFile(context, key)
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.path)?.let {
                        memory.put(key, it)
                        return@withPermit it
                    }
                }
                val bmp = runCatching {
                    if (isPdf) renderPdf(context, uri, page, widthPx, heightPx) else decodeImage(context, uri, widthPx)
                }.getOrNull() ?: return@withPermit null
                memory.put(key, bmp)
                runCatching {
                    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    // Listing the whole folder on every save is slow; do it once in a while
                    if (saves.incrementAndGet() % 25 == 0) trimDisk(file.parentFile)
                }
                bmp
            }
        }
    }

    private fun renderPdf(context: Context, uri: Uri, pageIndex: Int, widthPx: Int, heightPx: Int): Bitmap? {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return fd.use {
            PdfRenderer(it).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(pageIndex.coerceIn(0, renderer.pageCount - 1)).use { page ->
                    // Cover the box exactly (the screen draws it 1:1, which is what keeps text sharp)
                    var scale = widthPx.toFloat() / page.width
                    if (heightPx > 0) scale = maxOf(scale, heightPx.toFloat() / page.height)
                    var w = Math.round(page.width * scale).coerceIn(1, 3000)
                    var h = Math.round(page.height * scale).coerceIn(1, 3000)
                    if (w < widthPx) w = widthPx.coerceAtMost(3000)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }

    private fun decodeImage(context: Context, uri: Uri, widthPx: Int): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun trimDisk(dir: File?) {
        val files = dir?.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_LIMIT_BYTES) return
        files.sortedBy { it.lastModified() }.forEach {
            if (total <= DISK_LIMIT_BYTES * 3 / 4) return
            total -= it.length()
            it.delete()
        }
    }
}
