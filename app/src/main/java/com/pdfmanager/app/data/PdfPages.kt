package com.pdfmanager.app.data

import android.content.Context
import android.net.Uri
import com.pdfmanager.app.printing.PageRangeParser
import com.pdfmanager.app.printing.PageRangeResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.OutputStream

/** Page-level operations: split, extract, delete, reorder and insert blank pages. All page numbers are 0-based. */
object PdfPages {

    private fun open(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: error("Cannot open the file")

    private fun load(context: Context, uri: Uri): PDDocument = open(context, uri).use { PdfOps.load(it) }

    /** Bookmarks point at old page numbers, so they are dropped from results that change the page list. */
    private fun dropOutline(doc: PDDocument) {
        runCatching { doc.documentCatalog.documentOutline = null }
    }

    // ------------------------------------------------------------ range parsing

    /**
     * "1-3, 5, 8-10" -> [[0,1,2], [4], [7,8,9]]: every comma part becomes its own file.
     * Returns null when the text is empty or not valid for a document of [total] pages.
     */
    fun parseRanges(text: String, total: Int): List<List<Int>>? {
        if (text.isBlank() || total <= 0) return null
        val groups = ArrayList<List<Int>>()
        for (raw in text.split(',')) {
            if (raw.isBlank()) return null
            val r = PageRangeParser.parse(raw.trim(), total)
            if (r !is PageRangeResult.Valid) return null
            groups += r.pages
        }
        return groups
    }

    /** [total] pages cut into groups of [n]: 10 pages, n = 4 -> [0..3], [4..7], [8..9]. */
    fun everyN(total: Int, n: Int): List<List<Int>> =
        if (total <= 0 || n <= 0) emptyList() else (0 until total).chunked(n)

    // ------------------------------------------------------------ split

    /**
     * Writes each group of pages as its own PDF. [openOut] gives the output stream for group number
     * [index] (0-based). The source is read once. Returns how many files were written.
     */
    fun split(
        context: Context,
        uri: Uri,
        groups: List<List<Int>>,
        openOut: (index: Int, pages: List<Int>, write: (OutputStream) -> Unit) -> Unit
    ): Int {
        val src = load(context, uri)
        try {
            val total = src.numberOfPages
            groups.forEachIndexed { i, pages ->
                require(pages.isNotEmpty() && pages.all { it in 0 until total }) { "Wrong page range" }
                openOut(i, pages) { os ->
                    val dst = PDDocument(PdfOps.memory())
                    try {
                        pages.forEach { dst.importPage(src.getPage(it)) }
                        dst.save(os)
                    } finally {
                        runCatching { dst.close() }
                    }
                }
            }
            return groups.size
        } finally {
            runCatching { src.close() }
        }
    }

    // ------------------------------------------------------------ extract / delete

    /** Keeps only the pages in [keep]; they stay in document order. Returns the new page count. */
    fun keepPages(context: Context, uri: Uri, out: OutputStream, keep: Set<Int>): Int {
        val doc = load(context, uri)
        try {
            val total = doc.numberOfPages
            val wanted = keep.filter { it in 0 until total }.toSet()
            require(wanted.isNotEmpty()) { "Pick at least one page to keep" }
            for (i in total - 1 downTo 0) if (i !in wanted) doc.removePage(i)
            dropOutline(doc)
            doc.save(out)
            return wanted.size
        } finally {
            runCatching { doc.close() }
        }
    }

    /** Removes the pages in [remove]. At least one page must stay. Returns the new page count. */
    fun deletePages(context: Context, uri: Uri, out: OutputStream, remove: Set<Int>): Int {
        val total = load(context, uri).use { it.numberOfPages }
        val keep = (0 until total).filter { it !in remove }.toSet()
        require(keep.isNotEmpty()) { "A PDF needs at least one page" }
        return keepPages(context, uri, out, keep)
    }

    // ------------------------------------------------------------ reorder

    /** Pages inherit size, rotation and resources from their parent node; copy them onto the page before it moves. */
    private fun freeze(page: PDPage) {
        page.mediaBox = page.mediaBox
        page.cropBox = page.cropBox
        page.rotation = page.rotation
        page.resources = page.resources
    }

    /** [order] lists the original page numbers in their new order; it must contain every page once. */
    fun reorder(context: Context, uri: Uri, out: OutputStream, order: List<Int>) {
        val doc = load(context, uri)
        try {
            val total = doc.numberOfPages
            require(order.size == total && order.toSet().size == total && order.all { it in 0 until total }) {
                "The new order does not match the pages"
            }
            val pages = (0 until total).map { doc.getPage(it) }
            pages.forEach { freeze(it) }
            for (i in total - 1 downTo 0) doc.removePage(i)
            order.forEach { doc.addPage(pages[it]) }
            dropOutline(doc)
            doc.save(out)
        } finally {
            runCatching { doc.close() }
        }
    }

    // ------------------------------------------------------------ blank pages

    /**
     * Adds [count] blank pages next to every page in [anchors]: after it, or before it when [after] is false.
     * A blank page has the size and rotation of the page it sits next to. Returns how many were added.
     */
    fun insertBlank(
        context: Context, uri: Uri, out: OutputStream,
        anchors: Set<Int>, after: Boolean, count: Int
    ): Int {
        val doc = load(context, uri)
        try {
            val total = doc.numberOfPages
            val list = anchors.filter { it in 0 until total }.sorted()
            require(list.isNotEmpty() && count > 0) { "Pick a page first" }
            val pages = (0 until total).map { doc.getPage(it) }
            var added = 0
            for (i in list) {
                val ref = pages[i]
                val box = ref.mediaBox
                repeat(count) {
                    val blank = PDPage(PDRectangle(box.lowerLeftX, box.lowerLeftY, box.width, box.height))
                    blank.rotation = ref.rotation
                    blank.resources = PDResources()
                    if (after) doc.pages.insertAfter(blank, ref) else doc.pages.insertBefore(blank, ref)
                    added++
                }
            }
            dropOutline(doc)
            doc.save(out)
            return added
        } finally {
            runCatching { doc.close() }
        }
    }
}
