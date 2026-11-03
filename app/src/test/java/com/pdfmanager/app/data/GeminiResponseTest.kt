package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiResponseTest {

    private fun parse(json: String) = GeminiResponse.parse(json)!!

    @Test fun normalAnswer() {
        val a = parse(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"OK"}]},"finishReason":"STOP"}],
               "usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":3,"totalTokenCount":15}}""",
        )
        assertEquals("OK", a.text)
        assertEquals("STOP", a.finishReason)
        assertNull(a.blockReason)
        assertEquals(GeminiOutcome.Ok, a.outcome)
        assertEquals(12, a.usage!!.promptTokens)
        assertEquals(3, a.usage!!.answerTokens)
        assertEquals(15, a.usage!!.totalTokens)
    }

    @Test fun severalPartsAreJoinedInOrder() {
        val a = parse("""{"candidates":[{"content":{"parts":[{"text":"one "},{"text":"two"},{"text":" three"}]},"finishReason":"STOP"}]}""")
        assertEquals("one two three", a.text)
    }

    @Test fun thoughtPartsAreSkipped() {
        val a = parse("""{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true},{"text":"answer"}]},"finishReason":"STOP"}]}""")
        assertEquals("answer", a.text)
    }

    @Test fun banglaAndEscapesSurvive() {
        val a = parse("""{"candidates":[{"content":{"parts":[{"text":"উত্তর \"ঠিক\"\nদ্বিতীয় লাইন"}]},"finishReason":"STOP"}]}""")
        assertEquals("উত্তর \"ঠিক\"\nদ্বিতীয় লাইন", a.text)
    }

    @Test fun onlyTheFirstCandidateIsUsed() {
        val a = parse("""{"candidates":[{"content":{"parts":[{"text":"first"}]}},{"content":{"parts":[{"text":"second"}]}}]}""")
        assertEquals("first", a.text)
    }

    @Test fun promptBlockedHasNoCandidates() {
        val a = parse("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        assertEquals("", a.text)
        assertEquals("SAFETY", a.blockReason)
        assertEquals(GeminiOutcome.Blocked, a.outcome)
    }

    @Test fun answerBlockedByFinishReason() {
        for (reason in GeminiResponse.BLOCKING_FINISH) {
            val a = parse("""{"candidates":[{"finishReason":"$reason"}]}""")
            assertEquals(reason, GeminiOutcome.Blocked, a.outcome)
        }
    }

    @Test fun emptyAnswers() {
        assertEquals(GeminiOutcome.Empty, parse("{}").outcome)
        assertEquals(GeminiOutcome.Empty, parse("""{"candidates":[]}""").outcome)
        assertEquals(GeminiOutcome.Empty, parse("""{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}""").outcome)
        assertEquals(GeminiOutcome.Empty, parse("""{"candidates":[{"content":{"parts":[{"text":"  \n"}]},"finishReason":"STOP"}]}""").outcome)
        assertEquals(GeminiOutcome.Empty, parse("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""").outcome)
    }

    @Test fun partialAnswerKeepsItsText() {
        val a = parse("""{"candidates":[{"content":{"parts":[{"text":"cut off he"}]},"finishReason":"MAX_TOKENS"}]}""")
        assertEquals("cut off he", a.text)
        assertEquals(GeminiOutcome.Partial, a.outcome)
        val s = parse("""{"candidates":[{"content":{"parts":[{"text":"some"}]},"finishReason":"SAFETY"}]}""")
        assertEquals(GeminiOutcome.Partial, s.outcome)
    }

    @Test fun textWithNoFinishReasonCountsAsOk() {
        assertEquals(GeminiOutcome.Ok, parse("""{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}""").outcome)
    }

    @Test fun missingUsageIsNullAndMissingNumbersAreZero() {
        assertNull(parse("""{"candidates":[]}""").usage)
        val u = parse("""{"usageMetadata":{"totalTokenCount":9}}""").usage!!
        assertEquals(0, u.promptTokens)
        assertEquals(0, u.answerTokens)
        assertEquals(9, u.totalTokens)
    }

    @Test fun oddShapesDoNotCrash() {
        assertNotNull(GeminiResponse.parse("""{"candidates":"x","promptFeedback":5,"usageMetadata":[]}"""))
        assertNotNull(GeminiResponse.parse("""{"candidates":[1,null,"a"]}"""))
        assertNotNull(GeminiResponse.parse("""{"candidates":[{"content":{"parts":[1,{"text":5},null]}}]}"""))
    }

    @Test fun badJsonGivesNull() {
        assertNull(GeminiResponse.parse(""))
        assertNull(GeminiResponse.parse("{"))
        assertNull(GeminiResponse.parse("[1]"))
        assertNull(GeminiResponse.parse("<html>502</html>"))
    }
}
