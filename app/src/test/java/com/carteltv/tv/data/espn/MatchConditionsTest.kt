package com.carteltv.tv.data.espn

import com.carteltv.tv.data.espn.MatchConditionsSource.GeoResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MatchConditionsTest {

    private fun geo(name: String, admin1: String?, cc: String, country: String = cc, tz: String? = null) =
        GeoResult(name, admin1, country, cc, 1.0, 2.0, tz)

    private val raleighResults = listOf(
        geo("Raleigh", "North Carolina", "US", "United States", "America/New_York"),
        geo("Raleigh", "Mississippi", "US", "United States", "America/Chicago"),
        geo("Raleigh", "Illinois", "US", "United States", "America/Chicago"),
    )

    @Test fun aStateAbbreviationPicksTheRightRaleigh() {
        assertEquals("North Carolina", MatchConditionsSource.pickPlace(raleighResults, "Raleigh, NC")?.admin1)
        assertEquals("Mississippi", MatchConditionsSource.pickPlace(raleighResults, "Raleigh, MS")?.admin1)
        assertEquals("Illinois", MatchConditionsSource.pickPlace(raleighResults, "Raleigh, IL")?.admin1)
    }

    @Test fun aCountryNamePicksTheRightSydney() {
        val results = listOf(geo("Sydney", "Nova Scotia", "CA", "Canada"), geo("Sydney", "New South Wales", "AU", "Australia"))
        assertEquals("AU", MatchConditionsSource.pickPlace(results, "Sydney, Australia")?.countryCode)
    }

    @Test fun anEnglishRegionPicksTheBritishCity() {
        val results = listOf(geo("Manchester", "New Hampshire", "US", "United States"), geo("Manchester", "England", "GB", "United Kingdom"))
        assertEquals("GB", MatchConditionsSource.pickPlace(results, "Manchester, England")?.countryCode)
    }

    @Test fun provincesAndAustralianStatesAreUnderstood() {
        val results = listOf(geo("Toronto", "Ohio", "US", "United States"), geo("Toronto", "Ontario", "CA", "Canada"))
        assertEquals("CA", MatchConditionsSource.pickPlace(results, "Toronto, ON")?.countryCode)
        val perth = listOf(geo("Perth", "Tasmania", "AU", "Australia"), geo("Perth", "Western Australia", "AU", "Australia"))
        assertEquals("Western Australia", MatchConditionsSource.pickPlace(perth, "Perth, WA")?.admin1)
    }

    @Test fun withNoRegionTheFirstResultIsTaken() {
        assertEquals("North Carolina", MatchConditionsSource.pickPlace(raleighResults, "Raleigh")?.admin1)
    }

    @Test fun aRegionThatMatchesNothingIsRefusedNotGuessed() {
        assertNull(MatchConditionsSource.pickPlace(raleighResults, "Raleigh, Texas"))
        assertNull(MatchConditionsSource.pickPlace(emptyList(), "Raleigh, NC"))
    }

    @Test fun splitLocationSeparatesTheCityFromTheRegion() {
        assertEquals("Raleigh" to "NC", MatchConditionsSource.splitLocation("Raleigh, NC"))
        assertEquals("Sydney" to null, MatchConditionsSource.splitLocation("Sydney"))
        assertEquals("" to null, MatchConditionsSource.splitLocation(""))
    }

    @Test fun theGeocoderDocumentParsesIncludingTheZone() {
        val json = """{"results":[{"name":"Raleigh","latitude":35.77,"longitude":-78.64,"country_code":"US","country":"United States",
          "admin1":"North Carolina","timezone":"America/New_York"},{"name":"bad"}]}"""
        val r = MatchConditionsSource.parseGeo(json).single()
        assertEquals("America/New_York", r.timezone); assertEquals(35.77, r.lat, 0.0001)
        assertTrue(MatchConditionsSource.parseGeo("not json").isEmpty())
        assertTrue(MatchConditionsSource.parseGeo("{}").isEmpty())
    }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val forecast = """{
      "current":{"time":"2026-11-21T19:15","temperature_2m":18.3,"weather_code":3,"wind_speed_10m":15.3},
      "hourly":{"time":["2026-11-21T18:00","2026-11-21T19:00","2026-11-21T20:00","2026-11-21T21:00"],
        "temperature_2m":[17.0,18.0,19.5,12.0],"precipitation_probability":[0,5,40,80],
        "weather_code":[0,2,61,95],"wind_speed_10m":[10.0,12.0,25.0,40.0]}}"""

    @Test fun anUpcomingGameGetsTheHourOfKickoff() {
        val w = MatchConditionsSource.parseForecast(forecast, ms("2026-11-21T20:00:00Z"), live = false)!!
        assertEquals(19.5, w.tempC, 0.001); assertEquals("Rain", w.label); assertEquals(40, w.rainChance); assertEquals(25.0, w.windKmh!!, 0.001)
    }

    @Test fun aKickoffBetweenHoursUsesTheNearestOne() {
        assertEquals(12.0, MatchConditionsSource.parseForecast(forecast, ms("2026-11-21T20:50:00Z"), live = false)!!.tempC, 0.001)
    }

    @Test fun aKickoffBeyondTheForecastHasNoWeather() {
        assertNull(MatchConditionsSource.parseForecast(forecast, ms("2026-11-23T20:00:00Z"), live = false))
    }

    @Test fun aLiveGameGetsTheCurrentConditions() {
        val w = MatchConditionsSource.parseForecast(forecast, ms("2026-11-21T19:15:00Z"), live = true)!!
        assertEquals(18.3, w.tempC, 0.001); assertEquals("Overcast", w.label); assertEquals(5, w.rainChance)
    }

    @Test fun garbageIsNullNotACrash() {
        assertNull(MatchConditionsSource.parseForecast("nope", 0L, live = false))
        assertNull(MatchConditionsSource.parseForecast("{}", 0L, live = true))
    }

    @Test fun weatherCodesReadAsWords() {
        assertEquals("Clear", MatchConditionsSource.describe(0)); assertEquals("Fog", MatchConditionsSource.describe(45))
        assertEquals("Snow", MatchConditionsSource.describe(73)); assertEquals("Storm", MatchConditionsSource.describe(96))
        assertEquals("—", MatchConditionsSource.describe(1234))
    }

    @Test fun theSummaryOnlyMentionsRainAndWindWhenTheyMatter() {
        val t: (Double) -> String = { "${it.toInt()}°" }
        val w: (Double) -> String = { "${it.toInt()} km/h" }
        assertEquals("19° Rain · 40% rain · 25 km/h", MatchConditionsSource.summary(MatchConditions(19.5, "Rain", 40, 25.0, 61), t, w))
        assertEquals("18° Clear", MatchConditionsSource.summary(MatchConditions(18.0, "Clear", 5, 12.0, 0), t, w))
        assertEquals("18° Clear", MatchConditionsSource.summary(MatchConditions(18.0, "Clear", null, null, 0), t, w))
    }

    private val now = ms("2026-11-21T19:00:00Z")
    private fun game(state: String, start: Long, location: String? = "Raleigh, NC", indoor: Boolean? = false) = EspnScoreboardClient.Score(
        name = "B at A", detail = "", state = state, logoA = null, abbrA = "A", scoreA = null, logoB = null, abbrB = "B", scoreB = null,
        startMs = start, location = location, indoor = indoor,
    )

    @Test fun onlyOutdoorUnfinishedGamesWithACityNeedWeather() {
        assertTrue(MatchConditionsSource.wanted(game("pre", now + 3_600_000L), now))
        assertTrue(MatchConditionsSource.wanted(game("in", now - 600_000L), now))
        assertFalse("indoors", MatchConditionsSource.wanted(game("pre", now + 3_600_000L, indoor = true), now))
        assertFalse("finished", MatchConditionsSource.wanted(game("post", now - 3_600_000L), now))
        assertFalse("no city", MatchConditionsSource.wanted(game("pre", now + 3_600_000L, location = null), now))
        assertFalse("no start", MatchConditionsSource.wanted(game("pre", 0L), now))
        assertFalse("beyond the forecast", MatchConditionsSource.wanted(game("pre", now + 8L * 86_400_000L), now))
    }

    @Test fun anUnknownIndoorFlagStillAsks() {
        assertTrue(MatchConditionsSource.wanted(game("pre", now + 3_600_000L, indoor = null), now))
    }

    private val brisbane = ZoneId.of("Australia/Brisbane")
    private val newYork = ZoneId.of("America/New_York")

    @Test fun theVenueTimeShowsWhenTheGroundIsOnAnotherClock() {
        val kickoff = ms("2026-11-22T00:30:00Z") // 10:30 Brisbane, 19:30 EST at the ground
        assertEquals("19:30 EST", ScoreboardModel.venueTime(kickoff, newYork, brisbane, Locale.US))
    }

    @Test fun theVenueTimeFollowsDaylightSavingAtTheGround() {
        val july = ms("2026-07-22T00:30:00Z")
        assertEquals("20:30 EDT", ScoreboardModel.venueTime(july, newYork, brisbane, Locale.US))
    }

    @Test fun noVenueTimeWhenBothClocksAgree() {
        assertNull(ScoreboardModel.venueTime(ms("2026-11-22T00:30:00Z"), brisbane, brisbane, Locale.US))
        // Different zones that happen to share an offset on that date also agree.
        assertNull(ScoreboardModel.venueTime(ms("2026-11-22T00:30:00Z"), ZoneId.of("Australia/Lindeman"), brisbane, Locale.US))
    }

    @Test fun noVenueTimeWithoutAZoneOrATime() {
        assertNull(ScoreboardModel.venueTime(ms("2026-11-22T00:30:00Z"), null, brisbane))
        assertNull(ScoreboardModel.venueTime(0L, newYork, brisbane))
    }
}
