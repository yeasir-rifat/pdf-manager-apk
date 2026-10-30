package com.pdfmanager.app.printing

/** Result of reading the "Pages" field. */
sealed interface PageRangeResult {
    /**
     * Valid input. [pages] are the chosen pages, 0-based, sorted, without duplicates.
     * [isAll] is true when the whole document is chosen (blank field, or every page listed).
     */
    data class Valid(val pages: List<Int>, val isAll: Boolean) : PageRangeResult {
        val count: Int get() = pages.size
    }

    /** Invalid input. [message] is shown under the field; nothing must be printed. */
    data class Invalid(val message: String) : PageRangeResult
}

/**
 * Reads text like "1-4,7,9-11" (1-based page numbers, as the user sees them).
 *
 * Rules:
 * - blank means all pages; Bangla digits (\u09E7-\u09EF) are accepted like 1-9;
 * - every page must be between 1 and the page count;
 * - a range must not go backwards ("8-3" is invalid);
 * - overlaps and repeats are merged, and pages are always printed in document order,
 *   so "5, 1-3, 2-4" means pages 1, 2, 3, 4, 5.
 */
object PageRangeParser {

    fun parse(text: String, totalPages: Int): PageRangeResult {
        if (totalPages <= 0) return PageRangeResult.Invalid("This PDF has no pages")
        if (text.isBlank()) return PageRangeResult.Valid((0 until totalPages).toList(), isAll = true)

        val picked = BooleanArray(totalPages)
        for (raw in text.split(',')) {
            val part = raw.trim()
            if (part.isEmpty()) return PageRangeResult.Invalid("Remove the extra comma")
            val bits = part.split('-').map { it.trim() }
            if (bits.size > 2 || bits.any { it.isEmpty() || !it.all { c -> Character.isDigit(c) } || it.length > 6 }) {
                return PageRangeResult.Invalid("Use numbers like 1-3, 5, 8-10")
            }
            val from = number(bits[0])
            val to = if (bits.size == 2) number(bits[1]) else from
            if (from < 1 || to < 1 || from > totalPages || to > totalPages) {
                return PageRangeResult.Invalid("Pages must be between 1 and $totalPages")
            }
            if (from > to) return PageRangeResult.Invalid("\"$part\" goes backwards")
            for (p in from..to) picked[p - 1] = true
        }
        val pages = picked.indices.filter { picked[it] }
        return PageRangeResult.Valid(pages, isAll = pages.size == totalPages)
    }

    /** Also understands Bengali digits (\u09E7 = 1), so typing with a Bangla keyboard works. */
    private fun number(digits: String): Int = digits.fold(0) { acc, c -> acc * 10 + Character.digit(c, 10) }
}
