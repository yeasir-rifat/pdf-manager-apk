package com.pdfmanager.app.ui

import com.pdfmanager.app.ui.tools.ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationTest {
    @Test fun five_tabs_in_the_required_order() {
        assertEquals(
            listOf("home", "files", "tools", "scan", "settings"),
            MainTab.entries.map { it.route }
        )
        assertEquals(
            listOf(MainTab.Home, MainTab.Files, MainTab.Tools, MainTab.Scanner, MainTab.Settings),
            MainTab.entries
        )
    }

    @Test fun fromRoute_finds_tabs_and_ignores_secondary_screens() {
        MainTab.entries.forEach { assertEquals(it, MainTab.fromRoute(it.route)) }
        // The bar must not show on these
        listOf("merge", "viewer/{uri}", "print-properties/{uri}?name={name}", null, "").forEach {
            assertNull(MainTab.fromRoute(it))
        }
    }

    @Test fun quick_actions_are_real_catalog_tools() {
        assertEquals(4, quickActionIds.size)
        quickActionIds.forEach { id -> assertTrue(id, ToolCatalog.all.any { it.id == id }) }
    }
}
