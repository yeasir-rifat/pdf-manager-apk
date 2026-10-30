package com.pdfmanager.app.printing

/**
 * Works out which pages of the original PDF the Android print system asked for.
 * Kept separate from the print adapter so it can be tested without a device.
 */
object PageMapping {

    /**
     * [ranges] are the page ranges the print system wants (0-based, counted in the document
     * that was offered to it). [chosen] are the original pages (0-based, sorted) that were
     * offered when the user typed a page range, or null when the whole document was offered.
     * Returns one flag per original page: true if that page must be written.
     */
    fun wanted(ranges: List<IntRange>, pageCount: Int, chosen: List<Int>?): BooleanArray {
        val presented = chosen?.size ?: pageCount
        val result = BooleanArray(pageCount)
        for (r in ranges) {
            val from = r.first.coerceAtLeast(0)
            val to = r.last.coerceAtMost(presented - 1)
            for (i in from..to) result[chosen?.get(i) ?: i] = true
        }
        return result
    }
}
