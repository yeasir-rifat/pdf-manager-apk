package com.pdfmanager.app.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogTest {
    /** Every route MainActivity registers for a tool (kept in sync by hand; see TEST-CHECKLIST). */
    private val registeredToolRoutes = setOf(
        "merge", "img2pdf", "pdf2img", "pdf2text", "extractimg", "text2pdf", "croprotate", "lock", "scan",
        "compress", "nup", "booklet", "split", "extract", "reorder", "blank", "watermark", "pagenum",
        "sign", "notes", "resize", "poster", "metadata", "repair"
    )

    @Test fun has_all_24_tools_exactly_once() {
        assertEquals(24, ToolCatalog.all.size)
        assertEquals(24, ToolCatalog.all.map { it.id }.toSet().size)
        assertEquals(24, ToolCatalog.all.map { it.route }.toSet().size)
    }

    @Test fun every_route_is_a_registered_destination() {
        assertEquals(registeredToolRoutes, ToolCatalog.all.map { it.route }.toSet())
    }

    @Test fun every_category_has_tools_and_order_follows_categories() {
        ToolCatalog.grouped.forEach { (_, tools) -> assertTrue(tools.isNotEmpty()) }
        assertEquals(ToolCatalog.all, ToolCatalog.grouped.flatMap { it.second })
        val ordinals = ToolCatalog.all.map { it.category.ordinal }
        assertEquals(ordinals.sorted(), ordinals)
    }

    @Test fun grouping_keeps_order_and_drops_empty_categories() {
        val picked = ToolCatalog.all.filter { it.id == "lock" || it.id == "merge" }
        val groups = ToolCatalog.groupByCategory(picked)
        assertEquals(listOf(ToolCategory.ORGANIZE, ToolCategory.PROTECT), groups.map { it.first })
        assertEquals(24, ToolCatalog.grouped.sumOf { it.second.size })
    }

    @Test fun search_matches_name_and_category_words() {
        val terms: (ToolDef) -> List<String> = { listOf(it.id, it.category.name) }
        assertEquals(ToolCatalog.all, ToolCatalog.search("  ", terms))
        assertEquals(listOf("merge"), ToolCatalog.search("MERGE", terms).map { it.id })
        assertTrue(ToolCatalog.search("edit", terms).all { it.category == ToolCategory.EDIT })
        assertTrue(ToolCatalog.search("nothing-like-this", terms).isEmpty())
    }

    @Test fun search_rules() {
        assertTrue(ToolSearch.matches("page num", listOf("Page numbers", "Edit PDF")))
        assertTrue(ToolSearch.matches("rotate", listOf("Crop & Rotate")))
        assertTrue(ToolSearch.matches("পৃষ্ঠা", listOf("পৃষ্ঠা নম্বর", "Page numbers")))
        assertFalse(ToolSearch.matches("merge split", listOf("Merge PDF")))
        assertTrue(ToolSearch.matches("", listOf("anything")))
    }
}
