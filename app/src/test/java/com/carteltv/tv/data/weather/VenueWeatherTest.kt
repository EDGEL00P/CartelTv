package com.carteltv.tv.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class VenueWeatherTest {

    private fun place(name: String, region: String, country: String, lat: Double = 0.0) = Place(name, region, country, lat, 0.0)

    private val raleighes = listOf(
        place("Raleigh", "North Carolina", "United States", 35.77),
        place("Raleigh", "Mississippi", "United States", 32.03),
        place("Raleigh", "Illinois", "United States", 37.83),
    )

    @Test fun aStateAbbreviationPicksThatStateNotJustTheFirstHit() {
        assertEquals("Mississippi", VenueWeather.bestMatch(raleighes, "MS")?.region)
        assertEquals("North Carolina", VenueWeather.bestMatch(raleighes, "NC")?.region)
        assertEquals("Illinois", VenueWeather.bestMatch(raleighes, "il")?.region)
    }

    @Test fun aCountryHintPicksThatCountry() {
        val toulouse = listOf(place("Toulouse", "Occitanie", "France"), place("Toulouse", "Ontario", "Canada"))
        assertEquals("France", VenueWeather.bestMatch(toulouse, "France")?.country)
        assertEquals("Canada", VenueWeather.bestMatch(toulouse, "Canada")?.country)
    }

    @Test fun noHintOrAnUnknownHintFallsBackToTheBestKnownResult() {
        assertEquals("North Carolina", VenueWeather.bestMatch(raleighes, "")?.region)
        assertEquals("North Carolina", VenueWeather.bestMatch(raleighes, "Atlantis")?.region)
        assertNull(VenueWeather.bestMatch(emptyList(), "NC"))
    }

    @Test fun offsetsReadAsUtcLabels() {
        assertEquals("UTC", VenueWeather.utcLabel(0))
        assertEquals("UTC-5", VenueWeather.utcLabel(-5 * 3600))
        assertEquals("UTC+11", VenueWeather.utcLabel(11 * 3600))
        assertEquals("UTC+5:30", VenueWeather.utcLabel(19_800))
        assertEquals("UTC-3:30", VenueWeather.utcLabel(-12_600))
    }

    @Test fun localTimeShiftsByTheVenuesOffsetInEitherClockStyle() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val nineAmUtc = 1_800_000_000_000L - (1_800_000_000_000L % 86_400_000L) + 9 * 3_600_000L
            assertEquals("09:00", VenueWeather.localTime(nineAmUtc, 0, clock24 = true))
            assertEquals("04:00", VenueWeather.localTime(nineAmUtc, -5 * 3600, clock24 = true))
            assertEquals("2:30 PM", VenueWeather.localTime(nineAmUtc, 5 * 3600 + 1800, clock24 = false))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
