package com.pdfmanager.app.data

import com.pdfmanager.app.printing.PageRangeParser
import com.pdfmanager.app.printing.PageRangeResult

/**
 * What part of the PDF a question is about. Plain Kotlin, no Android classes, so every rule runs in unit tests.
 * All page numbers here are 1 based, as the reader sees them.
 */
sealed interface AiScope {

    /** One page. The reader's page, or the page chosen with the stepper. */
    data class Page(val number: Int) : AiScope

    /** The whole file. */
    data object Whole : AiScope

    /** The listed pages, sorted and without duplicates, as [AiScope.range] makes them. */
    data class Range(val pages: List<Int>) : AiScope

    /** Result of reading the Range field. */
    sealed interface Parsed {
        class Ok(val scope: Range) : Parsed
        /** [message] is the text from [PageRangeParser], shown under the field. */
        class Invalid(val message: String) : Parsed
    }

    /** The pages of the original PDF that go into the attached copy, in the order they are attached. */
    fun pageNumbers(totalPages: Int): List<Int> = when (this) {
        is Page -> listOf(number)
        Whole -> (1..totalPages).toList()
        is Range -> pages
    }

    /** True when every page of this scope exists in a PDF of [totalPages] pages. */
    fun isValidFor(totalPages: Int): Boolean = when (this) {
        is Page -> number in 1..totalPages
        Whole -> totalPages > 0
        is Range -> pages.isNotEmpty() && pages.all { it in 1..totalPages }
    }

    /**
     * The page of the original PDF for page [attachedPage] of the attached copy, or null when the copy has no such page.
     * A Page copy has one page, a Whole copy is the file itself, a Range copy holds the listed pages in order.
     */
    fun originalPage(attachedPage: Int): Int? = when (this) {
        is Page -> if (attachedPage == 1) number else null
        Whole -> if (attachedPage >= 1) attachedPage else null
        is Range -> pages.getOrNull(attachedPage - 1)
    }

    companion object {
        const val BAD_INPUT = "Use numbers like 1-3, 5, 8-10"

        /**
         * Reads the Range field (for example `3-7, 10`) with [PageRangeParser], so the messages are the same
         * as everywhere else. A blank field is not a range here: the parser would read it as every page.
         */
        fun range(text: String, totalPages: Int): Parsed {
            if (text.isBlank()) return Parsed.Invalid(BAD_INPUT)
            return when (val r = PageRangeParser.parse(text, totalPages)) {
                is PageRangeResult.Invalid -> Parsed.Invalid(r.message)
                is PageRangeResult.Valid -> Parsed.Ok(Range(r.pages.map { it + 1 }))
            }
        }

        /**
         * Whether the PDF must be attached to the next question. It is attached when nothing was attached yet
         * and whenever the scope differs from the last attached one: another page, another range, or Whole.
         */
        fun needsAttach(lastAttached: AiScope?, next: AiScope): Boolean = lastAttached != next

        /** `3 to 7, 10` for pages 3,4,5,6,7,10 and `5` for one page. */
        fun describePages(pages: List<Int>): String {
            val runs = ArrayList<String>()
            var i = 0
            while (i < pages.size) {
                var j = i
                while (j + 1 < pages.size && pages[j + 1] == pages[j] + 1) j++
                runs += if (j == i) "${pages[i]}" else if (j == i + 1) "${pages[i]}, ${pages[j]}" else "${pages[i]} to ${pages[j]}"
                i = j + 1
            }
            return runs.joinToString(", ")
        }

        /** `3-7, 10` for pages 3,4,5,6,7,10. The short form shown in the tag of a message. */
        fun rangeLabel(pages: List<Int>): String {
            val runs = ArrayList<String>()
            var i = 0
            while (i < pages.size) {
                var j = i
                while (j + 1 < pages.size && pages[j + 1] == pages[j] + 1) j++
                runs += if (j == i) "${pages[i]}" else "${pages[i]}-${pages[j]}"
                i = j + 1
            }
            return runs.joinToString(", ")
        }

        /** Tells Gemini how to write page references, so the app can turn them into chips. */
        const val NO_PAGE_REF_RULE = "Never mention page numbers or write references such as [p. N], (p. 3) or (page 3) in the answer."
        const val PAGE_REF_RULE = "When you mention a page, write it as [p. N] with the page number inside the attached PDF, where page 1 is the first page of the attached PDF, whatever page numbers are printed on the pages."

        /**
         * The one context line added to the system instruction, or null when there is nothing to say.
         * [readerPage] is the page the reader is on. Only the Whole scope uses it.
         */
        fun contextLine(scope: AiScope, totalPages: Int, readerPage: Int?): String? = when (scope) {
            is Page -> "The attached PDF is page ${scope.number} of a $totalPages page document. Answer only about this page unless asked otherwise."
            Whole -> readerPage?.takeIf { it in 1..totalPages }?.let { "The reader is currently on page $it of $totalPages." }
            is Range -> if (scope.pages.isEmpty()) null else {
                val word = if (scope.pages.size == 1) "page" else "pages"
                "The attached PDF holds $word ${describePages(scope.pages)} of a $totalPages page document."
            }
        }

        private val PAGE_REF = Regex("""\s?\[p\.\s*([0-9\u09E6-\u09EF]{1,6})]""")

        /**
         * Turns every `[p. N]` in an answer into the page of the original PDF. Page numbers of a Page or Range copy
         * count inside the copy, so they are mapped back. A number the copy does not have is removed, so a chip
         * never jumps to a wrong page. A Whole answer is returned as it is, with Bangla digits turned into 0-9.
         */
        fun remapPageRefs(text: String, scope: AiScope): String = PAGE_REF.replace(text) { m ->
            val n = m.groupValues[1].fold(0) { acc, c -> acc * 10 + Character.digit(c, 10) }
            val original = scope.originalPage(n)
            if (original == null) "" else m.value.substringBefore('[') + "[p. $original]"
        }
    }
}

/** One message in the Ask AI chat. [scope] is what the question was about, fixed when Send was tapped. */
class AskAiMessage(val role: String, val text: String, val scope: AiScope)
