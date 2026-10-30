package com.pdfmanager.app.ui.tools

import java.util.Locale

/** Plain-Kotlin matching rules for the tool search (no Android classes, so it is unit-testable). */
object ToolSearch {
    private fun normalize(text: String): String = text.trim().lowercase(Locale.ROOT)

    /**
     * True when every word of [query] appears somewhere in [fields] (tool name, category name,
     * in the current language and in English). A blank query matches everything.
     * "page num" finds "Page numbers"; "rotate" finds "Crop & Rotate".
     */
    fun matches(query: String, fields: List<String>): Boolean {
        val words = normalize(query).split(' ', '\t', '\n').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = fields.joinToString(" ") { normalize(it) }
        return words.all { haystack.contains(it) }
    }
}
