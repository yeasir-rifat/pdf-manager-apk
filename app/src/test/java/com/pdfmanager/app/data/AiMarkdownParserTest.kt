package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMarkdownParserTest {

    private fun parse(s: String) = AiMarkdownParser.parse(s)
    private fun run(t: String, bold: Boolean = false, italic: Boolean = false, code: Boolean = false) = AiSpan.Run(t, bold, italic, code)

    @Test fun plainParagraph() {
        assertEquals(listOf(AiBlock.Paragraph(listOf(run("Hello world")))), parse("Hello world"))
    }

    @Test fun emptyAndBlankInputGiveNoBlocks() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("\n \n\t\n").isEmpty())
    }

    @Test fun emptyLinesSplitParagraphsAndManyOfThemChangeNothing() {
        val one = parse("A\n\nB")
        val many = parse("A\n\n\n\n\nB\n\n")
        assertEquals(2, one.size)
        assertEquals(one, many)
    }

    @Test fun aSingleNewlineStaysInsideOneParagraph() {
        assertEquals(listOf(AiBlock.Paragraph(listOf(run("line one\nline two")))), parse("line one\nline two"))
    }

    @Test fun headingsAreCappedAtLevelThree() {
        assertEquals(AiBlock.Heading(1, listOf(run("Title"))), parse("# Title").single())
        assertEquals(AiBlock.Heading(2, listOf(run("Sub"))), parse("## Sub").single())
        assertEquals(AiBlock.Heading(3, listOf(run("Deep"))), parse("##### Deep").single())
        assertEquals(AiBlock.Paragraph(listOf(run("#nospace"))), parse("#nospace").single())
    }

    @Test fun boldItalicAndCode() {
        assertEquals(
            listOf(run("a "), run("b", bold = true), run(" c "), run("d", italic = true), run(" e "), run("f", code = true)),
            AiMarkdownParser.spans("a **b** c *d* e `f`"),
        )
    }

    @Test fun italicInsideBold() {
        assertEquals(
            listOf(run("bold ", bold = true), run("it", bold = true, italic = true), run(" bold", bold = true)),
            AiMarkdownParser.spans("**bold *it* bold**"),
        )
    }

    @Test fun unclosedBoldStaysAsText() {
        assertEquals(listOf(run("**not closed")), AiMarkdownParser.spans("**not closed"))
        assertEquals(listOf(run("ok **half")), AiMarkdownParser.spans("ok **half"))
        assertEquals(listOf(run("a ** b")), AiMarkdownParser.spans("a ** b"))
    }

    @Test fun unclosedItalicAndCodeStayAsText() {
        assertEquals(listOf(run("*half")), AiMarkdownParser.spans("*half"))
        assertEquals(listOf(run("a `b")), AiMarkdownParser.spans("a `b"))
        assertEquals(listOf(run("2 * 3 * 4")), AiMarkdownParser.spans("2 * 3 * 4"))
    }

    @Test fun neverThrowsOnOddInput() {
        for (s in listOf("**", "*", "`", "***", "****", "[p.", "[p. ]", "[p. 99999999]", "|", "| a", "```", "- ", "1.", "\u09E7\u09E8 **\u0995")) {
            AiMarkdownParser.parse(s)
            AiMarkdownParser.toPlain(s)
        }
    }

    @Test fun bulletsWithAllThreeMarkers() {
        val b = parse("* one\n- two\n+ three")
        assertEquals(listOf(0, 0, 0), b.map { (it as AiBlock.Bullet).level })
        assertEquals(listOf(run("one")), (b[0] as AiBlock.Bullet).spans)
    }

    @Test fun nestedBulletsFollowTheIndent() {
        val b = parse("* top\n    * child\n        * grandchild\n    * child two\n* top two")
        assertEquals(listOf(0, 1, 2, 1, 0), b.map { (it as AiBlock.Bullet).level })
    }

    @Test fun nestedBulletsWorkWithTwoSpaceIndentToo() {
        val b = parse("- a\n  - b\n    - c")
        assertEquals(listOf(0, 1, 2), b.map { (it as AiBlock.Bullet).level })
    }

    @Test fun boldInsideABullet() {
        val b = parse("* **Term:** the meaning") .single() as AiBlock.Bullet
        assertEquals(listOf(run("Term:", bold = true), run(" the meaning")), b.spans)
    }

    @Test fun aBoldLineIsNotABullet() {
        assertTrue(parse("**Bold line**").single() is AiBlock.Paragraph)
        assertTrue(parse("*italic line*").single() is AiBlock.Paragraph)
    }

    @Test fun numberedListsKeepTheirNumbersAndNest() {
        val b = parse("1. first\n2. second\n    * inner\n3) third")
        assertEquals(AiBlock.Numbered(1, 0, listOf(run("first"))), b[0])
        assertEquals(AiBlock.Numbered(2, 0, listOf(run("second"))), b[1])
        assertEquals(AiBlock.Bullet(1, listOf(run("inner"))), b[2])
        assertEquals(AiBlock.Numbered(3, 0, listOf(run("third"))), b[3])
    }

    @Test fun aListAfterAParagraphStartsAtLevelZero() {
        val b = parse("    * a\nText\n* b")
        assertEquals(0, (b[0] as AiBlock.Bullet).level)
        assertEquals(0, (b[2] as AiBlock.Bullet).level)
    }

    @Test fun codeBlockKeepsItsLinesAndMarks() {
        val b = parse("Before\n```kotlin\nval a = **1**\n\nval b = 2\n```\nAfter")
        assertEquals(3, b.size)
        assertEquals(AiBlock.Code("val a = **1**\n\nval b = 2"), b[1])
    }

    @Test fun unclosedCodeBlockTakesTheRest() {
        val b = parse("```\nline 1\nline 2")
        assertEquals(AiBlock.Code("line 1\nline 2"), b.single())
    }

    @Test fun tableWithHeader() {
        val t = parse("| Name | Value |\n|---|---|\n| a | **1** |\n| b | 2 |").single() as AiBlock.Table
        assertEquals(listOf(listOf(run("Name")), listOf(run("Value"))), t.header)
        assertEquals(2, t.rows.size)
        assertEquals(listOf(listOf(run("a")), listOf(run("1", bold = true))), t.rows[0])
    }

    @Test fun tableWithoutHeaderAndWithAlignmentMarks() {
        val t = parse("| a | b |\n| c | d |").single() as AiBlock.Table
        assertEquals(null, t.header)
        assertEquals(2, t.rows.size)
        val t2 = parse("|A|B|\n|:---|---:|\n|1|2|").single() as AiBlock.Table
        assertEquals(2, t2.header!!.size)
        assertEquals(1, t2.rows.size)
    }

    @Test fun horizontalRule() {
        assertEquals(listOf(AiBlock.Paragraph(listOf(run("a"))), AiBlock.Rule, AiBlock.Paragraph(listOf(run("b")))), parse("a\n\n---\n\nb"))
    }

    // ---- page chips

    @Test fun pageReferenceBecomesAChip() {
        assertEquals(listOf(run("See "), AiSpan.PageRef(12), run(" for more")), AiMarkdownParser.spans("See [p. 12] for more"))
    }

    @Test fun pageReferenceAllowsNoSpaceAndSeveralChips() {
        assertEquals(listOf(AiSpan.PageRef(3), run(" and "), AiSpan.PageRef(14)), AiMarkdownParser.spans("[p.3] and [p. 14]"))
    }

    @Test fun pageReferenceInsideBoldKeepsAChip() {
        assertEquals(listOf(run("Key ", bold = true), AiSpan.PageRef(5)), AiMarkdownParser.spans("**Key [p. 5]**"))
    }

    @Test fun badPageReferencesStayText() {
        for (s in listOf("[p. ]", "[p. x]", "[p. 0]", "[p. 12", "[page 12]", "[p. 1234567]")) {
            assertTrue(s, AiMarkdownParser.spans(s).none { it is AiSpan.PageRef })
            assertEquals(s, listOf(run(s)), AiMarkdownParser.spans(s))
        }
    }

    @Test fun pageReferenceInABullet() {
        val b = parse("* defined here [p. 7]").single() as AiBlock.Bullet
        assertEquals(listOf(run("defined here "), AiSpan.PageRef(7)), b.spans)
    }

    // ---- other scripts

    @Test fun banglaTextPassesThroughUntouched() {
        val bn = "\u098F\u099F\u09BF \u098F\u0995\u099F\u09BF **\u0997\u09C1\u09B0\u09C1\u09A4\u09CD\u09AC\u09AA\u09C2\u09B0\u09CD\u09A3** \u09AC\u09BF\u09B7\u09AF\u09BC [p. 4]"
        val sp = AiMarkdownParser.spans(bn)
        assertEquals(AiSpan.PageRef(4), sp.last())
        assertEquals(run("\u0997\u09C1\u09B0\u09C1\u09A4\u09CD\u09AC\u09AA\u09C2\u09B0\u09CD\u09A3", bold = true), sp[1])
    }

    // ---- plain text for Copy and Share

    @Test fun plainTextHasNoMarksAndKeepsStructure() {
        val md = "# Title\n\nSome **bold** and *soft* text [p. 3].\n\n* one\n    * inner\n* two\n\n1. first\n2. second\n\n`code`"
        val plain = AiMarkdownParser.toPlain(md)
        assertEquals(
            "Title\n\nSome bold and soft text (p. 3).\n\n- one\n  - inner\n- two\n1. first\n2. second\n\ncode",
            plain,
        )
        assertFalse(plain.contains('*'))
        assertFalse(plain.contains('#'))
        assertFalse(plain.contains('`'))
    }

    @Test fun plainTextOfATableAndCode() {
        assertEquals("A | B\n1 | 2", AiMarkdownParser.toPlain("| A | B |\n|---|---|\n| 1 | 2 |"))
        assertEquals("x = **1**", AiMarkdownParser.toPlain("```\nx = **1**\n```"))
    }

    @Test fun plainTextOfEmptyIsEmpty() {
        assertEquals("", AiMarkdownParser.toPlain(""))
    }
}
