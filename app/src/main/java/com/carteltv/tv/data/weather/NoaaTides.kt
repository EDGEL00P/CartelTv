package com.carteltv.tv.data.weather
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.PI
import kotlin.math.cos

data class TideSeries(val levels: List<Double>, val events: List<TideEvent>, val stationName: String, val distanceKm: Double)

/**
 * Real harmonic tide predictions from NOAA CO-OPS for US coasts (keyless). They replace the coarse ocean-model
 * tide wherever a prediction station is within [MAX_KM]. Levels are metres above MLLW.
 */
object NoaaTides {
    private const val MAX_KM = 100.0
    private val compactDate = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    }
    private data class Station(val id: String, val name: String, val lat: Double, val lon: Double)
    @Volatile private var stations: List<Station>? = null

    fun covered(lat: Double, lon: Double) = lat in 17.0..72.0 && lon in -180.0..-64.0

    private suspend fun stationList(): List<Station> = stations ?: withContext(Dispatchers.IO) {
        val txt = HttpSupport.get("https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi/stations.json?type=tidepredictions", readMs = 30_000)
        val arr = HttpSupport.json.parseToJsonElement(txt).jsonObject["stations"] as JsonArray
        arr.map { it.jsonObject }.mapNotNull { o ->
            val lat = (o["lat"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = (o["lng"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
            Station((o["id"] as JsonPrimitive).content, (o["name"] as JsonPrimitive).content, lat, lon)
        }
    }.also { stations = it }

    /** [today] is the location's local date (yyyy-MM-dd); minutes in the result count from its midnight. */
    suspend fun forLocation(lat: Double, lon: Double, today: String): TideSeries? = withContext(Dispatchers.IO) {
        if (!covered(lat, lon)) return@withContext null
        val (st, km) = stationList().map { it to StormMath.km(lat, lon, it.lat, it.lon) }.minByOrNull { it.second } ?: return@withContext null
        if (km > MAX_KM) return@withContext null

        val todayMs = Forecast.dateToUtcMs(today)
        fun compact(ms: Long) = compactDate.get().format(java.util.Date(ms))
        val url = "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?product=predictions&application=CartelTV" +
            "&begin_date=${compact(todayMs - 86_400_000L)}&end_date=${compact(todayMs + 4 * 86_400_000L)}" +
            "&datum=MLLW&station=${st.id}&time_zone=lst_ldt&interval=hilo&units=metric&format=json"
        val preds = HttpSupport.json.parseToJsonElement(HttpSupport.get(url)).jsonObject["predictions"]?.jsonArray ?: return@withContext null
        val events = preds.mapNotNull { p ->
            val o = p.jsonObject
            val t = (o["t"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val v = (o["v"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val day = (Forecast.dateToUtcMs(t.take(10)) - todayMs) / 60_000L
            TideEvent((day + t.substring(11, 13).toInt() * 60 + t.substring(14, 16).toInt()).toInt(), v, (o["type"] as? JsonPrimitive)?.content == "H")
        }.sortedBy { it.minute }
        val levels = hourly(events) ?: return@withContext null
        TideSeries(levels, events.filter { it.minute in 0..72 * 60 }, st.name, km)
    }

    /** Hourly curve through the turning points: tides follow a half-cosine between a high and the next low. */
    internal fun hourly(events: List<TideEvent>, hours: Int = 72): List<Double>? {
        if (events.size < 4) return null
        val out = ArrayList<Double>(hours)
        for (h in 0 until hours) {
            val m = h * 60
            val i = events.indexOfLast { it.minute <= m }
            val a = events.getOrNull(i) ?: return null
            val b = events.getOrNull(i + 1) ?: return null
            val f = (m - a.minute).toDouble() / (b.minute - a.minute)
            out += a.levelM + (b.levelM - a.levelM) * (1 - cos(PI * f)) / 2
        }
        return out
    }
}

/** Reverse geocode for device/coordinate fixes (BigDataCloud client endpoint, keyless). */
internal suspend fun reverseGeocode(lat: Double, lon: Double): Place = withContext(Dispatchers.IO) {
    val fallback = Place("%.4f, %.4f".format(lat, lon), "", "", lat, lon)
    suspendRunCatching {
        val o = HttpSupport.json.parseToJsonElement(
            HttpSupport.get("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=en", connectMs = 6_000, readMs = 8_000),
        ).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val name = s("locality").ifBlank { s("city") }
        if (name.isBlank()) fallback else Place(name, s("principalSubdivision"), s("countryName"), lat, lon)
    }.getOrDefault(fallback)
}
