package com.carteltv.tv.data.espn

import com.carteltv.tv.data.espn.ScoreboardModel.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class ScoreboardModelTest {

    private val brisbane = ZoneId.of("Australia/Brisbane")
    private val now = Instant.parse("2026-11-22T02:00:00Z").toEpochMilli()
    private val minute = 60_000L

    private fun game(
        state: String, start: Long = 0L, a: String = "42", b: String = "35", short: String? = null, detail: String = "",
        clock: String? = null, venue: String? = null, location: String? = null, network: String? = null,
        networks: List<String> = emptyList(),
    ) = EspnScoreboardClient.Score(
        name = "Celtics at Lakers", detail = detail, state = state,
        logoA = "https://x/lal.png", abbrA = "LAL", scoreA = a, logoB = "https://x/bos.png", abbrB = "BOS", scoreB = b,
        nameA = "Lakers", nameB = "Celtics", recordA = "10-3", recordB = "9-4", startMs = start,
        network = network, networks = networks, venue = venue, location = location, shortDetail = short, clock = clock,
    )

    @Test fun countdownReadsNaturallyAtEveryScale() {
        assertEquals("2d 3h", ScoreboardModel.countdown(2 * 86_400_000L + 3 * 3_600_000L + 5 * minute))
        assertEquals("2h 15m", ScoreboardModel.countdown(2 * 3_600_000L + 15 * minute + 30_000L))
        assertEquals("14:05", ScoreboardModel.countdown(14 * minute + 5_000L))
        assertEquals("1:00", ScoreboardModel.countdown(minute))
        assertEquals("42s", ScoreboardModel.countdown(42_000L))
        assertEquals("1h 0m", ScoreboardModel.countdown(3_600_000L))
    }

    @Test fun aDueOrPastKickoffIsStarting() {
        assertEquals("Starting", ScoreboardModel.countdown(0L))
        assertEquals("Starting", ScoreboardModel.countdown(-5 * minute))
    }

    @Test fun theCountdownRefreshesEverySecondOnlyInTheLastHour() {
        assertEquals(1_000L, ScoreboardModel.tickMs(30 * minute))
        assertEquals(1_000L, ScoreboardModel.tickMs(0L))
        assertEquals(30_000L, ScoreboardModel.tickMs(3 * 3_600_000L))
        assertEquals(30_000L, ScoreboardModel.tickMs(-minute))
    }

    @Test fun anUpcomingGameShowsACountdownAndTheLocalKickoff() {
        val bug = ScoreboardModel.build(game("pre", start = now + 135 * minute), now, brisbane, locale = Locale.US)
        assertEquals(Phase.UPCOMING, bug.phase)
        assertEquals("STARTS IN 2H 15M", bug.status)
        assertEquals("14:15", bug.kickoff) // 02:00Z + 2h15 = 04:15Z = 14:15 in Brisbane (UTC+10)
    }

    @Test fun theKickoffTimeIsInTheChosenZone() {
        val start = Instant.parse("2026-11-22T00:00:00Z").toEpochMilli() // 10:00 in Brisbane
        assertEquals("10:00", ScoreboardModel.build(game("pre", start = start), now - 5 * 3_600_000L, brisbane, locale = Locale.US).kickoff)
        assertEquals("16:00", ScoreboardModel.build(game("pre", start = start), now - 5 * 3_600_000L, ZoneId.of("America/Los_Angeles"), locale = Locale.US).kickoff)
    }

    @Test fun aLiveGameShowsTheFeedsOwnLine() {
        assertEquals("End of 1st", ScoreboardModel.build(game("in", short = "End of 1st"), now, brisbane).status)
        assertEquals("Halftime", ScoreboardModel.build(game("in", short = "Halftime", detail = "Halftime"), now, brisbane).status)
        assertEquals("5:32 - 2nd", ScoreboardModel.build(game("in", short = "5:32 - 2nd"), now, brisbane).status)
    }

    @Test fun aLiveGameFallsBackToDetailThenClockThenLive() {
        assertEquals("P2 12:34", ScoreboardModel.build(game("in", detail = "P2 12:34"), now, brisbane).status)
        assertEquals("67'", ScoreboardModel.build(game("in", clock = "67'"), now, brisbane).status)
        assertEquals("LIVE", ScoreboardModel.build(game("in"), now, brisbane).status)
    }

    @Test fun aFinishedGameShowsTheFeedsFinalWording() {
        assertEquals("FINAL", ScoreboardModel.build(game("post", short = "Final"), now, brisbane).status)
        assertEquals("FT", ScoreboardModel.build(game("post", short = "FT"), now, brisbane).status)
        assertEquals("FINAL/OT", ScoreboardModel.build(game("post", short = "Final/OT"), now, brisbane).status)
        assertEquals("FINAL", ScoreboardModel.build(game("post", short = "3/17 - 1:30 AM EDT"), now, brisbane).status)
        assertEquals("FINAL", ScoreboardModel.build(game("post"), now, brisbane).status)
    }

    @Test fun anUpcomingGameWithNoStartTimeSaysSo() {
        assertEquals("UPCOMING", ScoreboardModel.build(game("pre", start = 0L), now, brisbane).status)
        assertEquals("", ScoreboardModel.build(game("pre", start = 0L), now, brisbane).kickoff)
    }

    @Test fun awayIsOnTheLeftAndHomeOnTheRight() {
        val bug = ScoreboardModel.build(game("in"), now, brisbane)
        assertEquals("BOS", bug.away.abbr); assertEquals("Celtics", bug.away.name); assertEquals("35", bug.away.score)
        assertEquals("LAL", bug.home.abbr); assertEquals("Lakers", bug.home.name); assertEquals("42", bug.home.score)
        assertEquals("https://x/bos.png", bug.away.logo)
    }

    @Test fun upcomingShowsRecordsNotScores() {
        val bug = ScoreboardModel.build(game("pre", start = now + minute), now, brisbane)
        assertNull(bug.away.score); assertNull(bug.home.score)
        assertEquals("9-4", bug.away.record); assertEquals("10-3", bug.home.record)
    }

    @Test fun theSpoilerShieldHidesEveryScore() {
        val bug = ScoreboardModel.build(game("post", short = "Final"), now, brisbane, shielded = true)
        assertEquals(ScoreboardModel.HIDDEN, bug.away.score); assertEquals(ScoreboardModel.HIDDEN, bug.home.score)
        assertEquals("FINAL", bug.status) // the result itself is not hidden by the shield, only the numbers
    }

    @Test fun aMissingScoreOnALiveGameIsZero() {
        val bug = ScoreboardModel.build(game("in").copy(scoreA = null, scoreB = null), now, brisbane)
        assertEquals("0", bug.away.score); assertEquals("0", bug.home.score)
    }

    @Test fun missingNamesFallBackToAbbreviationsAndBack() {
        val g = game("in").copy(nameA = null, nameB = null)
        val bug = ScoreboardModel.build(g, now, brisbane)
        assertEquals("BOS", bug.away.name); assertEquals("LAL", bug.home.name)
        val noAbbr = game("in").copy(abbrA = null, abbrB = null)
        val bug2 = ScoreboardModel.build(noAbbr, now, brisbane)
        assertEquals("CEL", bug2.away.abbr); assertEquals("LAK", bug2.home.abbr)
    }

    @Test fun theGroundAndCityAreJoined() {
        assertEquals("Lenovo Center · Raleigh, NC", ScoreboardModel.place("Lenovo Center", "Raleigh, NC"))
        assertEquals("SCG", ScoreboardModel.place("SCG", null))
        assertEquals("Sydney, Australia", ScoreboardModel.place(null, "Sydney, Australia"))
        assertNull(ScoreboardModel.place(null, null))
        assertNull(ScoreboardModel.place("  ", ""))
        assertEquals("Wembley", ScoreboardModel.place("Wembley", "Wembley")) // no repeat
    }

    @Test fun networksAreListedOnceNetworkFirst() {
        val bug = ScoreboardModel.build(game("pre", network = "ESPN", networks = listOf("ESPN", "SN", " ", "TSN")), now, brisbane)
        assertEquals(listOf("ESPN", "SN", "TSN"), bug.networks)
        assertTrue(ScoreboardModel.build(game("pre"), now, brisbane).networks.isEmpty())
    }

    @Test fun theNhlFeedFillsVenueAndLiveFields() {
        val json = """{"games":[{"id":1,"gameState":"LIVE","startTimeUTC":"2026-11-21T18:00:00Z",
          "venue":{"default":"Lenovo Center"},"venueLocation":{"default":"Raleigh"},
          "periodDescriptor":{"number":2},"clock":{"timeRemaining":"12:34"},
          "homeTeam":{"name":{"default":"Hurricanes"},"abbrev":"CAR","score":2},
          "awayTeam":{"name":{"default":"Maple Leafs"},"abbrev":"TOR","score":1},
          "tvBroadcasts":[{"network":"SN","market":"N"}]}]}"""
        val g = NhlScoreboardClient.parse(json).single()
        assertEquals("Lenovo Center", g.venue); assertEquals("Raleigh", g.location)
        assertEquals(2, g.period); assertEquals("12:34", g.clock); assertEquals("P2 12:34", g.shortDetail)
        val bug = ScoreboardModel.build(g, now, brisbane)
        assertEquals("P2 12:34", bug.status)
        assertEquals("Lenovo Center · Raleigh", bug.place)
    }
}
