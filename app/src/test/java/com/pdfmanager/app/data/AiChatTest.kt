package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChatTest {

    @Test fun chipsForAPageAreExplainSummarizeDetailPoints() {
        assertEquals(
            listOf(AiChip.Explain, AiChip.Summarize, AiChip.Detail, AiChip.Points),
            AiChip.forScope(AiScope.Page(12)),
        )
    }

    @Test fun chipsForWholeAndRangeAreSummarizePointsOutlineQuiz() {
        val many = listOf(AiChip.Summarize, AiChip.Points, AiChip.Outline, AiChip.Quiz)
        assertEquals(many, AiChip.forScope(AiScope.Whole))
        assertEquals(many, AiChip.forScope(AiScope.Range(listOf(3, 4))))
    }

    @Test fun everyChipHasALongerPromptWithoutEmDash() {
        for (c in AiChip.values()) {
            assertTrue(c.name, c.prompt.length > 20)
            assertFalse(c.name, c.prompt.contains('\u2014'))
        }
    }

    @Test fun historyIsUserThenModelInOrder() {
        val h = AiChat.history(listOf(AiChat.Pair("q1", "a1"), AiChat.Pair("q2", "a2")))
        assertEquals(listOf("user", "model", "user", "model"), h.map { it.role })
        assertEquals(listOf("q1", "a1", "q2", "a2"), h.map { it.text })
        assertTrue(h.all { it.pdf == null })
    }

    @Test fun emptyHistoryIsEmpty() {
        assertTrue(AiChat.history(emptyList()).isEmpty())
    }

    @Test fun everyQuestionCarriesItsPdfByDefault() {
        assertTrue(AiChat.ALWAYS_ATTACH)
        assertNull(AiChat.attachedFor(AiScope.Page(3)))
        assertTrue(AiScope.needsAttach(AiChat.attachedFor(AiScope.Page(3)), AiScope.Page(3)))
    }

    @Test fun rangeLabelIsTheShortForm() {
        assertEquals("3-7, 10", AiScope.rangeLabel(listOf(3, 4, 5, 6, 7, 10)))
        assertEquals("5", AiScope.rangeLabel(listOf(5)))
        assertEquals("5-6", AiScope.rangeLabel(listOf(5, 6)))
        assertEquals("1, 3, 5-6", AiScope.rangeLabel(listOf(1, 3, 5, 6)))
        assertEquals("", AiScope.rangeLabel(emptyList()))
    }
}
