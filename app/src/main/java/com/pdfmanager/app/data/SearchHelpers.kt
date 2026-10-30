package com.pdfmanager.app.data

import java.text.Normalizer
import java.util.Locale

/**
 * Plain logic of the text index and the library search (step 4). It uses no Android classes,
 * so it is covered by ordinary unit tests.
 *
 * The index keeps one "folded" copy of each PDF's text: Unicode-normalized, lower-cased, invisible
 * characters (zero-width joiners, soft hyphens) removed and every run of spaces/line breaks turned
 * into one space. A search folds the typed text the same way, so both sides always agree.
 */
object SearchHelpers {

    /** Result of reading one PDF for the index. */
    const val STATUS_OK = 1
    const val STATUS_NO_TEXT = 2   // only pictures (a scan)
    const val STATUS_FAILED = 3    // locked, damaged or too big

    /** A word shorter than this is ignored by the in-PDF search (it would match almost everything). */
    const val MIN_TERM_LENGTH = 2
    const val MAX_TERMS = 5

    /** Limits that keep the index small and the reading fast. */
    const val MAX_PAGES = 400
    const val MAX_CHARS = 400_000
    const val MAX_FILE_BYTES = 80L * 1024 * 1024
    const val MAX_HITS = 500

    const val SNIPPET_BEFORE = 40
    const val SNIPPET_LENGTH = 120

    /** Tells whether a stored text still belongs to the file on the device. */
    fun stampOf(sizeBytes: Long, modifiedSec: Long): String = "$sizeBytes-$modifiedSec"

    private fun isInvisible(c: Char) =
        c == '\u00AD' || c in '\u200B'..'\u200F' || c in '\u202A'..'\u202E' || c == '\u2060' || c == '\uFEFF'

    /** See the class comment. Bangla joiners (ZWJ, ZWNJ) are removed, so "র‍্য" and "র্য" find each other. */
    fun fold(text: String): String {
        if (text.isEmpty()) return ""
        val n = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = StringBuilder(n.length)
        var pendingSpace = false
        for (c in n) {
            when {
                isInvisible(c) -> {}
                c.isWhitespace() || c.isISOControl() -> pendingSpace = out.isNotEmpty()
                else -> {
                    if (pendingSpace) { out.append(' '); pendingSpace = false }
                    out.append(c)
                }
            }
        }
        return out.toString()
    }

    /** The words a search looks for inside PDFs: folded, no repeats, short ones dropped, at most [MAX_TERMS]. */
    fun textTerms(query: String): List<String> =
        fold(query).split(' ')
            .filter { it.codePointCount(0, it.length) >= MIN_TERM_LENGTH }
            .distinct()
            .take(MAX_TERMS)

    /**
     * SQL of the in-PDF search: every term must occur somewhere in the text. Gives the file, the
     * place of the first term (1-based) and a piece of text around it. Arguments: see [hitArgs].
     */
    fun hitSql(termCount: Int): String {
        require(termCount in 1..MAX_TERMS) { "1 to $MAX_TERMS words" }
        val all = (1..termCount).joinToString(" AND ") { "instr(text, ?) > 0" }
        return "SELECT uri, instr(text, ?) AS pos, " +
            "substr(text, MAX(1, instr(text, ?) - $SNIPPET_BEFORE), $SNIPPET_LENGTH) AS raw " +
            "FROM pdf_text WHERE status = $STATUS_OK AND $all LIMIT $MAX_HITS"
    }

    fun hitArgs(terms: List<String>): Array<Any?> {
        require(terms.isNotEmpty())
        return (listOf<Any?>(terms[0], terms[0]) + terms).toTypedArray()
    }

    /**
     * Turns the piece of text the database gave ([raw], taken from place [pos] - [SNIPPET_BEFORE]) into a
     * tidy line: cut at word edges, with "…" where the text goes on.
     */
    fun snippet(raw: String, pos: Int): String {
        var s = raw
        val cutFront = pos - SNIPPET_BEFORE > 1
        val cutBack = raw.length >= SNIPPET_LENGTH
        if (cutFront) {
            val sp = s.indexOf(' ')
            if (sp in 0..12) s = s.substring(sp + 1)
        }
        if (cutBack) {
            val sp = s.lastIndexOf(' ')
            if (sp >= 0 && s.length - sp <= 13) s = s.substring(0, sp)
        }
        return (if (cutFront) "…" else "") + s.trim() + (if (cutBack) "…" else "")
    }

    /** Where the [terms] occur in [text] (both folded): sorted, not overlapping, touching ranges joined. */
    fun highlightRanges(text: String, terms: List<String>): List<IntRange> {
        val found = ArrayList<IntRange>()
        for (t in terms) {
            if (t.isEmpty()) continue
            var i = text.indexOf(t)
            while (i >= 0) {
                found += i until i + t.length
                i = text.indexOf(t, i + t.length)
            }
        }
        found.sortBy { it.first }
        val out = ArrayList<IntRange>()
        for (r in found) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                if (r.last > last.last) out[out.lastIndex] = last.first..r.last
            } else {
                out += r
            }
        }
        return out
    }
}
