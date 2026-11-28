package com.carteltv.tv.data.weather
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** How close the nearest recent lightning is, in the terms the 30-30 safety rule uses. */
enum class Threat(val label: String, val advice: String) {
    CLEAR("All Clear", "No lightning detected nearby in the last 30 minutes."),
    DISTANT("Distant Lightning", "Storms are in the region. Keep an eye on the radar."),
    WATCH("Storm Approaching", "Lightning within 50 km. Be ready to move indoors."),
    WARNING("Take Shelter", "Lightning within 25 km. Head indoors and unplug sensitive gear."),
    DANGER("Danger Close", "Lightning within 10 km. Stay inside; wait 30 minutes after the last strike."),
}

data class StormMotion(val bearingDeg: Int, val speedKmh: Int, val etaMin: Int?)

data class StormReport(
    val threat: Threat,
    val nearestKm: Double?,
    val nearestBearing: Int?,
    val lastStrikeAgoSec: Long?,
    val within: Map<Int, Int>,          // ring km -> strikes in the last 15 min
    val perMinute: Double,              // last 10 min, inside 100 km
    val total60: Int,                   // last hour, everything retained
    val motion: StormMotion?,
)

/** Convective risk derived from the model's CAPE / lifted index / gusts for one hour. */
enum class ConvRisk(val label: String, val level: Int) { NONE("Minimal", 0), LOW("Low", 1), MODERATE("Moderate", 2), HIGH("High", 3), EXTREME("Extreme", 4) }

data class ConvOutlook(val peak: WeatherHour?, val peakRisk: ConvRisk, val maxGustKmh: Double, val maxPop: Int, val totalMm: Double)

object StormTracker {
    val rings = listOf(10, 25, 50, 100)

    fun analyze(all: List<Strike>, lat: Double, lon: Double, nowMs: Long = System.currentTimeMillis()): StormReport {
        val ages = all.filter { nowMs - it.tMs in 0..Lightning.RETAIN_MS }
        val recent = ages.filter { nowMs - it.tMs <= 15 * 60_000L }
        fun d(s: Strike) = StormMath.km(lat, lon, s.lat, s.lon)

        val last30 = ages.filter { nowMs - it.tMs <= 30 * 60_000L }
        val nearest = last30.minByOrNull(::d)
        val nearestKm = nearest?.let(::d)
        val threat = when {
            nearestKm == null -> Threat.CLEAR
            nearestKm <= 10 -> Threat.DANGER
            nearestKm <= 25 -> Threat.WARNING
            nearestKm <= 50 -> Threat.WATCH
            nearestKm <= 150 -> Threat.DISTANT
            else -> Threat.CLEAR
        }
        val within = rings.associateWith { r -> recent.count { d(it) <= r } }
        val tenMin = ages.filter { nowMs - it.tMs <= 10 * 60_000L && d(it) <= 100 }

        return StormReport(
            threat = threat,
            nearestKm = nearestKm,
            nearestBearing = nearest?.let { StormMath.bearing(lat, lon, it.lat, it.lon).roundToInt() },
            lastStrikeAgoSec = ages.maxByOrNull { it.tMs }?.let { ((nowMs - it.tMs) / 1000).coerceAtLeast(0) },
            within = within,
            perMinute = tenMin.size / 10.0,
            total60 = ages.size,
            motion = motion(ages, lat, lon, nowMs),
        )
    }

