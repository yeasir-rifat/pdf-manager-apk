package com.pdfmanager.app.data

/**
 * How long Google asks the app to wait after a 429. It comes as a `Retry-After` header (seconds) or as
 * `"retryDelay": "34s"` in the error body. Returns 0 when Google gave no usable delay, so nothing is blocked.
 * No Android classes, so it is unit testable. The wait is capped, so an odd value can never lock Send for long.
 */
object GeminiRetry {
    const val MAX_SECONDS = 120

    private val BODY = Regex("\"retryDelay\"\\s*:\\s*\"(\\d{1,6})(?:\\.(\\d{1,9}))?s\"")
    private val SECONDS = Regex("\\d{1,6}")

    /** Seconds to wait for [result], rounded up. Only a 429 has a wait. */
    fun seconds(result: GeminiHttpResult): Int {
        if (result.code != 429) return 0
        val header = result.headers["retry-after"]?.trim()
        if (header != null && SECONDS.matches(header)) return cap(header.toLong())
        val m = BODY.find(result.body) ?: return 0
        val whole = m.groupValues[1].toLong()
        val hasFraction = m.groupValues[2].any { it != '0' }
        return cap(if (hasFraction) whole + 1 else whole)
    }

    private fun cap(s: Long): Int = s.coerceIn(0L, MAX_SECONDS.toLong()).toInt()
}
