package com.pdfmanager.app.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangeParserTest {
    private fun pages(text: String, total: Int) = (PageRangeParser.parse(text, total) as PageRangeResult.Valid).pages
    private fun error(text: String, total: Int) = (PageRangeParser.parse(text, total) as PageRangeResult.Invalid).message

    @Test fun blankMeansAllPages() {
        val r = PageRangeParser.parse("  ", 3) as PageRangeResult.Valid
        assertTrue(r.isAll); assertEquals(listOf(0, 1, 2), r.pages)
    }

    @Test fun readsRangesAndSinglePages() = assertEquals(listOf(0, 1, 2, 3, 6, 8, 9, 10), pages("1-4,7,9-11", 12))
    @Test fun ignoresSpaces() = assertEquals(listOf(0, 1, 2, 4, 7, 8, 9), pages(" 1-3 , 5 ,8-10", 10))
    @Test fun mergesOverlapsAndSorts() = assertEquals(listOf(0, 1, 2, 3, 4), pages("5, 1-3, 2-4", 10))
    @Test fun acceptsBanglaDigits() = assertEquals(listOf(0, 1, 2, 4), pages("\u09E7-\u09E9, \u09EB", 10))

    @Test fun rejectsBackwardsRange() = assertEquals("\"8-3\" goes backwards", error("8-3", 10))
    @Test fun rejectsPageZeroAndTooBig() {
        assertEquals("Pages must be between 1 and 10", error("0", 10))
        assertEquals("Pages must be between 1 and 10", error("1-11", 10))
    }
    @Test fun rejectsBadShapes() {
        for (t in listOf("a", "3-", "-3", "1-2-3", "9999999")) assertEquals("Use numbers like 1-3, 5, 8-10", error(t, 10))
        assertEquals("Remove the extra comma", error("1,", 10))
        assertEquals("Remove the extra comma", error("1,,2", 10))
    }

    // Step 12 acceptance cases
    @Test fun readmeExampleOnLargeDocument() {
        val r = PageRangeParser.parse("1-3, 5, 8-10", 500) as PageRangeResult.Valid
        assertEquals(listOf(0, 1, 2, 4, 7, 8, 9), r.pages)
        assertEquals(false, r.isAll)
        assertEquals(7, r.count)
    }
    @Test fun listingEveryPageCountsAsAll() {
        val r = PageRangeParser.parse("1-10", 10) as PageRangeResult.Valid
        assertTrue(r.isAll)
    }
    @Test fun wholeFiveHundredPagesIsFast() {
        val r = PageRangeParser.parse("1-500", 500) as PageRangeResult.Valid
        assertEquals(500, r.count)
    }
    @Test fun spacesAroundDashAreFine() = assertEquals(listOf(0, 1, 2), pages("1 - 3", 10))
    @Test fun singlePageDocument() {
        assertEquals(listOf(0), pages("1", 1))
        assertEquals("Pages must be between 1 and 1", error("2", 1))
    }
    @Test fun documentWithNoPagesIsInvalid() {
        assertEquals("This PDF has no pages", error("", 0))
        assertEquals("This PDF has no pages", error("1", 0))
    }
    @Test fun otherSeparatorsAreRejected() {
        for (t in listOf("1;2", "1 2", "1.5")) assertEquals("Use numbers like 1-3, 5, 8-10", error(t, 10))
    }
}
