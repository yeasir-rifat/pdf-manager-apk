package com.pdfmanager.app.data

/** Pure helpers for the Recent files cards on Home. No Android classes, so they can be unit tested. */
object RecentHelpers {

    enum class AgoUnit { NOW, MINUTES, HOURS, DAYS, WEEKS, MONTHS, YEARS }

    /** "[count] [unit] ago". [count] is 0 for [AgoUnit.NOW] and at least 1 for every other unit. */
    data class Ago(val unit: AgoUnit, val count: Int)

    private const val MINUTE = 60L
    private const val HOUR = 3600L
    private const val DAY = 86400L

    /**
     * How long ago [thenSec] was, as seen at [nowSec] (both in seconds).
     * Under one minute, and any time in the future (clock changes), is NOW.
     * Then it counts whole minutes, hours, days (up to 6), weeks (up to 4), months (30 days) and years (365 days).
     */
    fun ago(nowSec: Long, thenSec: Long): Ago {
        val diff = nowSec - thenSec
        if (diff < MINUTE) return Ago(AgoUnit.NOW, 0)
        if (diff < HOUR) return Ago(AgoUnit.MINUTES, (diff / MINUTE).toInt())
        if (diff < DAY) return Ago(AgoUnit.HOURS, (diff / HOUR).toInt())
        val days = diff / DAY
        return when {
            days < 7 -> Ago(AgoUnit.DAYS, days.toInt())
            days < 30 -> Ago(AgoUnit.WEEKS, (days / 7).toInt())
            days < 365 -> Ago(AgoUnit.MONTHS, (days / 30).toInt())
            else -> Ago(AgoUnit.YEARS, (days / 365).toInt())
        }
    }
}
