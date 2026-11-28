package com.carteltv.tv.ui.hero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HolidayCountdownTest {
    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test fun splitsTheRemainingTimeIntoDaysAndALiveClock() {
        val time = holidayTime(29 * day + 16 * hour + 7 * minute + 42 * second + 999)

        assertEquals("29", time.days)
        assertEquals("16", time.hours)
        assertEquals("07", time.minutes)
        assertEquals("42", time.seconds)
        assertFalse(time.arrived)
    }

    @Test fun padsTheClockButKeepsTheDayCountNatural() {
        assertEquals(HolidayTime("1", "00", "00", "09", false), holidayTime(day + 9 * second))
        assertEquals("120", holidayTime(120 * day).days)
    }

    @Test fun stopsAtZeroWhenTheHolidayArrives() {
        assertEquals(HolidayTime("0", "00", "00", "00", true), holidayTime(0))
        assertEquals(HolidayTime("0", "00", "00", "00", true), holidayTime(-5 * minute))
    }
}
