package com.carteltv.tv.data.network

import com.carteltv.tv.data.EmptySharedPreferences
import com.carteltv.tv.data.espn.EspnScoreboardClient
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.weather.WeatherClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClientTransportTest {
    private lateinit var original: com.carteltv.tv.data.network.HttpTransport

    @Before fun save() {
        original = HttpSupport.transport
        AppPreferences.install(EmptySharedPreferences)
    }
    @After fun restore() { HttpSupport.transport = original }

    private fun install(routes: Map<String, String>) = FakeHttpTransport(routes).also { HttpSupport.transport = it }

    @Test fun `espn parses events and drops malformed ones`() = runBlocking {
        val fake = install(mapOf("basketball/nba/scoreboard" to ESPN))
        val scores = EspnScoreboardClient().scoreboard("basketball/nba")
        assertEquals(1, scores.size)
        val g = scores.single()
        assertEquals("Lakers at Celtics", g.name)
        assertEquals("LAL", g.abbrA)
        assertEquals("112", g.scoreA)
        assertEquals("BOS", g.abbrB)
        assertEquals("in", g.state)
        assertEquals("ESPN", g.network)
        assertTrue(fake.requested.single().endsWith("/scoreboard"))
    }

    @Test fun `espn appends date query and survives network failure`() = runBlocking {
        val fake = install(mapOf("never" to ""))
        assertTrue(EspnScoreboardClient().scoreboard("hockey/nhl", "20260101").isEmpty())
        assertTrue(fake.requested.single().endsWith("/scoreboard?dates=20260101"))
    }

    @Test fun `weather maps current conditions`() = runBlocking {
        install(mapOf("open-meteo" to WEATHER))
        val c = WeatherClient().current(40.0, -70.0)!!
        assertEquals(21.5, c.tempC, 0.0)
        assertEquals(60, c.humidity)
        assertEquals("Rain", c.label)
        assertTrue(c.isDay)
        assertEquals(25.0, c.highC!!, 0.0)
        assertEquals(15.0, c.lowC!!, 0.0)
    }

    @Test fun `weather returns null on garbage and on transport failure`() = runBlocking {
        install(mapOf("open-meteo" to "{}"))
        assertNull(WeatherClient().current(1.0, 2.0))
        install(emptyMap())
        assertNull(WeatherClient().current(1.0, 2.0))
    }

    private companion object {
        const val ESPN = """{"events":[
          {"name":"Lakers at Celtics","date":"2026-01-01T00:00Z",
           "status":{"displayClock":"4:12","type":{"state":"in","detail":"Q3"}},
           "competitions":[{"competitors":[
             {"score":"112","team":{"abbreviation":"LAL","shortDisplayName":"Lakers"}},
             {"score":"108","team":{"abbreviation":"BOS","shortDisplayName":"Celtics"}}],
             "broadcasts":[{"names":["ESPN"]}]}]},
          {"name":"Broken"}]}"""
        const val WEATHER = """{"current":{"temperature_2m":21.5,"relative_humidity_2m":60,"is_day":1,"weather_code":61},
          "daily":{"temperature_2m_max":[25.0],"temperature_2m_min":[15.0]}}"""
    }
}
