package com.carteltv.tv.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirQualityTest {
    // The air-quality service's answer for Kuala Lumpur, read on 25 Nov 2026.
    private val feed = """
        {"latitude":3.0999985,"longitude":101.70001,"generationtime_ms":0.478,"utc_offset_seconds":28800,
         "timezone":"Asia/Kuala_Lumpur","timezone_abbreviation":"GMT+8","elevation":62.0,
         "current_units":{"time":"iso8601","interval":"seconds","european_aqi":"EAQI","us_aqi":"USAQI","pm10":"μg/m³","pm2_5":"μg/m³"},
         "current":{"time":"2026-11-25T07:00","interval":3600,"european_aqi":50,"us_aqi":79,"pm10":17.8,"pm2_5":17.4}}
    """.trimIndent()

    @Test
    fun readsTheIndexAndTheDustBehindIt() {
        val air = AirQualityService.parse(feed)!!
        assertEquals(79, air.usAqi)
        assertEquals(50, air.europeanAqi)
        assertEquals(17.4, air.pm25!!, 0.0)
        assertEquals(17.8, air.pm10!!, 0.0)
        assertEquals("2026-11-25T07:00", air.updatedAt)
    }

    @Test
    fun aFeedWithoutTheIndexOrWithNoReadingIsNoAnswer() {
        assertNull(AirQualityService.parse("""{"current":{"pm10":3.0}}"""))
        assertNull(AirQualityService.parse("""{"error":true,"reason":"no data"}"""))
    }

    @Test
    fun theIndexFallsIntoTheBandsTheAgencyNames() {
        assertEquals(AirLevel.GOOD, AirLevel.of(0))
        assertEquals(AirLevel.GOOD, AirLevel.of(50))
        assertEquals(AirLevel.MODERATE, AirLevel.of(51))
        assertEquals(AirLevel.MODERATE, AirLevel.of(100))
        assertEquals(AirLevel.SENSITIVE, AirLevel.of(101))
        assertEquals(AirLevel.UNHEALTHY, AirLevel.of(151))
        assertEquals(AirLevel.VERY_UNHEALTHY, AirLevel.of(201))
        assertEquals(AirLevel.HAZARDOUS, AirLevel.of(301))
        assertEquals(AirLevel.HAZARDOUS, AirLevel.of(500))
        assertEquals("Unhealthy for sensitive groups", AirLevel.SENSITIVE.label)
        assertEquals(AirLevel.MODERATE, AirQualityService.parse(feed)!!.level)
    }

    @Test
    fun aReadingOffTheScaleStaysInsideTheBands() {
        assertEquals(AirLevel.HAZARDOUS, AirLevel.of(720))
        assertEquals(AirLevel.GOOD, AirLevel.of(-1))
    }
}