    /**
     * Cell motion: centroid of strikes within 150 km, newest 10 min vs the 10 min before. Needs a real sample in
     * both windows; the closing speed toward the viewer gives the ETA.
     */
    private fun motion(ages: List<Strike>, lat: Double, lon: Double, nowMs: Long): StormMotion? {
        val near = ages.filter { StormMath.km(lat, lon, it.lat, it.lon) <= 150 }
        val a = near.filter { nowMs - it.tMs <= 10 * 60_000L }
        val b = near.filter { nowMs - it.tMs in (10 * 60_000L + 1)..(20 * 60_000L) }
        if (a.size < 5 || b.size < 5) return null
        val aLat = a.map { it.lat }.average(); val aLon = a.map { it.lon }.average()
        val bLat = b.map { it.lat }.average(); val bLon = b.map { it.lon }.average()
        val dtH = (a.map { it.tMs }.average() - b.map { it.tMs }.average()) / 3_600_000.0
        if (dtH <= 0.02) return null
        val moved = StormMath.km(bLat, bLon, aLat, aLon)
        val speed = moved / dtH
        if (moved < 1.5 || speed > 140) return null
        val bearing = StormMath.bearing(bLat, bLon, aLat, aLon)
        val distNow = StormMath.km(lat, lon, aLat, aLon)
        val distThen = StormMath.km(lat, lon, bLat, bLon)
        val closing = (distThen - distNow) / dtH
        val eta = if (closing > 3 && distNow > 3) (distNow / closing * 60).roundToInt().takeIf { it in 1..240 } else null
        return StormMotion(bearing.roundToInt(), speed.roundToInt(), eta)
    }

    fun risk(h: WeatherHour): ConvRisk {
        var s = when {
            h.capeJ >= 4000 -> 4
            h.capeJ >= 2500 -> 3
            h.capeJ >= 1000 -> 2
            h.capeJ >= 300 -> 1
            else -> 0
        }
        if (s > 0 && h.liftedIndex <= -6) s++ else if (s > 0 && h.liftedIndex >= 0) s--
        if (s > 0 && h.cinJ <= -200) s--          // strong cap: storms struggle to initiate
        if (Sky.of(h.code) == Sky.STORM) s = maxOf(s, 2)
        if (h.gustKmh >= 90) s++
        return ConvRisk.entries[s.coerceIn(0, 4)]
    }

    fun outlook(f: Forecast, hours: Int = 24): ConvOutlook {
        val next = f.hours.take(hours)
        val peak = next.maxByOrNull { risk(it).level * 10_000 + it.capeJ }
        return ConvOutlook(
            peak = peak, peakRisk = peak?.let(::risk) ?: ConvRisk.NONE,
            maxGustKmh = next.maxOfOrNull { it.gustKmh } ?: 0.0, maxPop = next.maxOfOrNull { it.popPct } ?: 0,
            totalMm = next.sumOf { it.precipMm },
        )
    }

    /** Point on the map `km` away at `bearingDeg` — for the motion arrow and range labels. */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, km: Double): Pair<Double, Double> {
        val dLat = km * cos(Math.toRadians(bearingDeg)) / 111.0
        val dLon = km * sin(Math.toRadians(bearingDeg)) / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return (lat + dLat) to (lon + dLon)
    }
}

data class OfficialAlert(val event: String, val severity: String, val headline: String, val area: String, val endsMs: Long?)

/** Official warnings. The US National Weather Service is the only keyless national feed, so it covers the US only. */
object OfficialAlerts {
    private val ua = mapOf("User-Agent" to com.carteltv.tv.data.network.OkHttpTransport.USER_AGENT, "Accept" to "application/geo+json")

    fun covered(lat: Double, lon: Double) = lat in 17.0..72.0 && lon in -180.0..-64.0

    suspend fun fetch(lat: Double, lon: Double): List<OfficialAlert>? = withContext(Dispatchers.IO) {
        if (!covered(lat, lon)) return@withContext null
        suspendRunCatching {
            val txt = HttpSupport.get("https://api.weather.gov/alerts/active?point=%.4f,%.4f".format(java.util.Locale.ROOT, lat, lon), ua)
            val feats = HttpSupport.json.parseToJsonElement(txt).jsonObject["features"] as? JsonArray ?: return@suspendRunCatching emptyList()
            feats.mapNotNull { it.jsonObject["properties"]?.jsonObject }.map { p ->
                fun s(k: String) = (p[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                OfficialAlert(
                    event = s("event"), severity = s("severity"), headline = s("headline"), area = s("areaDesc"),
                    endsMs = s("ends").ifBlank { s("expires") }.let { runCatching { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse(it)?.time }.getOrNull() },
                )
            }.sortedBy { severityRank(it.severity) }
        }.getOrNull()
    }

    private fun severityRank(s: String) = when (s) { "Extreme" -> 0; "Severe" -> 1; "Moderate" -> 2; "Minor" -> 3; else -> 4 }
}
