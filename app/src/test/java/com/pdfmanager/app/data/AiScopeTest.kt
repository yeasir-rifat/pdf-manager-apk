package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiScopeTest {

    private fun range(text: String, total: Int = 40) = (AiScope.range(text, total) as AiScope.Parsed.Ok).scope

    private fun invalid(text: String, total: Int = 40) = (AiScope.range(text, total) as AiScope.Parsed.Invalid).message

    // ---- scope to page list

    @Test fun pageGivesThatOnePage() {
        assertEquals(listOf(12), AiScope.Page(12).pageNumbers(40))
    }

    @Test fun wholeGivesEveryPage() {
        assertEquals(listOf(1, 2, 3), AiScope.Whole.pageNumbers(3))
    }

    @Test fun rangeGivesItsPagesInOrder() {
        assertEquals(listOf(3, 4, 5, 6, 7, 10), AiScope.range("3-7, 10", 40).let { (it as AiScope.Parsed.Ok).scope.pageNumbers(40) })
    }

    @Test fun validityPerScope() {
        assertTrue(AiScope.Page(1).isValidFor(1))
        assertTrue(AiScope.Page(40).isValidFor(40))
        assertFalse(AiScope.Page(0).isValidFor(40))
        assertFalse(AiScope.Page(41).isValidFor(40))
        assertTrue(AiScope.Whole.isValidFor(1))
        assertFalse(AiScope.Whole.isValidFor(0))
        assertTrue(AiScope.Range(listOf(2, 5)).isValidFor(5))
        assertFalse(AiScope.Range(listOf(2, 6)).isValidFor(5))
        assertFalse(AiScope.Range(emptyList()).isValidFor(5))
    }

    // ---- range parsing

    @Test fun rangeIsOneBasedSortedAndMerged() {
        assertEquals(listOf(1, 2, 3, 4, 5), range("5, 1-3, 2-4").pages)
    }

    @Test fun rangeAcceptsBanglaDigits() {
        assertEquals(listOf(3, 4, 5), range("\u09E9-\u09EB").pages)
    }

    @Test fun blankRangeIsNotAllPages() {
        assertEquals(AiScope.BAD_INPUT, invalid(""))
        assertEquals(AiScope.BAD_INPUT, invalid("   "))
    }

    @Test fun rangeErrorsUseTheParserMessages() {
        assertEquals("Pages must be between 1 and 40", invalid("0"))
        assertEquals("Pages must be between 1 and 40", invalid("41"))
        assertEquals("\"8-3\" goes backwards", invalid("8-3"))
        assertEquals("Remove the extra comma", invalid("1,,2"))
        assertEquals("Use numbers like 1-3, 5, 8-10", invalid("a-b"))
        assertEquals("This PDF has no pages", invalid("1", total = 0))
    }

    // ---- attach again decision table

    @Test fun firstQuestionAttaches() {
        assertTrue(AiScope.needsAttach(null, AiScope.Page(12)))
        assertTrue(AiScope.needsAttach(null, AiScope.Whole))
    }

    @Test fun samePageDoesNotAttachAgain() {
        assertFalse(AiScope.needsAttach(AiScope.Page(12), AiScope.Page(12)))
    }

    @Test fun nextPageAttachesAgain() {
        assertTrue(AiScope.needsAttach(AiScope.Page(12), AiScope.Page(13)))
    }

    @Test fun pageThenWholeAttachesAgain() {
        assertTrue(AiScope.needsAttach(AiScope.Page(12), AiScope.Whole))
    }

    @Test fun wholeThenPageAttachesAgain() {
        assertTrue(AiScope.needsAttach(AiScope.Whole, AiScope.Page(12)))
    }

    @Test fun wholeThenWholeDoesNotAttachAgain() {
        assertFalse(AiScope.needsAttach(AiScope.Whole, AiScope.Whole))
    }

    @Test fun sameRangeStaysAndAnotherRangeAttaches() {
        assertFalse(AiScope.needsAttach(range("3-7"), range("3-7")))
        assertTrue(AiScope.needsAttach(range("3-7"), range("3-8")))
        assertTrue(AiScope.needsAttach(range("3"), AiScope.Page(3)))
    }

    // ---- context lines

    @Test fun contextLineForPage() {
        assertEquals(
            "The attached PDF is page 12 of a 40 page document. Answer only about this page unless asked otherwise.",
            AiScope.contextLine(AiScope.Page(12), 40, 12),
        )
    }

    @Test fun contextLineForWholeUsesTheReaderPage() {
        assertEquals("The reader is currently on page 12 of 40.", AiScope.contextLine(AiScope.Whole, 40, 12))
        assertNull(AiScope.contextLine(AiScope.Whole, 40, null))
        assertNull(AiScope.contextLine(AiScope.Whole, 40, 41))
    }

    @Test fun contextLineForRange() {
        assertEquals("The attached PDF holds pages 3 to 7 of a 40 page document.", AiScope.contextLine(range("3-7"), 40, 1))
        assertEquals("The attached PDF holds pages 3 to 7, 10 of a 40 page document.", AiScope.contextLine(range("3-7, 10"), 40, 1))
        assertEquals("The attached PDF holds page 5 of a 40 page document.", AiScope.contextLine(range("5"), 40, 1))
        assertEquals("The attached PDF holds pages 5, 6 of a 40 page document.", AiScope.contextLine(range("5-6"), 40, 1))
    }

    @Test fun contextLineIsPlainAsciiWithoutEmDash() {
        val lines = listOf(
            AiScope.contextLine(AiScope.Page(12), 40, 12), AiScope.contextLine(AiScope.Whole, 40, 12),
            AiScope.contextLine(range("3-7, 10"), 40, 1), AiScope.PAGE_REF_RULE,
        )
        for (l in lines) assertFalse(l!!.contains('\u2014'))
    }

    // ---- page numbers mapped back

    @Test fun pageCopyHasOnePageAndItMapsToTheStepperPage() {
        assertEquals(12, AiScope.Page(12).originalPage(1))
        assertNull(AiScope.Page(12).originalPage(2))
        assertNull(AiScope.Page(12).originalPage(0))
    }

    @Test fun wholeCopyKeepsItsNumbers() {
        assertEquals(7, AiScope.Whole.originalPage(7))
        assertNull(AiScope.Whole.originalPage(0))
    }

    @Test fun rangeCopyMapsBackToTheOriginalPages() {
        val r = range("3-7, 10")
        assertEquals(3, r.originalPage(1))
        assertEquals(7, r.originalPage(5))
        assertEquals(10, r.originalPage(6))
        assertNull(r.originalPage(7))
        assertNull(r.originalPage(0))
    }

    @Test fun answerRefsAreRewrittenForRange() {
        val out = AiScope.remapPageRefs("See [p. 1] and [p. 6].", range("3-7, 10"))
        assertEquals("See [p. 3] and [p. 10].", out)
    }

    @Test fun answerRefsAreRewrittenForPage() {
        assertEquals("It is defined here [p. 12].", AiScope.remapPageRefs("It is defined here [p. 1].", AiScope.Page(12)))
    }

    @Test fun answerRefsThatDoNotExistAreRemoved() {
        assertEquals("Only this.", AiScope.remapPageRefs("Only this [p. 9].", AiScope.Page(12)))
        assertEquals("A and B.", AiScope.remapPageRefs("A [p. 8] and B [p. 0].", range("3-7")))
    }

    @Test fun wholeAnswersKeepTheirRefsAndBanglaDigitsBecomeAscii() {
        assertEquals("See [p. 12].", AiScope.remapPageRefs("See [p. 12].", AiScope.Whole))
        assertEquals("See [p. 12].", AiScope.remapPageRefs("See [p. \u09E7\u09E8].", AiScope.Whole))
        assertEquals("No refs here", AiScope.remapPageRefs("No refs here", AiScope.Page(3)))
    }

    @Test fun otherBracketsAreLeftAlone() {
        assertEquals("a [1] b [page 2]", AiScope.remapPageRefs("a [1] b [page 2]", AiScope.Page(3)))
    }
}
