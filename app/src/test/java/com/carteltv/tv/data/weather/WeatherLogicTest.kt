package com.carteltv.tv.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WeatherLogicTest {
    private val brisbane = -27.47 to 153.02
    private val tz = 10 * 3600

    private fun utc(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    @Test fun feedDecoderExpandsDictionaryCodes() {
        // 'a','b' learn "ab" as code 256; code 256 then expands to "ab"
        assertEquals("ababc", Lightning.decode("ab\u0100c"))
        assertEquals("", Lightning.decode(""))
        assertEquals("x", Lightning.decode("x"))
    }

    @Test fun placeRoundTripsAndSurvivesSeparators() {
        val p = Place("Bris|bane", "Queens\nland", "Australia", -27.47, 153.02)
        val back = Place.decode(p.encode())!!
        assertEquals(-27.47, back.lat, 1e-9)
        assertEquals("Bris bane", back.name)
        assertNull(Place.decode("garbage"))
    }

    @Test fun sunriseMatchesOpenMeteoWithinTwoMinutes() {
        // Open-Meteo for Brisbane on 2026-11-21 reported sunrise 04:46 local.
        val midnight = utc(2026, 11, 21) - tz * 1000L
        val day = SunMoonTimes.sunDay(midnight, brisbane.first, brisbane.second)
        assertNotNull(day.sunrise)
        assertTrue("sunrise ${day.sunrise}", abs(day.sunrise!! - (4 * 60 + 46)) <= 2)
        assertTrue(day.sunset!! in (17 * 60 + 50)..(18 * 60 + 40))
        assertTrue(day.dawn!! < day.sunrise && day.dusk!! > day.sunset)
        assertTrue("longer days in November in the south", day.dayDeltaSec!! > 0)
        assertEquals(day.sunset - day.sunrise, day.dayLengthMin!!.toInt())
    }

    @Test fun moonPhaseIsRightForKnownNewAndFullMoons() {
        // New moon 2026-02-17 12:01 UTC, full moon 2026-03-03 11:38 UTC.
        assertTrue(SunMoonTimes.phaseDeg(utc(2026, 2, 17, 12, 1)).let { it < 4 || it > 356 })
        assertEquals(180.0, SunMoonTimes.phaseDeg(utc(2026, 3, 3, 11, 38)), 4.0)
        assertEquals("Full Moon", SunMoonTimes.phaseName(180.0))
    }

    @Test fun nextFullMoonIsFoundWithinAnHour() {
        val from = utc(2026, 2, 20)
        val moon = SunMoonTimes.moonDay(from, from, brisbane.first, brisbane.second)
        assertTrue(abs(moon.nextFullMs - utc(2026, 3, 3, 11, 38)) < 2 * 3_600_000L)
    }

    @Test fun tideTurningPointsAreRefinedAndAlternate() {
        val y = (0 until 72).map { Math.sin(it * 2 * Math.PI / 12.4) }
        val e = MarineService.turningPoints(y)
        assertTrue(e.size >= 8)
        e.zipWithNext().forEach { (a, b) -> assertTrue(a.high != b.high) }
        assertEquals(1.0, e.first { it.high }.levelM, 0.02)
        assertEquals(12.4 / 4 * 60, e.first { it.high }.minute.toDouble(), 3.0)
    }

    @Test fun stormThreatTracksNearestRecentStrike() {
        val now = 1_000_000_000L
        val (lat, lon) = brisbane
        fun at(km: Double, bearing: Double, ageMin: Int): Strike {
            val (a, b) = StormTracker.offset(lat, lon, bearing, km)
            return Strike(now - ageMin * 60_000L, a, b)
        }
        assertEquals(Threat.CLEAR, StormTracker.analyze(emptyList(), lat, lon, now).threat)
        assertEquals(Threat.DANGER, StormTracker.analyze(listOf(at(6.0, 90.0, 1)), lat, lon, now).threat)
        assertEquals(Threat.WARNING, StormTracker.analyze(listOf(at(20.0, 90.0, 1)), lat, lon, now).threat)
        assertEquals(Threat.WATCH, StormTracker.analyze(listOf(at(40.0, 90.0, 1)), lat, lon, now).threat)
        assertEquals(Threat.CLEAR, StormTracker.analyze(listOf(at(5.0, 90.0, 45)), lat, lon, now).threat)
        val r = StormTracker.analyze(listOf(at(20.0, 90.0, 1)), lat, lon, now)
        assertEquals(1, r.within[25])
        assertEquals(0, r.within[10])
        assertTrue(abs(r.nearestBearing!! - 90) <= 2)
    }

    @Test fun approachingCellGetsAnEtaAndRetreatingOneDoesNot() {
        val now = 2_000_000_000L
        val (lat, lon) = brisbane
        fun cluster(km: Double, ageMin: Int) = (0 until 8).map {
            val (a, b) = StormTracker.offset(lat, lon, 270.0, km + it * 0.1)
            Strike(now - ageMin * 60_000L - it * 1000L, a, b)
        }
        val approaching = StormTracker.analyze(cluster(30.0, 2) + cluster(50.0, 14), lat, lon, now).motion
        assertNotNull(approaching)
        assertNotNull(approaching!!.etaMin)
        assertTrue(approaching.bearingDeg in 80..100)
        val leaving = StormTracker.analyze(cluster(50.0, 2) + cluster(30.0, 14), lat, lon, now).motion
        assertNull(leaving?.etaMin)
    }

    private fun now(temp: Double = 24.0, code: Int = 2) = com.carteltv.tv.data.weather.WeatherNow(
        time = "2026-11-21T16:00", tempC = temp, feelsC = temp + 0.5, humidity = 60, dewC = 16.0, pressureHpa = 1015.0,
        windKmh = 10.0, gustKmh = 20.0, windDir = 40, cloud = 50, precipMm = 0.0, visibilityM = 30_000.0, uv = 2.0, code = code, isDay = true,
    )

    private fun forecastWith(nowcast: List<Double>, code: Int = 2) =
        com.carteltv.tv.data.weather.Forecast(0.0, 0.0, now(code = code), emptyList(), emptyList(), 0L, nowcast = nowcast)

    @Test fun nowcastNamesTheNextChange() {
        assertEquals("Rain in ~30 min", forecastWith(listOf(0.0, 0.0, 0.4, 0.5)).nowcastLine())
        assertEquals("Rain easing in ~45 min", forecastWith(listOf(0.6, 0.4, 0.2, 0.0)).nowcastLine())
        assertEquals("No rain for the next hour", forecastWith(listOf(0.0, 0.0, 0.0, 0.0)).nowcastLine())
        assertNull(forecastWith(emptyList()).nowcastLine())
        assertEquals("Snow in ~15 min", forecastWith(listOf(0.0, 0.3), code = 73).nowcastLine())
    }

    private fun day(models: Int, spread: Double, wet: Int) = com.carteltv.tv.data.weather.WeatherDay(
        "2026-11-22", 1, 28.0, 19.0, "2026-11-22T04:45", "2026-11-22T18:10", 9.0, 20, 0.0, 15.0,
        modelCount = models, maxSpreadC = spread, wetModels = wet,
    )

    @Test fun confidenceReflectsModelAgreement() {
        val c = com.carteltv.tv.data.weather.Confidence.values()
        assertNull(day(1, 0.0, 0).confidence)
        assertEquals(c[0], day(4, 0.8, 0).confidence)       // HIGH: close highs, all dry
        assertEquals(c[0], day(4, 1.2, 4).confidence)       // HIGH: close highs, all wet
        assertEquals(c[1], day(4, 2.5, 1).confidence)       // MEDIUM
        assertEquals(c[2], day(4, 5.0, 0).confidence)       // LOW: highs far apart
        assertEquals(c[2], day(4, 1.0, 2).confidence)       // LOW: models split on rain
    }

    @Test fun stationBlendCorrectsTheModelAndRejectsOutliers() {
        val obs = com.carteltv.tv.data.weather.StationObs("YBBN", "Brisbane Intl", 22.0, 12, 26.0, 18.0, 30, 16.7, null, 1018.0, 9654.0, 75)
        val blended = com.carteltv.tv.data.weather.WeatherObservations.blend(now(temp = 24.0), obs)
        assertEquals(26.0, blended.tempC, 0.001)
        assertEquals(26.5, blended.feelsC, 0.001)              // feels-like shifts with the measured bias
        assertEquals(1018.0, blended.pressureHpa, 0.001)
        assertEquals(75, blended.cloud)
        assertTrue(blended.humidity in 55..62)                 // Magnus from 26/18
        assertEquals(30_000.0, blended.visibilityM, 0.1)       // METAR's "6+" cap must not shrink a clear model view
        val bad = com.carteltv.tv.data.weather.WeatherObservations.blend(now(temp = 24.0), obs.copy(tempC = 40.0))
        assertEquals(24.0, bad.tempC, 0.001)
    }

    @Test fun noaaTideCurveHitsEachTurningPoint() {
        val events = listOf(
            com.carteltv.tv.data.weather.TideEvent(-300, 0.1, false), com.carteltv.tv.data.weather.TideEvent(60, 1.5, true),
            com.carteltv.tv.data.weather.TideEvent(420, 0.1, false), com.carteltv.tv.data.weather.TideEvent(780, 1.4, true),
            com.carteltv.tv.data.weather.TideEvent(5000, 0.2, false),
        )
        val h = com.carteltv.tv.data.weather.NoaaTides.hourly(events, 12)!!
        assertEquals(1.5, h[1], 1e-9)                                  // the high at minute 60
        assertTrue(h[4] in 0.6..1.0)                                   // halfway down is mid-level
        assertNull(com.carteltv.tv.data.weather.NoaaTides.hourly(events.take(3)))
    }

    @Test fun greatCircleBasics() {
        assertEquals(111.19, StormMath.km(0.0, 0.0, 1.0, 0.0), 0.2)
        assertEquals(90.0, StormMath.bearing(0.0, 0.0, 0.0, 1.0), 0.01)
    }

    @Test fun radarLevelMapsLegendColours() {
        assertEquals(0, com.carteltv.tv.data.weather.RadarFeed.level(0x00000000))
        assertEquals(1, com.carteltv.tv.data.weather.RadarFeed.level(0xFF04E9E7.toInt()))
        assertEquals(6, com.carteltv.tv.data.weather.RadarFeed.level(0xFFFDF802.toInt()))
        assertEquals(10, com.carteltv.tv.data.weather.RadarFeed.level(0xFFF800FD.toInt()))
        assertEquals("Heavy rain", com.carteltv.tv.data.weather.RadarFeed.intensityWord(7))
    }
}
