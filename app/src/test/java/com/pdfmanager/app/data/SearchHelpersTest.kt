package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SearchHelpersTest {

    // ------------------------------------------------------------ fold

    @Test fun foldLowercasesAndCollapsesSpaces() = assertEquals("hello world", SearchHelpers.fold("  Hello \n  WORLD\t"))

    @Test fun foldJoinsLigatures() = assertEquals("fine office", SearchHelpers.fold("ﬁne oﬃce"))

    @Test fun foldDropsSoftHyphenAndZeroWidthSpace() = assertEquals("cooperate", SearchHelpers.fold("co\u00ADoper\u200Bate"))

    @Test fun nonBreakingSpaceIsASpace() = assertEquals("a b", SearchHelpers.fold("a\u00A0b"))

    @Test fun banglaJoinersDoNotChangeTheMatch() {
        // Ro + hasanta + ZWJ + Ya (ra-phala style) must equal the same letters without the joiner
        assertEquals(SearchHelpers.fold("র্য"), SearchHelpers.fold("র্\u200Dয"))
        assertEquals(SearchHelpers.fold("ক্ষ"), SearchHelpers.fold("ক্\u200Cষ"))
    }

    @Test fun banglaTextIsKept() = assertEquals("আমি ভাত খাই", SearchHelpers.fold("আমি  ভাত\nখাই "))

    @Test fun emptyStaysEmpty() = assertEquals("", SearchHelpers.fold(""))

    @Test fun onlySpacesBecomeEmpty() = assertEquals("", SearchHelpers.fold(" \n\t "))

    // ------------------------------------------------------------ textTerms

    @Test fun termsAreFoldedAndUnique() = assertEquals(listOf("hello", "world"), SearchHelpers.textTerms("  Hello  hello WORLD "))

    @Test fun oneLetterWordsAreIgnored() = assertEquals(listOf("programming"), SearchHelpers.textTerms("c programming"))

    @Test fun onlyShortWordsGiveNoTerms() = assertTrue(SearchHelpers.textTerms("a b").isEmpty())

    @Test fun blankQueryGivesNoTerms() = assertTrue(SearchHelpers.textTerms("   ").isEmpty())

    @Test fun atMostFiveTerms() = assertEquals(5, SearchHelpers.textTerms("aa bb cc dd ee ff gg").size)

    // ------------------------------------------------------------ SQL

    @Test fun sqlHasOneConditionPerTerm() {
        val sql = SearchHelpers.hitSql(2)
        assertTrue(sql.contains("instr(text, ?) > 0 AND instr(text, ?) > 0"))
        assertEquals(4, sql.count { it == '?' })
        assertTrue(sql.contains("LIMIT ${SearchHelpers.MAX_HITS}"))
    }

    @Test fun argumentsMatchThePlaceholders() {
        val terms = listOf("ab", "cd", "ef")
        assertEquals(SearchHelpers.hitSql(3).count { it == '?' }, SearchHelpers.hitArgs(terms).size)
        assertEquals(listOf<Any?>("ab", "ab", "ab", "cd", "ef"), SearchHelpers.hitArgs(terms).toList())
    }

    @Test fun sqlRefusesZeroOrTooManyTerms() {
        for (n in listOf(0, SearchHelpers.MAX_TERMS + 1)) {
            try {
                SearchHelpers.hitSql(n)
                fail("expected an error for $n terms")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    // ------------------------------------------------------------ snippet

    @Test fun shortTextNearTheStartIsUnchanged() = assertEquals("hello world", SearchHelpers.snippet("hello world", 1))

    @Test fun cutFrontStartsAtAWordAndShowsDots() = assertEquals("…hello world", SearchHelpers.snippet("xxxxx hello world", 100))

    @Test fun cutBackEndsAtAWordAndShowsDots() {
        val raw = "word ".repeat(24) // exactly SNIPPET_LENGTH characters
        val s = SearchHelpers.snippet(raw, 1)
        assertTrue(s.endsWith("word…"))
        assertFalse(s.startsWith("…"))
    }

    // ------------------------------------------------------------ highlightRanges

    @Test fun findsEveryOccurrenceInOrder() =
        assertEquals(
            listOf(0..2, 5..6, 9..10, 15..17, 20..21),
            SearchHelpers.highlightRanges("the cat sat on the mat", listOf("the", "at"))
        )

    @Test fun overlappingRangesAreJoined() = assertEquals(listOf(0..3), SearchHelpers.highlightRanges("abcd", listOf("abc", "bcd")))

    @Test fun touchingRangesAreJoined() = assertEquals(listOf(0..3), SearchHelpers.highlightRanges("abcd", listOf("ab", "cd")))

    @Test fun noMatchGivesNoRanges() = assertTrue(SearchHelpers.highlightRanges("abc", listOf("zz")).isEmpty())

    // ------------------------------------------------------------ stamp

    @Test fun stampChangesWithSizeOrDate() {
        assertEquals("12-34", SearchHelpers.stampOf(12, 34))
        assertFalse(SearchHelpers.stampOf(12, 34) == SearchHelpers.stampOf(12, 35))
        assertFalse(SearchHelpers.stampOf(12, 34) == SearchHelpers.stampOf(13, 34))
    }
}
