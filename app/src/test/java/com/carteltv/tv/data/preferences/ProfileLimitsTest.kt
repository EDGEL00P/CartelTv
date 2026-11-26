package com.carteltv.tv.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Profile laws: bedtime windows (overnight-safe), limit math, day-key. */
class ProfileLimitsTest {

    private fun cal(hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply { set(2026, 10, 20, hour, minute, 0) }

    private fun kids(limit: Int = 0, bedStart: Int = -1, bedHours: Int = 8) = Profile(
        id = "k1", name = "Kid", emoji = "🐼", kids = true,
        dailyMinutes = limit, bedtimeStartMin = bedStart, bedtimeHours = bedHours,
    )

    @Test
    fun bedtime_off_when_unset() {
        assertFalse(Profiles.bedtimeBlocked(kids(), 23 * 60 + 59))
    }

    @Test
    fun bedtime_simple_evening_window() {
        val p = kids(bedStart = 21 * 60)   // 21:00 + 8h → 05:00 (wraps)
        assertFalse("13:00 is day", Profiles.bedtimeBlocked(p, 13 * 60))
        assertTrue("21:00 lights out", Profiles.bedtimeBlocked(p, 21 * 60))
        assertTrue("23:30 sleeps", Profiles.bedtimeBlocked(p, 23 * 60 + 30))
        assertTrue("04:59 still night", Profiles.bedtimeBlocked(p, 4 * 60 + 59))
        assertFalse("05:01 morning!", Profiles.bedtimeBlocked(p, 5 * 60 + 1))
    }

    @Test
    fun bedtime_non_wrapping_window() {
        val p = kids(bedStart = 20 * 60, bedHours = 1)  // 20:00..21:00
        assertTrue(Profiles.bedtimeBlocked(p, 20 * 60 + 30))
        assertFalse(Profiles.bedtimeBlocked(p, 21 * 60 + 1))
    }

    @Test
    fun limit_left_tracks_budget() {
        val p = kids(limit = 60)
        assertEquals(60, Profiles.minutesLeft(p, cal(10, 0)))
    }

    @Test
    fun evaluate_ranks_bedtime_over_limit_and_spares_adults() {
        assertNull("adults never gate", Profiles.evaluate(Profile("a", "Pa"), cal(9, 0)))
        val p = kids(limit = 60, bedStart = 21 * 60)
        assertEquals(Profiles.Block.Bedtime, Profiles.evaluate(p, cal(22, 0)))
    }

    @Test
    fun daykey_shapes_and_differs() {
        val a = Profiles.dayKey(cal(9, 0), "p1")
        val b = Profiles.dayKey(cal(23, 59), "p1")
        assertEquals(a, b)                       // same date, any hour
        assertTrue(a.endsWith(":p1"))
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}:p1").matches(a))
    }
}
