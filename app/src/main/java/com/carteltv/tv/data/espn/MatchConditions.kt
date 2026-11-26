package com.carteltv.tv.data.espn

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/** The weather at a game's ground: at kick-off for an upcoming game, right now for a live one. */
data class MatchConditions(val tempC: Double, val label: String, val rainChance: Int?, val windKmh: Double?, val code: Int)

/**
 * Weather for a game's venue. The venue's city comes from the scoreboard ("Raleigh, NC"); Open-Meteo's keyless
 * geocoder turns it into a place (using the region to tell Raleigh NC from Raleigh MS), and its forecast gives the
 * conditions. Indoor grounds are skipped, as are finished games and kick-offs beyond the 7-day forecast.
 */
object MatchConditionsSource {

    data class GeoResult(
        val name: String, val admin1: String?, val country: String?, val countryCode: String?, val lat: Double, val lon: Double,
        /** The place's own IANA zone ("America/New_York"), which is what the ground's local time runs on. */
        val timezone: String? = null,
    )

    private data class Cached(val atMs: Long, val value: MatchConditions?)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val TTL_MS = 10 * 60_000L

    /** Geocodes the venue and returns kick-off (or current) conditions; indoor/finished games skip the request. */
    suspend fun load(game: EspnScoreboardClient.Score, nowMs: Long = System.currentTimeMillis()): MatchConditions? = withContext(Dispatchers.IO) {
        if (!wanted(game, nowMs)) return@withContext null
        val live = game.state == "in"
        val targetMs = if (live) nowMs else game.startMs
        val key = "${game.leaguePath}|${game.name}|${game.location}|${if (live) 0 else targetMs / 300_000}"
        cache[key]?.takeIf { nowMs - it.atMs < TTL_MS }?.let { return@withContext it.value }
        val result = suspendRunCatching {
            val (city, _) = splitLocation(game.location.orEmpty())
            if (city.isBlank()) return@suspendRunCatching null
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name=" +
                URLEncoder.encode(city, "UTF-8")
            val place = pickPlace(parseGeo(HttpSupport.get(geoUrl, connectMs = 6_000, readMs = 10_000)), game.location.orEmpty())
                ?: return@suspendRunCatching null
            val forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,weather_code,wind_speed_10m" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,wind_speed_10m" +
                "&timezone=UTC&forecast_days=8"
            parseForecast(HttpSupport.get(forecastUrl, connectMs = 8_000, readMs = 15_000), targetMs, live)
        }.getOrNull()
        cache[key] = Cached(nowMs, result)
        result
    }

