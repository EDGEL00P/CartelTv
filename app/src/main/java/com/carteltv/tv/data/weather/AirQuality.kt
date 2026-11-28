package com.carteltv.tv.data.weather

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/* ═══════════════════════════════════════════════════════════
   AIR QUALITY
   ─────────────────────────────────────────────────────────────
   The air outside right now, from the same keyless service as the
   weather (Open-Meteo's air-quality feed). It is read by the US
   Air Quality Index, the 0 to 500 scale most weather apps show,
   with the fine dust behind it for anyone who wants the number.
   ═══════════════════════════════════════════════════════════ */

/** The air's bands on the US index, with EPA's category descriptors (AQI Technical Assistance Document, Table 1). */
enum class AirLevel(val label: String, val range: IntRange) {
    GOOD("Good", 0..50),
    MODERATE("Moderate", 51..100),
    SENSITIVE("Unhealthy for Sensitive Groups", 101..150),
    UNHEALTHY("Unhealthy", 151..200),
    VERY_UNHEALTHY("Very Unhealthy", 201..300),
    HAZARDOUS("Hazardous", 301..500),
    ;

    companion object {
        /** The band of an index value; anything past the top of the scale is as hazardous as it gets. */
        fun of(aqi: Int): AirLevel = entries.firstOrNull { aqi in it.range } ?: if (aqi < 0) GOOD else HAZARDOUS
    }
}

/** The pollutants the US AQI is built from, each with the feed's sub-index key and its reporting name. */
enum class Pollutant(val feedKey: String, val label: String) {
    PM25("us_aqi_pm2_5", "PM2.5"),
    PM10("us_aqi_pm10", "PM10"),
    OZONE("us_aqi_ozone", "Ozone"),
    NO2("us_aqi_nitrogen_dioxide", "NO₂"),
    SO2("us_aqi_sulphur_dioxide", "SO₂"),
    CO("us_aqi_carbon_monoxide", "CO"),
}

/**
 * The air at one place. [updatedAt] is the hour, in that place's own clock, the reading is for.
 * [subIndices] holds each pollutant's own US AQI; the overall index is the highest of them.
 */
data class AirQuality(
    val usAqi: Int,
    val europeanAqi: Int?,
    val pm25: Double?,
    val pm10: Double?,
    val updatedAt: String,
    val subIndices: Map<Pollutant, Int> = emptyMap(),
) {
    val level: AirLevel get() = AirLevel.of(usAqi)

    /** EPA's "main pollutant": the one with the highest sub-index; the first listed wins a tie. */
    val mainPollutant: Pollutant? get() = subIndices.maxByOrNull { it.value }?.key

    /** The main pollutant's concentration in µg/m³, where the feed reports it (the two particle sizes). */
    val mainConcentration: Double? get() = when (mainPollutant) {
        Pollutant.PM25 -> pm25
        Pollutant.PM10 -> pm10
        else -> null
    }
}

object AirQualityService {
    private const val TTL_MS = 30 * 60_000L

    private data class Entry(val atMs: Long, val value: AirQuality)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** The air at a place, re-read at most every half hour; null until the service answers. */
    suspend fun current(lat: Double, lon: Double): AirQuality? {
        val key = "%.2f,%.2f".format(Locale.ROOT, lat, lon)
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.atMs < TTL_MS }?.let { return it.value }
        val fresh = withContext(Dispatchers.IO) {
            suspendRunCatching {
                val url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon" +
                    "&current=us_aqi,european_aqi,pm2_5,pm10,${Pollutant.entries.joinToString(",") { it.feedKey }}&timezone=auto"
                parse(HttpSupport.get(url, connectMs = 8_000, readMs = 12_000))
            }.getOrNull()
        }
        if (fresh != null) cache[key] = Entry(now, fresh)
        return fresh
    }

    /** The reading in [body], or null when the feed has no index for the place. */
    internal fun parse(body: String): AirQuality? {
        val current = HttpSupport.json.parseToJsonElement(body).jsonObject["current"] as? JsonObject ?: return null
        val usAqi = current["us_aqi"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        fun real(key: String): Double? = current[key]?.jsonPrimitive?.content?.toDoubleOrNull()
        return AirQuality(
            usAqi = usAqi,
            europeanAqi = current["european_aqi"]?.jsonPrimitive?.content?.toIntOrNull(),
            pm25 = real("pm2_5"),
            pm10 = real("pm10"),
            updatedAt = current["time"]?.jsonPrimitive?.content.orEmpty(),
            subIndices = Pollutant.entries
                .mapNotNull { p -> current[p.feedKey]?.jsonPrimitive?.content?.toIntOrNull()?.let { p to it } }
                .toMap(),
        )
    }
}
