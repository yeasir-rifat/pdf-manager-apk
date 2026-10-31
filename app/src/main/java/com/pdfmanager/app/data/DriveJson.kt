package com.pdfmanager.app.data

/**
 * The little JSON that the Drive requests need: a few strings to write and small answers to read.
 * Android's own org.json is not used because it does not run in plain unit tests.
 * Objects come back as Map, arrays as List, numbers as Double.
 */
object DriveJson {

    /** [s] as a JSON string with quotes, e.g. say "hi" becomes "say \"hi\"". */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Reads [text]. Throws IllegalArgumentException when it is not valid JSON. */
    fun parse(text: String): Any? {
        val p = Reader(text)
        p.skipSpace()
        val value = p.value(0)
        p.skipSpace()
        require(p.atEnd()) { "Unexpected text after the JSON value" }
        return value
    }

    /** [text] read as an object, or null when it is not valid JSON or not an object. */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?>? =
        runCatching { parse(text) }.getOrNull() as? Map<String, Any?>

    /** A top-level string field such as "id", or null. */
    fun stringField(text: String, name: String): String? = parseObject(text)?.get(name) as? String

    /**
     * The reason Google gives for a failed request, from the error body. Reads both shapes:
     * error.errors[0].reason (for example exportSizeLimitExceeded) and error.details[].reason (SERVICE_DISABLED).
     * Returns null when the body has none. Only this short code is used, never the server's own message text.
     */
    fun errorReason(body: String): String? {
        val error = parseObject(body)?.get("error") as? Map<*, *> ?: return null
        val errors = error["errors"] as? List<*>
        val first = errors?.firstOrNull() as? Map<*, *>
        (first?.get("reason") as? String)?.let { if (it.isNotBlank()) return it }
        val details = error["details"] as? List<*>
        details?.forEach { d ->
            ((d as? Map<*, *>)?.get("reason") as? String)?.let { if (it.isNotBlank()) return it }
        }
        return null
    }

    /** The ids in a files.list answer: files[].id. Entries without an id are skipped. */
    fun fileIds(body: String): List<String> {
        val files = parseObject(body)?.get("files") as? List<*> ?: return emptyList()
        return files.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipSpace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "JSON is nested too deeply" }
            require(i < s.length) { "Unexpected end of JSON" }
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("Unexpected character")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Unexpected character" }
            i += word.length
            return v
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] in '0'..'9')) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("Bad number")
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated string" }
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        require(i < s.length) { "Unterminated string" }
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "Bad escape" }
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw IllegalArgumentException("Bad escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skipSpace()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                skipSpace()
                out.add(value(depth + 1))
                skipSpace()
                require(i < s.length) { "Unterminated array" }
                val c = s[i++]
                if (c == ']') return out
                require(c == ',') { "Expected , or ]" }
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipSpace()
                require(i < s.length && s[i] == '"') { "Expected a name" }
                val key = str()
                skipSpace()
                require(i < s.length && s[i] == ':') { "Expected :" }
                i++
                skipSpace()
                out[key] = value(depth + 1)
                skipSpace()
                require(i < s.length) { "Unterminated object" }
                val c = s[i++]
                if (c == '}') return out
                require(c == ',') { "Expected , or }" }
            }
        }
    }

    private const val MAX_DEPTH = 32
}
