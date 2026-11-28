package com.carteltv.tv.data.weather
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class MarineNow(
    val waveM: Double, val waveDir: Int, val wavePeriodS: Double,
    val swellM: Double, val swellDir: Int, val swellPeriodS: Double,
    val windWaveM: Double, val seaTempC: Double?,
)

/** A tide turning point. Minutes count from local midnight today, so 1440+ is tomorrow. */
data class TideEvent(val minute: Int, val levelM: Double, val high: Boolean)

data class MarineWeather(
    val now: MarineNow,
    val nowMinute: Int,
    val levels: List<Double>,              // hourly sea level (m, MSL) from local midnight today
    val waves: List<Double>,               // hourly significant wave height (m), same axis
    val tides: List<TideEvent>,
    val fetchedAtMs: Long,
    /** Set when real station predictions replaced the ocean-model tide. */
    val tideStation: String? = null,
    val tideStationKm: Double = 0.0,
) {
    val tideDatum: String get() = if (tideStation != null) "MLLW" else "MSL"
    val rising: Boolean get() = levels.getOrNull(nowMinute / 60 + 1)?.let { it > (levels.getOrNull(nowMinute / 60) ?: it) } ?: true
    fun nextTide(high: Boolean) = tides.firstOrNull { it.high == high && it.minute > nowMinute }
    val rangeM: Double get() = (levels.maxOrNull() ?: 0.0) - (levels.minOrNull() ?: 0.0)

    /** WMO/Douglas sea state, from significant wave height. */
    val seaState: String get() = when {
        now.waveM < 0.1 -> "Calm"
        now.waveM < 0.5 -> "Calm, Rippled"
        now.waveM < 1.25 -> "Smooth"
        now.waveM < 2.5 -> "Slight"
        now.waveM < 4.0 -> "Moderate"
        now.waveM < 6.0 -> "Rough"
        now.waveM < 9.0 -> "Very Rough"
        else -> "High"
    }
}

/** Open-Meteo MarineWeather: waves, swell, sea temperature and a tide-inclusive sea level. Open water only. */
object MarineService {
    private const val TTL_MS = 30 * 60_000L
    private val cache = ConcurrentHashMap<String, MarineWeather>()

    fun cached(lat: Double, lon: Double): MarineWeather? = cache[key(lat, lon)]
    private fun key(lat: Double, lon: Double) = "%.2f,%.2f".format(java.util.Locale.ROOT, lat, lon)

    /** Null when the point is inland / not covered by the ocean model, or the service is unreachable with no cache. */
    suspend fun get(lat: Double, lon: Double, force: Boolean = false): MarineWeather? {
        val k = key(lat, lon)
        val now = System.currentTimeMillis()
        if (!force) cache[k]?.takeIf { now - it.fetchedAtMs < TTL_MS }?.let { return it }
        val fresh = withContext(Dispatchers.IO) { suspendRunCatching { fetch(lat, lon) }.getOrNull() }
        if (fresh != null) cache[k] = fresh
        return fresh ?: cache[k]
    }

    private suspend fun fetch(lat: Double, lon: Double): MarineWeather? {
        val url = "https://marine-api.open-meteo.com/v1/marine?latitude=$lat&longitude=$lon" +
            "&current=wave_height,wave_direction,wave_period,swell_wave_height,swell_wave_direction,swell_wave_period," +
            "wind_wave_height,sea_surface_temperature,sea_level_height_msl" +
            "&hourly=sea_level_height_msl,wave_height&timezone=auto&forecast_days=3"
        val root = HttpSupport.json.parseToJsonElement(HttpSupport.get(url)).jsonObject
        val c = root["current"]?.jsonObject ?: return null
        if (c.numOrNull("wave_height") == null) return null

        val h = root["hourly"]?.jsonObject ?: return null
        val levels = h.arr("sea_level_height_msl").map { it.numOrNull() ?: Double.NaN }
        val waves = h.arr("wave_height").map { it.numOrNull() ?: 0.0 }
        if (levels.count { !it.isNaN() } < 24) return null
        val clean = fillGaps(levels)

        val timeStr = (c["time"] as JsonPrimitive).content
        val nowMin = timeStr.substring(11, 13).toInt() * 60 + timeStr.substring(14, 16).toInt()
        val noaa = suspendRunCatching { NoaaTides.forLocation(lat, lon, timeStr.take(10)) }.getOrNull()
        return MarineWeather(
            now = MarineNow(
                waveM = c.num("wave_height"), waveDir = c.num("wave_direction").roundToInt(), wavePeriodS = c.num("wave_period"),
                swellM = c.num("swell_wave_height"), swellDir = c.num("swell_wave_direction").roundToInt(), swellPeriodS = c.num("swell_wave_period"),
                windWaveM = c.num("wind_wave_height"), seaTempC = c.numOrNull("sea_surface_temperature"),
            ),
            nowMinute = nowMin, levels = noaa?.levels ?: clean, waves = waves, tides = noaa?.events ?: turningPoints(clean),
            fetchedAtMs = System.currentTimeMillis(), tideStation = noaa?.stationName, tideStationKm = noaa?.distanceKm ?: 0.0,
        )
    }

    private fun fillGaps(v: List<Double>): List<Double> {
        val out = v.toMutableList()
        for (i in out.indices) if (out[i].isNaN()) out[i] = out.getOrNull(i - 1)?.takeIf { !it.isNaN() } ?: out.firstOrNull { !it.isNaN() } ?: 0.0
        return out
    }

    /** Local maxima / minima of the hourly curve, refined to the minute with a parabola through the three samples. */
    internal fun turningPoints(y: List<Double>): List<TideEvent> {
        val out = ArrayList<TideEvent>()
        for (i in 1 until y.lastIndex) {
            val a = y[i - 1]; val b = y[i]; val c = y[i + 1]
            val high = b > a && b >= c
            val low = b < a && b <= c
            if (!high && !low) continue
            val denom = a - 2 * b + c
            val off = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
            val peak = b - 0.25 * (a - c) * off
            out += TideEvent(((i + off) * 60).roundToInt(), peak, high)
        }
        return out
    }
}

private fun JsonObject.num(k: String): Double = numOrNull(k) ?: 0.0
private fun JsonObject.numOrNull(k: String): Double? = this[k].numOrNull()
private fun JsonElement?.numOrNull(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
private fun JsonObject.arr(k: String): JsonArray = this[k]?.jsonArray ?: JsonArray(emptyList())