    private val US_STATES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California", "CO" to "Colorado",
        "CT" to "Connecticut", "DE" to "Delaware", "DC" to "District of Columbia", "FL" to "Florida", "GA" to "Georgia",
        "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
        "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland", "MA" to "Massachusetts",
        "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi", "MO" to "Missouri", "MT" to "Montana",
        "NE" to "Nebraska", "NV" to "Nevada", "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico",
        "NY" to "New York", "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma",
        "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina", "SD" to "South Dakota",
        "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington",
        "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
    )
    private val CA_PROVINCES = mapOf(
        "AB" to "Alberta", "BC" to "British Columbia", "MB" to "Manitoba", "NB" to "New Brunswick", "NL" to "Newfoundland and Labrador",
        "NS" to "Nova Scotia", "ON" to "Ontario", "PE" to "Prince Edward Island", "QC" to "Quebec", "SK" to "Saskatchewan",
    )
    private val AU_STATES = mapOf(
        "NSW" to "New South Wales", "VIC" to "Victoria", "QLD" to "Queensland", "WA" to "Western Australia",
        "SA" to "South Australia", "TAS" to "Tasmania", "ACT" to "Australian Capital Territory", "NT" to "Northern Territory",
    )
    private val COUNTRY_CODES = mapOf(
        "USA" to "US", "UNITED STATES" to "US", "CANADA" to "CA", "AUSTRALIA" to "AU", "NEW ZEALAND" to "NZ",
        "ENGLAND" to "GB", "SCOTLAND" to "GB", "WALES" to "GB", "NORTHERN IRELAND" to "GB", "UK" to "GB",
        "UNITED KINGDOM" to "GB", "IRELAND" to "IE", "SOUTH AFRICA" to "ZA", "FRANCE" to "FR", "GERMANY" to "DE",
        "ITALY" to "IT", "SPAIN" to "ES", "JAPAN" to "JP", "MEXICO" to "MX", "BRAZIL" to "BR",
    )

    /** Splits "Raleigh, NC" into the city to search for and the region to check results against. */
    fun splitLocation(location: String): Pair<String, String?> {
        val parts = location.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return (parts.firstOrNull() ?: "") to parts.getOrNull(1)
    }

    private fun expandRegion(region: String): String =
        (US_STATES + CA_PROVINCES + AU_STATES)[region.uppercase()] ?: region

    /**
     * The geocoder result that is really the venue's city. With a region (state, province, country) a result must
     * match it; with none, the first (most prominent) result is taken. Returns null rather than guessing wrong.
     */
    fun pickPlace(results: List<GeoResult>, location: String): GeoResult? {
        val (city, region) = splitLocation(location)
        val sameName = results.filter { it.name.equals(city, ignoreCase = true) }.ifEmpty { results }
        if (region == null) return sameName.firstOrNull()
        val wantedRegion = expandRegion(region)
        val wantedCountry = COUNTRY_CODES[region.uppercase()]
        fun score(r: GeoResult): Int =
            (if (r.admin1.equals(wantedRegion, ignoreCase = true)) 4 else 0) +
                (if (wantedCountry != null && r.countryCode.equals(wantedCountry, ignoreCase = true)) 3 else 0) +
                (if (r.country.equals(wantedRegion, ignoreCase = true) || r.countryCode.equals(region, ignoreCase = true)) 2 else 0)
        return sameName.maxByOrNull { score(it) }?.takeIf { score(it) > 0 }
    }

    fun parseGeo(json: String): List<GeoResult> = runCatching {
        (HttpSupport.lenientJson.parseToJsonElement(json).jsonObject["results"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            GeoResult(
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                admin1 = o["admin1"]?.jsonPrimitive?.contentOrNull,
                country = o["country"]?.jsonPrimitive?.contentOrNull,
                countryCode = o["country_code"]?.jsonPrimitive?.contentOrNull,
                lat = o["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                lon = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                timezone = o["timezone"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }.getOrDefault(emptyList())

    /** WMO weather code to a plain word, matching the labels the rest of the app uses. */
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        in 45..48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67, in 80..82 -> "Rain"
        in 71..77, in 85..86 -> "Snow"
        in 95..99 -> "Storm"
        else -> "—"
    }

    private fun hourMs(iso: String): Long? =
        runCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()

    /** Index of the hourly slot nearest [targetMs], or null if none is within 90 minutes. */
    fun nearestHour(times: List<String>, targetMs: Long): Int? {
        val best = times.indices.minByOrNull { kotlin.math.abs((hourMs(times[it]) ?: Long.MAX_VALUE / 2) - targetMs) } ?: return null
        val gap = kotlin.math.abs((hourMs(times[best]) ?: return null) - targetMs)
        return best.takeIf { gap <= 90 * 60_000L }
    }

    /**
     * Conditions from an Open-Meteo forecast fetched with `timezone=UTC`: the hour of [targetMs] when [live] is false,
     * the current block otherwise. Null if the forecast does not reach that time.
     */
    fun parseForecast(json: String, targetMs: Long, live: Boolean): MatchConditions? = runCatching {
        val root = HttpSupport.lenientJson.parseToJsonElement(json).jsonObject
        val hourly = root["hourly"]?.jsonObject ?: return null
        val times = hourly["time"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return null
        fun at(key: String, i: Int) = (hourly[key] as? JsonArray)?.getOrNull(i)?.jsonPrimitive
        val i = nearestHour(times, targetMs)
        if (live) {
            val cur = root["current"]?.jsonObject ?: return null
            val code = cur["weather_code"]?.jsonPrimitive?.intOrNull ?: return null
            MatchConditions(
                tempC = cur["temperature_2m"]?.jsonPrimitive?.doubleOrNull ?: return null, label = describe(code),
                rainChance = i?.let { at("precipitation_probability", it)?.intOrNull },
                windKmh = cur["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull, code = code,
            )
        } else {
            val idx = i ?: return null
            val code = at("weather_code", idx)?.intOrNull ?: return null
            MatchConditions(
                tempC = at("temperature_2m", idx)?.doubleOrNull ?: return null, label = describe(code),
                rainChance = at("precipitation_probability", idx)?.intOrNull,
                windKmh = at("wind_speed_10m", idx)?.doubleOrNull, code = code,
            )
        }
    }.getOrNull()

    /** "18° Overcast · 40% rain · 15 km/h", with the caller's unit formatting. */
    fun summary(w: MatchConditions, temp: (Double) -> String, wind: (Double) -> String): String =
        listOfNotNull(
            "${temp(w.tempC)} ${w.label}",
            w.rainChance?.takeIf { it >= 10 }?.let { "$it% rain" },
            w.windKmh?.takeIf { it >= 20 }?.let { wind(it) },
        ).joinToString(" · ")

    /** Whether a lookup is worth making at all for this game. */
    fun wanted(g: EspnScoreboardClient.Score, nowMs: Long): Boolean = when {
        g.indoor == true -> false
        g.state == "post" -> false
        g.location.isNullOrBlank() -> false
        g.state == "in" -> true
        g.startMs <= 0L -> false
        else -> g.startMs - nowMs in 0..(7L * 24 * 3_600_000L - 3_600_000L)
    }
}
