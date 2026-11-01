package com.pdfmanager.app.data

import com.pdfmanager.app.data.RecentHelpers.Ago
import com.pdfmanager.app.data.RecentHelpers.AgoUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentHelpersTest {
    private val now = 1_800_000_000L
    private fun ago(secondsBefore: Long) = RecentHelpers.ago(now, now - secondsBefore)

    @Test fun underOneMinuteIsNow() = assertEquals(Ago(AgoUnit.NOW, 0), ago(59))

    @Test fun aTimeInTheFutureIsNow() = assertEquals(Ago(AgoUnit.NOW, 0), RecentHelpers.ago(now, now + 500))

    @Test fun minutesCountWholeMinutes() {
        assertEquals(Ago(AgoUnit.MINUTES, 1), ago(60))
        assertEquals(Ago(AgoUnit.MINUTES, 59), ago(3599))
    }

    @Test fun hoursCountWholeHours() {
        assertEquals(Ago(AgoUnit.HOURS, 1), ago(3600))
        assertEquals(Ago(AgoUnit.HOURS, 23), ago(86_399))
    }

    @Test fun daysGoUpToSix() {
        assertEquals(Ago(AgoUnit.DAYS, 1), ago(86_400))
        assertEquals(Ago(AgoUnit.DAYS, 2), ago(2 * 86_400 + 5))
        assertEquals(Ago(AgoUnit.DAYS, 6), ago(6 * 86_400))
    }

    @Test fun weeksCoverSevenToTwentyNineDays() {
        assertEquals(Ago(AgoUnit.WEEKS, 1), ago(7 * 86_400))
        assertEquals(Ago(AgoUnit.WEEKS, 4), ago(29 * 86_400))
    }

    @Test fun monthsCoverThirtyToThreeHundredSixtyFourDays() {
        assertEquals(Ago(AgoUnit.MONTHS, 1), ago(30 * 86_400))
        assertEquals(Ago(AgoUnit.MONTHS, 12), ago(364 * 86_400))
    }

    @Test fun yearsStartAtThreeHundredSixtyFiveDays() {
        assertEquals(Ago(AgoUnit.YEARS, 1), ago(365 * 86_400))
        assertEquals(Ago(AgoUnit.YEARS, 3), ago(1100L * 86_400))
    }
}
