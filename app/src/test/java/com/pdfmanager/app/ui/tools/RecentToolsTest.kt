package com.pdfmanager.app.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentToolsTest {
    @Test fun newest_tool_goes_first() {
        assertEquals(listOf("split", "merge"), RecentTools.pushed(listOf("merge"), "split"))
    }

    @Test fun reopening_a_tool_moves_it_to_the_front_without_duplicates() {
        assertEquals(listOf("merge", "split", "lock"), RecentTools.pushed(listOf("split", "merge", "lock"), "merge"))
    }

    @Test fun keeps_at_most_four() {
        val out = RecentTools.pushed(listOf("a", "b", "c", "d"), "e")
        assertEquals(listOf("e", "a", "b", "c"), out)
        assertEquals(RecentTools.MAX, out.size)
    }

    @Test fun unknown_ids_are_dropped_when_resolved() {
        val tools = RecentTools.resolve(listOf("merge", "gone", "split"))
        assertEquals(listOf("merge", "split"), tools.map { it.id })
        assertTrue(RecentTools.resolve(emptyList()).isEmpty())
    }
}
