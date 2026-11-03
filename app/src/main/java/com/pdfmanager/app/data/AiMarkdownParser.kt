package com.pdfmanager.app.data

/** A piece of a line: styled text, or a page reference taken from `[p. 12]`. */
sealed interface AiSpan {
    data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false) : AiSpan
    data class PageRef(val number: Int) : AiSpan
}

/** A block of an answer. [Bullet.level] and [Numbered.level] start at 0 for the outer list. */
sealed interface AiBlock {
    data class Heading(val level: Int, val spans: List<AiSpan>) : AiBlock
    data class Paragraph(val spans: List<AiSpan>) : AiBlock
    data class Bullet(val level: Int, val spans: List<AiSpan>) : AiBlock
    data class Numbered(val number: Int, val level: Int, val spans: List<AiSpan>) : AiBlock
    data class Code(val text: String) : AiBlock
    data class Table(val header: List<List<AiSpan>>?, val rows: List<List<List<AiSpan>>>) : AiBlock
    data object Rule : AiBlock
}

/**
 * A small Markdown reader for what Gemini returns: headings, bold, italic, bullets (nested), numbered lists, inline code,
 * code blocks, simple tables and page references. No library, no Android classes. It never throws and never drops text:
 * a marker that is not closed (a stray `**` while an answer is still streaming) stays as plain text.
 */
