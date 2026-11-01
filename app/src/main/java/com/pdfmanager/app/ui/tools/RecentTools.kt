package com.pdfmanager.app.ui.tools

import android.content.Context

/** The last few tools the person opened, newest first. Stored in the same "ui" preferences as the rest. */
object RecentTools {
    private const val KEY = "recentTools"
    const val MAX = 4

    /** [id] moved to the front of [current]; no duplicates, at most [MAX] ids. Pure, so it can be tested. */
    fun pushed(current: List<String>, id: String): List<String> =
        (listOf(id) + current.filter { it != id }).take(MAX)

    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun get(c: Context): List<String> =
        prefs(c).getString(KEY, "").orEmpty().split(',').filter { it.isNotBlank() }

    /** Saves and returns the new list. */
    fun push(c: Context, id: String): List<String> {
        val next = pushed(get(c), id)
        prefs(c).edit().putString(KEY, next.joinToString(",")).apply()
        return next
    }

    /** The ids as tools, in order; ids of tools that no longer exist are dropped. */
    fun resolve(ids: List<String>): List<ToolDef> {
        val byId = ToolCatalog.all.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }
}
