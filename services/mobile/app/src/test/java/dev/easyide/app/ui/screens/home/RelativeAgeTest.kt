package dev.easyide.app.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeAgeTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val now = 1_000 * day

    private fun age(ago: Long) = relativeAge(now, now - ago)

    @Test fun `under a minute and future timestamps read as now`() {
        assertEquals(RelativeAge(AgeUnit.NOW, 0), age(0))
        assertEquals(RelativeAge(AgeUnit.NOW, 0), age(minute - 1))
        assertEquals(RelativeAge(AgeUnit.NOW, 0), age(-5 * minute))
    }

    @Test fun `bucket boundaries`() {
        assertEquals(RelativeAge(AgeUnit.MINUTES, 1), age(minute))
        assertEquals(RelativeAge(AgeUnit.MINUTES, 59), age(hour - 1))
        assertEquals(RelativeAge(AgeUnit.HOURS, 1), age(hour))
        assertEquals(RelativeAge(AgeUnit.HOURS, 23), age(day - 1))
        assertEquals(RelativeAge(AgeUnit.DAYS, 1), age(day))
        assertEquals(RelativeAge(AgeUnit.DAYS, 6), age(7 * day - 1))
        assertEquals(RelativeAge(AgeUnit.WEEKS, 1), age(7 * day))
        assertEquals(RelativeAge(AgeUnit.WEEKS, 4), age(30 * day - 1))
        assertEquals(RelativeAge(AgeUnit.MONTHS, 1), age(30 * day))
        assertEquals(RelativeAge(AgeUnit.MONTHS, 12), age(365 * day - 1))
        assertEquals(RelativeAge(AgeUnit.YEARS, 1), age(365 * day))
        assertEquals(RelativeAge(AgeUnit.YEARS, 2), age(800 * day))
    }
}
