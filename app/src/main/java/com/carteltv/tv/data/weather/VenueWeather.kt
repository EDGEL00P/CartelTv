package com.carteltv.tv.data.weather

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Conditions at a game's venue, for the scorebug: the venue's city comes from the scoreboard feed
 * ("Raleigh, NC"), is geocoded, and the forecast service supplies the weather and the local UTC offset.
 */
object VenueWeather {
    data class Conditions(val tempC: Double, val sky: Sky, val utcOffsetSec: Int)

    private const val TTL_MS = 30 * 60_000L
    private val cache = ConcurrentHashMap<String, Pair<Long, Conditions>>()

    /** Null when the venue cannot be placed or the forecast is unavailable; failures are not cached. */
    suspend fun load(location: String): Conditions? {
        val key = location.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return null
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.let { return it.second }
        val city = location.substringBefore(',').trim()
        val hint = location.substringAfter(',', "").trim()
        val place = bestMatch(WeatherService.search(city), hint) ?: return null
        val forecast = WeatherService.forecast(place.lat, place.lon) ?: return null
        return Conditions(forecast.now.tempC, forecast.now.sky, forecast.utcOffsetSec).also {
            cache[key] = System.currentTimeMillis() to it
        }
    }

    /**
     * The geocoding result the scoreboard's "City, ST" or "City, Country" most likely means. Results
     * arrive best-known first, so with no usable hint the first one wins.
     */
    internal fun bestMatch(places: List<Place>, hint: String): Place? {
        val h = hint.trim()
        if (h.isEmpty()) return places.firstOrNull()
        val fromState = US_STATES.entries.firstOrNull { it.value.equals(h, ignoreCase = true) }?.key
        return places.firstOrNull {
            it.region.equals(h, ignoreCase = true) || it.country.equals(h, ignoreCase = true) ||
                (fromState != null && it.region.equals(fromState, ignoreCase = true) && it.country.contains("United States", ignoreCase = true))
        } ?: places.firstOrNull()
    }

    /** "UTC-5", "UTC+5:30", or "UTC" for an offset in seconds. */
    fun utcLabel(offsetSec: Int): String {
        if (offsetSec == 0) return "UTC"
        val sign = if (offsetSec < 0) "-" else "+"
        val minutes = kotlin.math.abs(offsetSec) / 60
        val h = minutes / 60
        val m = minutes % 60
        return "UTC$sign$h" + if (m == 0) "" else ":%02d".format(m)
    }

    /** The wall-clock time at the venue, in the device's 12/24-hour style. */
    fun localTime(nowMs: Long, offsetSec: Int, clock24: Boolean): String {
        val pattern = if (clock24) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .format(Instant.ofEpochMilli(nowMs).atOffset(ZoneOffset.ofTotalSeconds(offsetSec)))
    }

    private val US_STATES = mapOf(
        "Alabama" to "AL", "Alaska" to "AK", "Arizona" to "AZ", "Arkansas" to "AR", "California" to "CA",
        "Colorado" to "CO", "Connecticut" to "CT", "Delaware" to "DE", "District of Columbia" to "DC",
        "Florida" to "FL", "Georgia" to "GA", "Hawaii" to "HI", "Idaho" to "ID", "Illinois" to "IL",
        "Indiana" to "IN", "Iowa" to "IA", "Kansas" to "KS", "Kentucky" to "KY", "Louisiana" to "LA",
        "Maine" to "ME", "Maryland" to "MD", "Massachusetts" to "MA", "Michigan" to "MI", "Minnesota" to "MN",
        "Mississippi" to "MS", "Missouri" to "MO", "Montana" to "MT", "Nebraska" to "NE", "Nevada" to "NV",
        "New Hampshire" to "NH", "New Jersey" to "NJ", "New Mexico" to "NM", "New York" to "NY",
        "North Carolina" to "NC", "North Dakota" to "ND", "Ohio" to "OH", "Oklahoma" to "OK", "Oregon" to "OR",
        "Pennsylvania" to "PA", "Rhode Island" to "RI", "South Carolina" to "SC", "South Dakota" to "SD",
        "Tennessee" to "TN", "Texas" to "TX", "Utah" to "UT", "Vermont" to "VT", "Virginia" to "VA",
        "Washington" to "WA", "West Virginia" to "WV", "Wisconsin" to "WI", "Wyoming" to "WY",
    )
}
