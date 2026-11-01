package com.pdfmanager.app.data

/** The four text fields of a PDF's information dictionary that the Metadata tool edits. */
data class MetaFields(val title: String, val author: String, val subject: String, val keywords: String)

/**
 * Plain logic of Edit metadata and Repair PDF (step 4). No Android classes: covered by ordinary unit tests.
 */
object MetadataHelpers {

    const val MAX_FIELD = 1000

    /** One line, no control characters, single spaces, at most [MAX_FIELD] characters. Bangla joiners stay. */
    fun clean(s: String): String {
        val out = StringBuilder(s.length)
        var lastSpace = true
        for (c in s) {
            if (c.isWhitespace() || c.isISOControl()) {
                if (!lastSpace) { out.append(' '); lastSpace = true }
            } else {
                out.append(c); lastSpace = false
            }
        }
        var text = out.toString().trimEnd()
        if (text.length > MAX_FIELD) {
            var end = MAX_FIELD
            if (Character.isHighSurrogate(text[end - 1])) end--
            text = text.substring(0, end).trimEnd()
        }
        return text
    }

    fun cleaned(f: MetaFields) = MetaFields(clean(f.title), clean(f.author), clean(f.subject), clean(f.keywords))

    fun same(a: MetaFields, b: MetaFields): Boolean = cleaned(a) == cleaned(b)

    /** Names of the fields whose value differs after cleaning, in screen order. */
    fun changedNames(old: MetaFields, new: MetaFields): List<String> {
        val a = cleaned(old)
        val b = cleaned(new)
        return listOfNotNull(
            "Title".takeIf { a.title != b.title },
            "Author".takeIf { a.author != b.author },
            "Subject".takeIf { a.subject != b.subject },
            "Keywords".takeIf { a.keywords != b.keywords }
        )
    }

    /** "Title · Author · XMP" for the "Found" line; empty list gives "Nothing stored". */
    fun foundLine(found: List<String>): String = if (found.isEmpty()) "Nothing stored" else found.joinToString(" · ")
}

object RepairHelpers {

    /** [3, 7, 8, 9] -> "3, 7-9" (page numbers as given, 1-based). */
    fun pageList(pages: List<Int>): String {
        val sorted = pages.distinct().sorted()
        val parts = ArrayList<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            parts += if (j == i) "${sorted[i]}" else "${sorted[i]}-${sorted[j]}"
            i = j + 1
        }
        return parts.joinToString(", ")
    }

    /** The message under "Done". [skipped] are 1-based page numbers that could not be read. */
    fun summary(kept: Int, skipped: List<Int>, rebuilt: Boolean, opens: Boolean): String {
        val pages = ConvertHelpers.plural(kept, "page")
        val head = if (rebuilt) "Rebuilt $pages" else "Repaired ($pages)"
        val left = if (skipped.isEmpty()) "" else
            ". Skipped ${ConvertHelpers.plural(skipped.size, "page")} (${RepairHelpers.pageList(skipped)})"
        val tail = when {
            opens -> ". Opens fine"
            rebuilt -> ". Still won't open in the viewer"
            else -> ". Still won't open. Try Rebuild pages"
        }
        return "$head$left$tail"
    }
}
