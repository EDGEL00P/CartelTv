package com.carteltv.tv.ui.hero

import org.junit.Assert.assertEquals
import org.junit.Test

class CountdownTest {
    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test fun underADayItTicksInHoursMinutesSeconds() {
        assertEquals("03:07:42", countdownText(3 * hour + 7 * minute + 42 * second))
        assertEquals("00:00:09", countdownText(9 * second))
        assertEquals("23:59:59", countdownText(day - second))
    }

    @Test fun aDayOrMoreAwayItShowsDaysAndHours() {
        assertEquals("1d 0h", countdownText(day))
        assertEquals("2d 4h", countdownText(2 * day + 4 * hour + 59 * minute))
        assertEquals("20d 23h", countdownText(20 * day + 23 * hour))
    }

    @Test fun afterTheStartItStopsAtZeroInsteadOfGoingNegative() {
        assertEquals("00:00:00", countdownText(0))
        assertEquals("00:00:00", countdownText(-5 * minute))
    }

    @Test fun partialSecondsAreTruncatedNotRoundedUp() {
        assertEquals("00:00:01", countdownText(1_999))
    }
}
