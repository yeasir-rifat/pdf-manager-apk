package com.pdfmanager.app.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageOrderTest {

    private val B = PageOrder.BLANK
    private val rev = StackStyle.REVERSES
    private val same = StackStyle.SAME_ORDER

    @Test fun normalKeepsOrder() = assertEquals(listOf(0, 1, 2, 3), PageOrder.sequence(4, false))

    @Test fun reverseFlipsOrder() = assertEquals(listOf(3, 2, 1, 0), PageOrder.sequence(4, true))

    @Test fun noPagesGivesNothing() {
        assertEquals(emptyList<Int>(), PageOrder.sequence(0, false))
        assertEquals(emptyList<Int>(), PageOrder.duplexPass(0, DuplexPass.FIRST, rev))
    }

    // 40 pages = 20 sheets. Pass 1: backs 40, 38 ... 2. Pass 2: fronts 1, 3 ... 39 (0-based here).
    @Test fun fortyPagesReversedStack() {
        val first = PageOrder.duplexPass(40, DuplexPass.FIRST, rev)
        val second = PageOrder.duplexPass(40, DuplexPass.SECOND, rev)
        assertEquals(20, first.size)
        assertEquals(20, second.size)
        assertEquals(39, first.first())
        assertEquals(1, first.last())
        assertEquals(0, second.first())
        assertEquals(38, second.last())
        assertEquals(20, PageOrder.sheets(40))
    }

    @Test fun oddCountReversedStackStartsWithBlankBack() {
        assertEquals(listOf(B, 3, 1), PageOrder.duplexPass(5, DuplexPass.FIRST, rev))
        assertEquals(listOf(0, 2, 4), PageOrder.duplexPass(5, DuplexPass.SECOND, rev))
    }

    @Test fun sameOrderStackEndsWithBlankBack() {
        assertEquals(listOf(1, 3, B), PageOrder.duplexPass(5, DuplexPass.FIRST, same))
        assertEquals(listOf(1, 3, 5), PageOrder.duplexPass(6, DuplexPass.FIRST, same))
    }

    @Test fun singlePageHasOnlyBlankBack() {
        assertEquals(listOf(B), PageOrder.duplexPass(1, DuplexPass.FIRST, rev))
        assertEquals(listOf(0), PageOrder.duplexPass(1, DuplexPass.SECOND, rev))
        assertEquals(1, PageOrder.sheets(1))
    }

    @Test fun sheetsRoundUp() {
        assertEquals(3, PageOrder.sheets(5))
        assertEquals(3, PageOrder.sheets(6))
    }

    @Test fun bothPassesCoverEveryPageExactlyOnce() {
        for (n in 1..12) for (style in StackStyle.values()) {
            val printed = (PageOrder.duplexPass(n, DuplexPass.FIRST, style) + PageOrder.duplexPass(n, DuplexPass.SECOND, style))
                .filter { it != B }.sorted()
            assertEquals((0 until n).toList(), printed)
        }
    }

    // After pass 1 the sheets sit in the order they were printed; with a reversed stack, pass 2 feeds
    // them back newest-first, so pass 2 page k must be the front of the sheet whose back is pass 1 page (last - k).
    @Test fun frontAndBackLandOnTheSameSheetWithReversedStack() {
        for (n in 1..11) {
            val first = PageOrder.duplexPass(n, DuplexPass.FIRST, rev)
            val second = PageOrder.duplexPass(n, DuplexPass.SECOND, rev)
            val fedBack = first.reversed()
            for (i in second.indices) {
                val front = second[i]
                val back = fedBack[i]
                assertTrue(back == B || back == front + 1)
            }
        }
    }

    @Test fun frontAndBackLandOnTheSameSheetWithSameOrderStack() {
        for (n in 1..11) {
            val first = PageOrder.duplexPass(n, DuplexPass.FIRST, same)
            val second = PageOrder.duplexPass(n, DuplexPass.SECOND, same)
            for (i in second.indices) assertTrue(first[i] == B || first[i] == second[i] + 1)
        }
    }

    @Test fun resumeSkipsSheetsThatCameOut() {
        val seq = PageOrder.duplexPass(10, DuplexPass.FIRST, rev)
        assertEquals(seq.drop(3), PageOrder.resume(seq, 3))
        assertEquals(seq, PageOrder.resume(seq, 0))
    }

    @Test fun resumeRejectsImpossibleCounts() {
        val seq = PageOrder.duplexPass(10, DuplexPass.FIRST, rev)
        assertNull(PageOrder.resume(seq, -1))
        assertNull(PageOrder.resume(seq, seq.size))
    }

    @Test fun reorderNeededOnlyWhenAsked() {
        assertFalse(PageOrder.needsReorder(false, ManualDuplexChoice.OFF))
        assertTrue(PageOrder.needsReorder(true, ManualDuplexChoice.OFF))
        assertTrue(PageOrder.needsReorder(false, ManualDuplexChoice.ON))
    }

    @Test fun copiesAreCollated() {
        org.junit.Assert.assertEquals(listOf(0, 1, 2, 0, 1, 2), PageOrder.repeated(listOf(0, 1, 2), 2))
        org.junit.Assert.assertEquals(listOf(0, 1), PageOrder.repeated(listOf(0, 1), 1))
    }
}