object AiMarkdownParser {

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
    private val BULLET = Regex("""^(\s*)[*+-]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val TABLE_SEPARATOR_CELL = Regex("""^:?-{3,}:?$""")

    fun parse(text: String): List<AiBlock> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<AiBlock>()
        val para = ArrayList<String>()
        val indents = ArrayList<Int>()
        fun flush() {
            if (para.isNotEmpty()) { out += AiBlock.Paragraph(spans(para.joinToString("\n"))); para.clear() }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.startsWith("```")) {
                flush(); indents.clear()
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code += lines[i]; i++ }
                i++ // the closing fence, if there is one
                out += AiBlock.Code(code.joinToString("\n"))
                continue
            }
            if (t.isEmpty()) { flush(); i++; continue }
            val heading = HEADING.matchEntire(t)
            if (heading != null) {
                flush(); indents.clear()
                out += AiBlock.Heading(heading.groupValues[1].length.coerceAtMost(3), spans(heading.groupValues[2].trim()))
                i++; continue
            }
            if (RULE.matches(t)) { flush(); indents.clear(); out += AiBlock.Rule; i++; continue }
            if (t.startsWith("|") && t.count { it == '|' } >= 2) {
                flush(); indents.clear()
                val rows = ArrayList<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows += lines[i].trim(); i++ }
                out += table(rows)
                continue
            }
            val bullet = BULLET.matchEntire(line)
            if (bullet != null) {
                flush()
                out += AiBlock.Bullet(level(indents, indent(bullet.groupValues[1])), spans(bullet.groupValues[2].trim()))
                i++; continue
            }
            val numbered = NUMBERED.matchEntire(line)
            if (numbered != null) {
                flush()
                out += AiBlock.Numbered(
                    numbered.groupValues[2].toInt(), level(indents, indent(numbered.groupValues[1])),
                    spans(numbered.groupValues[3].trim()),
                )
                i++; continue
            }
            indents.clear()
            para += t
            i++
        }
        flush()
        return out
    }

    private fun indent(s: String): Int = s.fold(0) { acc, ch -> acc + (if (ch == '	') 4 else 1) }

    /** Nesting level of a list item by its indent: deeper indent is a deeper level, whatever the step size is. */
    private fun level(stack: ArrayList<Int>, indent: Int): Int {
        while (stack.isNotEmpty() && stack.last() > indent) stack.removeAt(stack.size - 1)
        if (stack.isEmpty() || stack.last() < indent) stack += indent
        return stack.size - 1
    }

    private fun cells(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.substring(1)
        if (r.endsWith("|")) r = r.substring(0, r.length - 1)
        return r.split('|').map { it.trim() }
    }

    private fun table(rawRows: List<String>): AiBlock.Table {
        val all = rawRows.map { cells(it) }
        val hasHeader = all.size >= 2 && all[1].isNotEmpty() && all[1].all { TABLE_SEPARATOR_CELL.matches(it) }
        val body = (if (hasHeader) all.drop(2) else all)
            .filterNot { row -> row.isNotEmpty() && row.all { TABLE_SEPARATOR_CELL.matches(it) } }
        return AiBlock.Table(
            header = if (hasHeader) all[0].map { spans(it) } else null,
            rows = body.map { row -> row.map { spans(it) } },
        )
    }

    /** The pieces of one piece of text. Adjacent runs with the same style are joined. */
    fun spans(s: String): List<AiSpan> = merge(inline(s, bold = false, italic = false))

    private fun inline(s: String, bold: Boolean, italic: Boolean): List<AiSpan> {
        val out = ArrayList<AiSpan>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) { out += AiSpan.Run(buf.toString(), bold, italic); buf.setLength(0) }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '[') {
                val ref = pageRef(s, i)
                if (ref != null) { flush(); out += AiSpan.PageRef(ref.first); i = ref.second; continue }
            }
            if (c == '*' && s.startsWith("**", i)) {
                val close = s.indexOf("**", i + 2)
                if (close > i + 2) {
                    flush(); out += inline(s.substring(i + 2, close), true, italic); i = close + 2; continue
                }
                buf.append("**"); i += 2; continue
            }
            if (c == '*' && i + 1 < s.length && !s[i + 1].isWhitespace()) {
                val close = singleStar(s, i + 1)
                if (close > i + 1) {
                    flush(); out += inline(s.substring(i + 1, close), bold, true); i = close + 1; continue
                }
            }
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i + 1) {
                    flush(); out += AiSpan.Run(s.substring(i + 1, close), bold, italic, code = true); i = close + 1; continue
                }
            }
            buf.append(c); i++
        }
        flush()
        return out
    }

    /** The next `*` that is not part of `**` and does not follow a space, or -1. */
    private fun singleStar(s: String, from: Int): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == '*') {
                if (j + 1 < s.length && s[j + 1] == '*') { j += 2; continue }
                if (!s[j - 1].isWhitespace()) return j
            }
            j++
        }
        return -1
    }

    /** `[p. 12]` at [i]: the number and the index after the bracket, or null. */
    private fun pageRef(s: String, i: Int): Pair<Int, Int>? {
        if (!s.startsWith("[p.", i)) return null
        var j = i + 3
        while (j < s.length && s[j] == ' ') j++
        val start = j
        while (j < s.length && s[j] in '0'..'9' && j - start < 6) j++
        if (j == start || j >= s.length || s[j] != ']') return null
        val n = s.substring(start, j).toInt()
        return if (n >= 1) Pair(n, j + 1) else null
    }

    private fun merge(spans: List<AiSpan>): List<AiSpan> {
        val out = ArrayList<AiSpan>()
        for (sp in spans) {
            val last = out.lastOrNull()
            if (sp is AiSpan.Run && last is AiSpan.Run && last.bold == sp.bold && last.italic == sp.italic && last.code == sp.code) {
                out[out.size - 1] = last.copy(text = last.text + sp.text)
            } else if (!(sp is AiSpan.Run && sp.text.isEmpty())) {
                out += sp
            }
        }
        return out
    }

    /** The answer as plain text for Copy and Share: no Markdown marks, page references as `(p. 12)`. */
    fun toPlain(text: String): String {
        val blocks = parse(text)
        val sb = StringBuilder()
        var prevList = false
        for ((index, b) in blocks.withIndex()) {
            val list = b is AiBlock.Bullet || b is AiBlock.Numbered
            if (index > 0) sb.append(if (list && prevList) "\n" else "\n\n")
            sb.append(
                when (b) {
                    is AiBlock.Heading -> plain(b.spans)
                    is AiBlock.Paragraph -> plain(b.spans)
                    is AiBlock.Bullet -> "  ".repeat(b.level) + "- " + plain(b.spans)
                    is AiBlock.Numbered -> "  ".repeat(b.level) + "${b.number}. " + plain(b.spans)
                    is AiBlock.Code -> b.text
                    is AiBlock.Table -> ((if (b.header != null) listOf(b.header) else emptyList()) + b.rows)
                        .joinToString("\n") { row -> row.joinToString(" | ") { plain(it) } }
                    AiBlock.Rule -> "---"
                }
            )
            prevList = list
        }
        return sb.toString()
    }

    private fun plain(spans: List<AiSpan>): String = spans.joinToString("") {
        when (it) {
            is AiSpan.Run -> it.text
            is AiSpan.PageRef -> "(p. ${it.number})"
        }
    }
}
