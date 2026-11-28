package com.carteltv.tv.engine.stability

import com.carteltv.tv.engine.stability.ViewerPresenceGuard.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceGuardTest {

    private val hour = ViewerPresenceGuard.HOUR_MS
    private val grace = ViewerPresenceGuard.GRACE_MS

    private fun guard(hours: Long = 2) = ViewerPresenceGuard(hours * hour).also { it.input(0) }

    @Test fun offNeverAsks() {
        val g = ViewerPresenceGuard(0)
        assertEquals(State.OK, g.check(0, true))
        assertEquals(State.OK, g.check(100 * hour, true))
    }

    @Test fun asksAfterTheLimitOfUnattendedPlayback() {
        val g = guard()
        assertEquals(State.OK, g.check(2 * hour - 1, true))
        assertEquals(State.ASK, g.check(2 * hour, true))
        assertTrue(g.asking)
    }

    @Test fun anyInputAnswersTheQuestionAndRestartsTheClock() {
        val g = guard()
        g.check(2 * hour, true)
        g.input(2 * hour + 5_000)
        assertFalse(g.asking)
        assertEquals(State.OK, g.check(3 * hour, true))
        assertEquals(State.ASK, g.check(4 * hour + 5_000, true))
    }

    @Test fun stopsWhenNobodyAnswersInTime() {
        val g = guard()
        g.check(2 * hour, true)
        assertEquals(State.ASK, g.check(2 * hour + grace - 1, false))
        assertEquals(State.STOP, g.check(2 * hour + grace, false))
    }

    @Test fun pausedTimeDoesNotCount() {
        val g = guard()
        assertEquals(State.OK, g.check(hour + 1, false))
        assertEquals(State.OK, g.check(2 * hour + 1, true))
        assertEquals(State.ASK, g.check(3 * hour + 1, true))
    }

    @Test fun countdownReportsWholeSecondsLeft() {
        val g = guard()
        g.check(2 * hour, true)
        assertEquals(60, g.secondsLeft(2 * hour))
        assertEquals(31, g.secondsLeft(2 * hour + 29_001))
        assertEquals(0, g.secondsLeft(2 * hour + grace))
    }

    @Test fun noQuestionOpenMeansNoCountdown() {
        assertEquals(0, guard().secondsLeft(5))
    }

    @Test fun firstCheckStartsTheClockWithoutAnInputEvent() {
        val g = ViewerPresenceGuard(hour)
        assertEquals(State.OK, g.check(10 * hour, true))
        assertEquals(State.OK, g.check(11 * hour - 1, true))
        assertEquals(State.ASK, g.check(11 * hour, true))
    }
}
