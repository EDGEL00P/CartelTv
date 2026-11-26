package com.carteltv.tv.data.weather
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** A real measurement from the nearest reporting airport (METAR), not a model grid cell. */
data class StationObs(
    val icao: String, val name: String, val distanceKm: Double, val ageMin: Int,
    val tempC: Double, val dewC: Double?, val windDir: Int?, val windKmh: Double?, val gustKmh: Double?,
    val pressureHpa: Double?, val visibilityM: Double?, val cloudPct: Int?,
)

/**
 * Nearest METAR from aviationweather.gov (free, global). Only used when the station is close and the report is
 * fresh; METAR temperatures are whole degrees, so this corrects bias rather than adding decimals.
 */
object WeatherObservations {
    const val MAX_KM = 60.0
    const val MAX_AGE_MIN = 80
    private const val KT_TO_KMH = 1.852
    private const val TTL_MS = 5 * 60_000L
    private data class Hit(val atMs: Long, val obs: StationObs?)
    private val cache = ConcurrentHashMap<String, Hit>()

    suspend fun nearest(lat: Double, lon: Double, force: Boolean = false): StationObs? {
        val key = "%.1f,%.1f".format(java.util.Locale.ROOT, lat, lon)
        val now = System.currentTimeMillis()
        if (!force) cache[key]?.takeIf { now - it.atMs < TTL_MS }?.let { return it.obs }
        val obs = withContext(Dispatchers.IO) { suspendRunCatching { fetch(lat, lon, now) }.getOrNull() }
        cache[key] = Hit(now, obs)
        return obs
    }

    private suspend fun fetch(lat: Double, lon: Double, nowMs: Long): StationObs? {
        for (box in listOf(0.5, 1.0)) {
            // The API rejects the bbox (400) when coordinates carry more than a couple of decimals.
            val bbox = "%.2f,%.2f,%.2f,%.2f".format(java.util.Locale.US, lat - box, lon - box, lat + box, lon + box)
            val url = "https://aviationweather.gov/api/data/metar?format=json&bbox=$bbox"
            val body = HttpSupport.get(url, mapOf("User-Agent" to com.carteltv.tv.data.network.OkHttpTransport.USER_AGENT)).takeIf { it.isNotBlank() } ?: continue
            val rows = HttpSupport.json.parseToJsonElement(body) as? JsonArray ?: continue
            val best = rows.map { it.jsonObject }.mapNotNull { o ->
                val sLat = o.num("lat") ?: return@mapNotNull null
                val sLon = o.num("lon") ?: return@mapNotNull null
                val obsSec = o.num("obsTime")?.toLong() ?: return@mapNotNull null
                val age = ((nowMs / 1000 - obsSec) / 60).toInt()
                val temp = o.num("temp") ?: return@mapNotNull null
                if (age !in 0..MAX_AGE_MIN) return@mapNotNull null
                val km = StormMath.km(lat, lon, sLat, sLon)
                if (km > MAX_KM) return@mapNotNull null
                km to StationObs(
                    icao = o.str("icaoId"), name = o.str("name").substringBefore(',').ifBlank { o.str("icaoId") },
                    distanceKm = km, ageMin = age, tempC = temp, dewC = o.num("dewp"),
                    windDir = o.num("wdir")?.roundToInt(), windKmh = o.num("wspd")?.times(KT_TO_KMH),
                    gustKmh = o.num("wgst")?.times(KT_TO_KMH),
                    pressureHpa = o.num("altim")?.let { if (it < 100) it * 33.8639 else it },
                    visibilityM = visibilityM(o["visib"]), cloudPct = cloudPct(o["clouds"]),
                )
            }.minByOrNull { it.first }?.second
            if (best != null) return best
        }
        return null
    }

    /** "6+" is METAR's cap of six statute miles or better, which is how an airport reports "10 km or more". */
    private fun visibilityM(e: JsonElement?): Double? {
        val s = (e as? JsonPrimitive)?.content ?: return null
        return (s.removeSuffix("+").toDoubleOrNull() ?: return null) * 1609.34
    }

    private fun cloudPct(e: JsonElement?): Int? {
        val layers = (e as? JsonArray) ?: return null
        if (layers.isEmpty()) return 0
        return layers.maxOf {
            when (it.jsonObject.str("cover")) { "FEW" -> 25; "SCT" -> 50; "BKN" -> 75; "OVC", "OVX" -> 100; else -> 0 }
        }
    }

    private fun humidity(t: Double, td: Double): Int =
        (100 * exp(17.625 * td / (243.04 + td)) / exp(17.625 * t / (243.04 + t))).roundToInt().coerceIn(1, 100)

    /** Overlay the measurement onto the model "now". Sky, UV and rain stay model-based; measured fields win. */
    fun blend(model: WeatherNow, o: StationObs): WeatherNow {
        // A report more than ten degrees off the model is a bad report or the wrong station: ignore it.
        if (abs(o.tempC - model.tempC) > 10) return model
        return model.copy(
            tempC = o.tempC,
            feelsC = model.feelsC + (o.tempC - model.tempC),
            dewC = o.dewC ?: model.dewC,
            humidity = o.dewC?.let { humidity(o.tempC, it) } ?: model.humidity,
            windKmh = o.windKmh ?: model.windKmh,
            gustKmh = o.gustKmh ?: maxOf(model.gustKmh, o.windKmh ?: 0.0),
            windDir = o.windDir ?: model.windDir,
            pressureHpa = o.pressureHpa ?: model.pressureHpa,
            visibilityM = o.visibilityM?.let { if (it >= 9_000) maxOf(it, model.visibilityM) else it } ?: model.visibilityM,
            cloud = o.cloudPct ?: model.cloud,
        )
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
}
