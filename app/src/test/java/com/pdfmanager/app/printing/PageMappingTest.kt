package com.pdfmanager.app.printing

import org.junit.Assert.assertEquals
import org.junit.Test

class PageMappingTest {
    private fun on(flags: BooleanArray) = flags.indices.filter { flags[it] }

    @Test fun allPagesRequestedWithoutPageRange() =
        assertEquals((0 until 5).toList(), on(PageMapping.wanted(listOf(0..Int.MAX_VALUE), 5, null)))

    @Test fun someRangesWithoutPageRange() =
        assertEquals(listOf(0, 1, 3), on(PageMapping.wanted(listOf(0..1, 3..3), 6, null)))

    @Test fun printSystemRangesAreMappedBackToOriginalPages() {
        // The user typed "1-3, 5, 8-10" for a 500 page PDF: only those 7 pages were offered.
        val chosen = listOf(0, 1, 2, 4, 7, 8, 9)
        // The print system asks for its pages 1-3 and 5 (0-based 0..2 and 4..4): original pages 1, 2, 3 and 8 (0-based 0, 1, 2, 7).
        assertEquals(listOf(0, 1, 2, 7), on(PageMapping.wanted(listOf(0..2, 4..4), 500, chosen)))
    }

    @Test fun allPagesOfAPageRangeGiveExactlyTheChosenPages() {
        val chosen = listOf(0, 1, 2, 4, 7, 8, 9)
        assertEquals(chosen, on(PageMapping.wanted(listOf(0..Int.MAX_VALUE), 500, chosen)))
    }

    @Test fun outOfBoundsRangesAreClamped() {
        assertEquals(listOf(0, 1, 2), on(PageMapping.wanted(listOf(-5..2), 10, null)))
        assertEquals(listOf(8, 9), on(PageMapping.wanted(listOf(8..99), 10, null)))
    }

    @Test fun backwardsOrEmptyRangesSelectNothing() {
        assertEquals(emptyList<Int>(), on(PageMapping.wanted(listOf(5..2), 10, null)))
        assertEquals(emptyList<Int>(), on(PageMapping.wanted(emptyList(), 10, null)))
    }

    @Test fun overlappingRangesDoNotRepeatPages() =
        assertEquals(listOf(1, 2, 3, 4), on(PageMapping.wanted(listOf(1..3, 2..4), 10, null)))
}
