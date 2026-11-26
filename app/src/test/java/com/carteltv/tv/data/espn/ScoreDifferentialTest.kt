package com.carteltv.tv.data.espn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreDiffTest {

    private fun score(state: String, scoreA: String? = "0", scoreB: String? = "0", detail: String = "") =
        EspnScoreboardClient.Score(
            name = "A at B", detail = detail, state = state,
            logoA = null, abbrA = "A", scoreA = scoreA,
            logoB = null, abbrB = "B", scoreB = scoreB,
        )

    @Test
    fun `first snapshot never alerts`() {
        assertNull(ScoreDifferential.diff(null, score("in")))
    }

    @Test
    fun `pre to in is kickoff`() {
        assertEquals(ScoreDifferential.Event.KICKOFF, ScoreDifferential.diff(score("pre"), score("in")))
    }

    @Test
    fun `score change is score`() {
        assertEquals(ScoreDifferential.Event.SCORE, ScoreDifferential.diff(score("in", "7", "0"), score("in", "14", "0")))
    }

    @Test
    fun `to post is final`() {
        assertEquals(ScoreDifferential.Event.FINAL, ScoreDifferential.diff(score("in", "14", "7"), score("post", "14", "7")))
    }

    @Test
    fun `kickoff beats a score in the same cycle`() {
        assertEquals(ScoreDifferential.Event.KICKOFF, ScoreDifferential.diff(score("pre", "0", "0"), score("in", "7", "0")))
    }

    @Test
    fun `clock-only change is null`() {
        assertNull(ScoreDifferential.diff(score("in", "7", "0", detail = "5:32"), score("in", "7", "0", detail = "5:00")))
    }

    @Test
    fun `after final nothing more is reported`() {
        assertNull(ScoreDifferential.diff(score("post", "14", "7"), score("post", "14", "7")))
    }
}
