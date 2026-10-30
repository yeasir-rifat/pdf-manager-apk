package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilityHelpersTest {

    // ------------------------------------------------------------ MetadataHelpers.clean

    @Test fun cleanMakesOneLineWithSingleSpaces() = assertEquals("a b", MetadataHelpers.clean(" a \n b\t"))

    @Test fun cleanTurnsControlCharactersIntoSpaces() = assertEquals("a b", MetadataHelpers.clean("a\u0000b"))

    @Test fun cleanKeepsBanglaJoiners() {
        val s = "র্\u200Dয ক্\u200Cষ"
        assertEquals(s, MetadataHelpers.clean(s))
    }

    @Test fun cleanLimitsTheLength() = assertEquals(MetadataHelpers.MAX_FIELD, MetadataHelpers.clean("a".repeat(2000)).length)

    @Test fun cleanNeverSplitsASurrogatePair() {
        val out = MetadataHelpers.clean("a" + "😀".repeat(600))
        assertTrue(out.length <= MetadataHelpers.MAX_FIELD)
        assertFalse(Character.isHighSurrogate(out.last()))
    }

    // ------------------------------------------------------------ same / changedNames

    private val base = MetaFields("A", "B", "", "")

    @Test fun differencesInSpacingOnlyAreNotChanges() {
        assertTrue(MetadataHelpers.same(base, MetaFields("A ", " B", "", "")))
        assertTrue(MetadataHelpers.changedNames(base, MetaFields("A ", "B", "", "")).isEmpty())
    }

    @Test fun changedNamesFollowScreenOrder() =
        assertEquals(
            listOf("Author", "Keywords"),
            MetadataHelpers.changedNames(base, MetaFields("A", "C", "", "x"))
        )

    @Test fun clearingAFieldIsAChange() {
        assertFalse(MetadataHelpers.same(base, MetaFields("", "B", "", "")))
        assertEquals(listOf("Title"), MetadataHelpers.changedNames(base, MetaFields("", "B", "", "")))
    }

    @Test fun foundLine() {
        assertEquals("Title · Author · XMP", MetadataHelpers.foundLine(listOf("Title", "Author", "XMP")))
        assertEquals("Nothing stored", MetadataHelpers.foundLine(emptyList()))
    }

    // ------------------------------------------------------------ RepairHelpers.pageList

    @Test fun pageListJoinsRuns() = assertEquals("3, 7-9", RepairHelpers.pageList(listOf(3, 7, 8, 9)))
    @Test fun pageListSortsAndDropsRepeats() = assertEquals("1-2, 9", RepairHelpers.pageList(listOf(9, 1, 2, 2)))
    @Test fun pageListOfNothingIsEmpty() = assertEquals("", RepairHelpers.pageList(emptyList()))

    // ------------------------------------------------------------ RepairHelpers.summary

    @Test fun quickFixThatOpens() =
        assertEquals(
            "Rewrote the file with a clean structure (12 pages). It opens in the viewer. Saved in Documents/PDF Manager",
            RepairHelpers.summary(12, emptyList(), rebuilt = false, opens = true)
        )

    @Test fun onePageIsSingular() =
        assertTrue(RepairHelpers.summary(1, emptyList(), rebuilt = false, opens = true).contains("(1 page)"))

    @Test fun quickFixThatStillFailsSuggestsRebuild() =
        assertEquals(
            "Rewrote the file with a clean structure (3 pages). The system viewer still cannot open it. " +
                "Try Rebuild pages. Saved in Documents/PDF Manager",
            RepairHelpers.summary(3, emptyList(), rebuilt = false, opens = false)
        )

    @Test fun rebuildNamesTheLeftOutPages() =
        assertEquals(
            "Rebuilt 5 pages. Left out 1 page that could not be read (3). It opens in the viewer. Saved in Documents/PDF Manager",
            RepairHelpers.summary(5, listOf(3), rebuilt = true, opens = true)
        )

    @Test fun rebuildThatStillFailsDoesNotSuggestRebuildAgain() =
        assertEquals(
            "Rebuilt 3 pages. The system viewer still cannot open it. Saved in Documents/PDF Manager",
            RepairHelpers.summary(3, emptyList(), rebuilt = true, opens = false)
        )
}
