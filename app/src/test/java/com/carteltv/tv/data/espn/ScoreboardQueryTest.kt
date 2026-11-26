package com.carteltv.tv.data.espn

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreboardQueryTest {
    @Test fun proLeagueWithoutDateHasNoQuery() = assertEquals("", EspnScoreboardClient.query("football/nfl", null))
    @Test fun dateIsPassedThrough() = assertEquals("?dates=20261122", EspnScoreboardClient.query("football/nfl", "20261122"))
    @Test fun collegeBoardAsksForTheFullDivision() =
        assertEquals("?dates=20261122&groups=50&limit=150", EspnScoreboardClient.query("basketball/mens-college-basketball", "20261122"))
    @Test fun startTimeWithoutSecondsParses() {
        assertEquals(java.time.Instant.parse("2026-11-22T03:00:00Z").toEpochMilli(), EspnScoreboardMapper.parseStartMs("2026-11-22T03:00Z"))
        assertEquals(java.time.Instant.parse("2026-11-22T03:00:05Z").toEpochMilli(), EspnScoreboardMapper.parseStartMs("2026-11-22T03:00:05Z"))
        assertEquals(0L, EspnScoreboardMapper.parseStartMs("nope"))
    }
}

