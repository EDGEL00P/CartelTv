package com.carteltv.tv.data.weather
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** A named spot on the map. Persisted by [AppPreferences.weatherPlaces] and mirrored into the legacy city/lat/lon prefs. */
data class Place(
    val name: String,
    val region: String,
    val country: String,
    val lat: Double,
    val lon: Double,
) {
    val label: String get() =
        listOf(name, region.takeIf { it.isNotBlank() && it != name }, country.takeIf { it.isNotBlank() })
            .filterNotNull()
            .joinToString(", ")
    val shortLabel: String get() = if (region.isNotBlank() && region != name) "$name, $region" else name

    fun sameSpot(o: Place) = kotlin.math.abs(lat - o.lat) < 0.02 && kotlin.math.abs(lon - o.lon) < 0.02

    fun encode(): String = listOf(name, region, country).joinToString("|") { it.replace('|', ' ').replace('\n', ' ') } + "|$lat|$lon"

    companion object {
        fun decode(line: String): Place? {
            val p = line.split("|")
            if (p.size < 5) return null
            val lat = p[3].toDoubleOrNull() ?: return null
            val lon = p[4].toDoubleOrNull() ?: return null
            return Place(p[0], p[1], p[2], lat, lon)
        }

        fun current() = Place(AppPreferences.cityName, "", "", AppPreferences.weatherLat, AppPreferences.weatherLon)
    }
}

enum class Sky(
    val label: String,
) {
    CLEAR("Clear"),
    PARTLY("Partly Cloudy"),
    CLOUDY("Overcast"),
    FOG("Fog"),
    DRIZZLE("Drizzle"),
    RAIN("Rain"),
    SNOW("Snow"),
    STORM("Thunderstorm"),
    ;

    val wet: Boolean get() = this == DRIZZLE || this == RAIN || this == STORM || this == SNOW

    companion object {
        fun of(code: Int): Sky =
            when (code) {
                0, 1 -> CLEAR
                2 -> PARTLY
                45, 48 -> FOG
                in 51..57 -> DRIZZLE
                in 61..67, in 80..82 -> RAIN
                in 71..77, 85, 86 -> SNOW
                in 95..99 -> STORM
                else -> CLOUDY
            }

        fun describe(code: Int): String =
            when (code) {
                0 -> "Clear Sky"
                1 -> "Mainly Clear"
                2 -> "Partly Cloudy"
                3 -> "Overcast"
                45 -> "Fog"
                48 -> "Depositing Rime Fog"
                51 -> "Light Drizzle"
                53 -> "Moderate Drizzle"
                55 -> "Dense Drizzle"
                56, 57 -> "Freezing Drizzle"
                61 -> "Slight Rain"
                63 -> "Moderate Rain"
                65 -> "Heavy Rain"
                66, 67 -> "Freezing Rain"
                71 -> "Slight Snowfall"
                73 -> "Moderate Snowfall"
                75 -> "Heavy Snowfall"
                77 -> "Snow Grains"
                80 -> "Slight Rain Showers"
                81 -> "Moderate Rain Showers"
                82 -> "Violent Rain Showers"
                85 -> "Slight Snow Showers"
                86 -> "Heavy Snow Showers"
                95 -> "Thunderstorm"
                96 -> "Thunderstorm with Slight Hail"
                97 -> "Heavy Thunderstorm"
                99 -> "Thunderstorm with Heavy Hail"
                else -> "—"
            }
    }
}

/** Metric values straight from the API. Conversion happens at display time via [WeatherUnits]. */
data class WeatherNow(
    val time: String,
    val tempC: Double,
    val feelsC: Double,
    val humidity: Int,
    val dewC: Double,
    val pressureHpa: Double,
    val windKmh: Double,
    val gustKmh: Double,
    val windDir: Int,
    val cloud: Int,
    val precipMm: Double,
    val visibilityM: Double,
    val uv: Double,
    val code: Int,
    val isDay: Boolean,
) {
    val sky: Sky get() = Sky.of(code)
}

data class WeatherHour(
    val time: String,
    val tempC: Double,
    val popPct: Int,
    val code: Int,
    val isDay: Boolean,
    val capeJ: Double,
    val liftedIndex: Double,
    val cinJ: Double,
    val gustKmh: Double,
    val precipMm: Double,
    val freezingLevelM: Double,
)

enum class Confidence(
    val label: String,
) {
    HIGH("Models agree"),
    MEDIUM("Some spread"),
    LOW("Uncertain"),
}

data class WeatherDay(
    val date: String,
    val code: Int,
    val maxC: Double,
    val minC: Double,
    val sunrise: String,
    val sunset: String,
    val uvMax: Double,
    val popMax: Int,
    val precipMm: Double,
    val windMaxKmh: Double,
    /** Cross-model check: how many models answered, the widest high-temperature gap, and how many call it wet. */
    val modelCount: Int = 1,
    val maxSpreadC: Double = 0.0,
    val wetModels: Int = 0,
) {
    val confidence: Confidence? get() =
        when {
            modelCount < 2 -> null
            maxSpreadC <= 1.5 && (wetModels == 0 || wetModels == modelCount) -> Confidence.HIGH
            maxSpreadC <= 3.0 && (wetModels <= 1 || wetModels >= modelCount - 1) -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
}

data class Forecast(
    val lat: Double,
    val lon: Double,
    val now: WeatherNow,
    val hours: List<WeatherHour>,
    val days: List<WeatherDay>,
    val fetchedAtMs: Long,
    val utcOffsetSec: Int = 0,
    val elevationM: Double = 0.0,
    /** Precipitation (mm) per 15 minutes, starting now. */
    val nowcast: List<Double> = emptyList(),
) {
    /** "Rain in ~25 min" style line from the 15-minute series, or null when there is no series. */
    fun nowcastLine(): String? {
        if (nowcast.isEmpty()) return null
        val wet = nowcast[0] >= 0.1
        val flip = nowcast.indexOfFirst { (it >= 0.1) != wet }
        val word = if (now.sky == Sky.SNOW) "Snow" else "Rain"
        val hours = nowcast.size / 4
        return when {
            wet && flip > 0 -> "$word easing in ~${flip * 15} min"
            wet -> "$word continues for $hours h+"
            flip >= 0 -> "$word in ~${flip * 15} min"
            else -> "No rain for the next ${if (hours == 1) "hour" else "$hours hours"}"
        }
    }

    /** UTC epoch of local midnight today at the forecast location. */
    fun localMidnightMs(): Long = dateToUtcMs(now.time.take(10)) - utcOffsetSec * 1000L

    companion object {
        fun dateToUtcMs(date: String): Long =
            java.text
                .SimpleDateFormat(
                    "yyyy-MM-dd",
                    java.util.Locale.US,
                ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(date)
                ?.time
                ?: 0L
    }
}

enum class WindUnit(
    val label: String,
    val perKmh: Double,
) {
    KMH("km/h", 1.0),
    MPH("mph", 0.621371),
    MS("m/s", 0.277778),
    KN("kn", 0.539957),
    ;

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: KMH
    }
}

/** Display conversions, all driven by the current Weather prefs. */
object WeatherUnits {
    /** Read inside composition by every formatter, so flipping a unit pref repaints whatever shows it. */
    private var version by androidx.compose.runtime.mutableIntStateOf(0)

    fun changed() {
        version++
    }

    private val windUnit get() = version.let { WindUnit.of(AppPreferences.weatherWindUnit) }
    private val imperial get() = version.let { AppPreferences.weatherFahrenheit }

    fun tempValue(c: Double): Int = (if (imperial) c * 9 / 5 + 32 else c).roundToInt()

    fun temp(c: Double): String = "${tempValue(c)}°"

    /** A temperature *difference* (a spread), which converts without the +32 offset. */
    fun tempDelta(c: Double): Double = if (imperial) c * 9 / 5 else c

    /** Hero-size reading: honours the "one decimal" option. */
    fun tempExact(c: Double): String =
        if (version.let { AppPreferences.weatherDecimals }) "%.1f°".format(if (imperial) c * 9 / 5 + 32 else c) else temp(c)

    val tempSymbol: String get() = if (imperial) "°F" else "°C"

    fun wind(kmh: Double): String = "${(kmh * windUnit.perKmh).roundToInt()} ${windUnit.label}"

    fun windValue(kmh: Double): String = (kmh * windUnit.perKmh).roundToInt().toString()

    val windLabel: String get() = windUnit.label

    fun pressure(hpa: Double): String = if (imperial) "%.2f inHg".format(hpa * 0.02953) else "${hpa.roundToInt()} hPa"

    fun visibility(m: Double): String = if (windUnit == WindUnit.MPH) "%.0f mi".format(m / 1609.34) else "%.0f km".format(m / 1000.0)

    fun distance(km: Double): String = if (windUnit == WindUnit.MPH) "%.0f mi".format(km * 0.621371) else "%.0f km".format(km)

    fun length(m: Double): String = if (imperial) "%.1f ft".format(m * 3.28084) else "%.1f m".format(m)

    fun altitude(m: Double): String = if (imperial) "%,d ft".format((m * 3.28084).roundToInt()) else "%,d m".format(m.roundToInt())

    fun precip(mm: Double): String =
        if (imperial) {
            "%.2f in".format(mm / 25.4)
        } else if (mm <
            10
        ) {
            "%.1f mm".format(mm)
        } else {
            "${mm.roundToInt()} mm"
        }

    private val points = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    fun compass(deg: Int): String = points[(((deg % 360) + 360) % 360 / 22.5 + 0.5).toInt() % 16]

    fun uvLabel(uv: Double): String =
        when {
            uv < 3 -> "Low"
            uv < 6 -> "Moderate"
            uv < 8 -> "High"
            uv < 11 -> "Very High"
            else -> "Extreme"
        }
}

/** Open-Meteo forecast + geocoding, and keyless IP geolocation. No API keys anywhere. */
object WeatherService {
    private const val TTL_MS = 10 * 60_000L
    private const val IP_TTL_MS = 6 * 3_600_000L
    private val cache = ConcurrentHashMap<String, Forecast>()
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()

    @Volatile private var ipHit: Pair<Long, Place>? = null

    private fun key(
        lat: Double,
        lon: Double,
    ) = "%.2f,%.2f".format(java.util.Locale.ROOT, lat, lon)

    /** Fresh-or-cached forecast; falls back to stale data when offline, null when there is nothing at all.
     *
     * Concurrent callers for the same location share one request: they queue here, then find the
     * freshly cached result instead of issuing a duplicate forecast call.
     */
    suspend fun forecast(
        lat: Double,
        lon: Double,
        force: Boolean = false,
    ): Forecast? {
        val k = key(lat, lon)
        val now = System.currentTimeMillis()
        if (!force) cache[k]?.takeIf { now - it.fetchedAtMs < TTL_MS }?.let { return it }
        return fetchLocks.getOrPut(k) { Mutex() }.withLock {
            if (!force) cache[k]?.takeIf { System.currentTimeMillis() - it.fetchedAtMs < TTL_MS }?.let { return@withLock it }
            val fresh =
                withContext(Dispatchers.IO) {
                    suspendRunCatching { fetch(lat, lon) }
                        .onFailure { android.util.Log.w("WeatherService", "forecast fetch failed for $k", it) }
                        .getOrNull()
                }
            if (fresh != null) cache[k] = fresh
            fresh ?: cache[k]
        }
    }

    fun cached(
        lat: Double,
        lon: Double,
    ): Forecast? = cache[key(lat, lon)]

    private suspend fun fetch(
        lat: Double,
        lon: Double,
    ): Forecast? {
        val url =
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code," +
                "cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m,dew_point_2m,visibility,uv_index" +
                "&hourly=temperature_2m,precipitation_probability,weather_code,is_day,cape,lifted_index," +
                "convective_inhibition,wind_gusts_10m,precipitation,freezing_level_height" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max," +
                "precipitation_probability_max,precipitation_sum,wind_speed_10m_max" +
                "&minutely_15=precipitation&forecast_minutely_15=24" +
                "&timezone=auto&forecast_days=8&wind_speed_unit=kmh"
        val (mainTxt, spreads) =
            coroutineScope {
                // The model comparison is a bonus: it must never delay or fail the forecast itself.
                val spreadJob = async { withTimeoutOrNull(6_000) { suspendRunCatching { modelSpread(lat, lon) }.getOrNull() } }
                HttpSupport.get(url, connectMs = 8_000, readMs = 15_000) to spreadJob.await()
            }
        val root = HttpSupport.json.parseToJsonElement(mainTxt).jsonObject
        val c = root["current"]?.jsonObject ?: return null
        val now =
            WeatherNow(
                time = c.str("time"),
                tempC = c.num("temperature_2m"),
                feelsC = c.num("apparent_temperature"),
                humidity = c.num("relative_humidity_2m").roundToInt(),
                dewC = c.num("dew_point_2m"),
                pressureHpa = c.num("pressure_msl"),
                windKmh = c.num("wind_speed_10m"),
                gustKmh = c.num("wind_gusts_10m"),
                windDir = c.num("wind_direction_10m").roundToInt(),
                cloud = c.num("cloud_cover").roundToInt(),
                precipMm = c.num("precipitation"),
                visibilityM = c.num("visibility"),
                uv = c.num("uv_index"),
                code = c.num("weather_code").roundToInt(),
                isDay = c.num("is_day") > 0.5,
            )

        val h = root["hourly"]?.jsonObject ?: return null
        val hTimes = h.arr("time")
        val start = hTimes.indexOfFirst { it.jsonPrimitive.content >= now.time.take(13) + ":00" }.coerceAtLeast(0)
        val hours =
            (start until minOf(hTimes.size, start + 36)).map { i ->
                WeatherHour(
                    time = hTimes[i].jsonPrimitive.content,
                    tempC = h.arr("temperature_2m").dbl(i),
                    popPct = h.arr("precipitation_probability").dbl(i).roundToInt(),
                    code = h.arr("weather_code").dbl(i).roundToInt(),
                    isDay = h.arr("is_day").dbl(i) > 0.5,
                    capeJ = h.arr("cape").dbl(i),
                    liftedIndex = h.arr("lifted_index").dbl(i),
                    cinJ = h.arr("convective_inhibition").dbl(i),
                    gustKmh = h.arr("wind_gusts_10m").dbl(i),
                    precipMm = h.arr("precipitation").dbl(i),
                    freezingLevelM = h.arr("freezing_level_height").dbl(i),
                )
            }

        val d = root["daily"]?.jsonObject ?: return null
        val days =
            d.arr("time").indices.map { i ->
                WeatherDay(
                    date = d.arr("time")[i].jsonPrimitive.content,
                    code = d.arr("weather_code").dbl(i).roundToInt(),
                    maxC = d.arr("temperature_2m_max").dbl(i),
                    minC = d.arr("temperature_2m_min").dbl(i),
                    sunrise = d.arr("sunrise")[i].jsonPrimitive.content,
                    sunset = d.arr("sunset")[i].jsonPrimitive.content,
                    uvMax = d.arr("uv_index_max").dbl(i),
                    popMax = d.arr("precipitation_probability_max").dbl(i).roundToInt(),
                    precipMm = d.arr("precipitation_sum").dbl(i),
                    windMaxKmh = d.arr("wind_speed_10m_max").dbl(i),
                ).let { day ->
                    spreads?.getOrNull(i)?.let { day.copy(modelCount = it.models, maxSpreadC = it.spreadC, wetModels = it.wet) } ?: day
                }
            }
        val nowcast = ((root["minutely_15"] as? JsonObject)?.get("precipitation") as? JsonArray)?.map { it.asDouble() }.orEmpty()
        return Forecast(
            lat,
            lon,
            now,
            hours,
            days,
            System.currentTimeMillis(),
            root.num("utc_offset_seconds").toInt(),
            elevationM = root.num("elevation"),
            nowcast = nowcast,
        )
    }

    private data class DaySpread(
        val models: Int,
        val spreadC: Double,
        val wet: Int,
    )

    /**
     * Asks four independent global models (Open-Meteo's best match, ECMWF, GFS, ICON) for the same eight days and
     * measures how far apart their highs are and whether they agree on rain. Disagreement is the honest error bar.
     */
    private suspend fun modelSpread(
        lat: Double,
        lon: Double,
    ): List<DaySpread> {
        val url =
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&daily=temperature_2m_max,precipitation_sum&timezone=auto&forecast_days=8" +
                "&models=best_match,ecmwf_ifs025,gfs_seamless,icon_seamless"
        val d =
            HttpSupport.json
                .parseToJsonElement(HttpSupport.get(url))
                .jsonObject["daily"]
                ?.jsonObject ?: return emptyList()
        val tempKeys = d.keys.filter { it.startsWith("temperature_2m_max") }
        val rainKeys = d.keys.filter { it.startsWith("precipitation_sum") }
        return d.arr("time").indices.map { i ->
            val t = tempKeys.mapNotNull { d.arr(it).realOrNull(i) }
            val r = rainKeys.mapNotNull { d.arr(it).realOrNull(i) }
            DaySpread(models = t.size, spreadC = (t.maxOrNull() ?: 0.0) - (t.minOrNull() ?: 0.0), wet = r.count { it >= 1.0 })
        }
    }

    /** City search, best match first. */
    suspend fun search(query: String): List<Place> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < 2) return@withContext emptyList()
            val url =
                "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name=" +
                    URLEncoder.encode(q, "UTF-8")
            suspendRunCatching {
                val results =
                    HttpSupport.json.parseToJsonElement(HttpSupport.get(url)).jsonObject["results"] as? JsonArray
                        ?: return@suspendRunCatching emptyList()
                results.map { it.jsonObject }.map { r ->
                    Place(
                        name = r.str("name"),
                        region = r.str("admin1"),
                        country = r.str("country"),
                        lat = r.num("latitude"),
                        lon = r.num("longitude"),
                    )
                }
            }.getOrDefault(emptyList())
        }

    /**
     * Where the device is: its own Wi-Fi/GPS fix when the OS gives one (steady, tens of metres), otherwise the IP
     * location. IP lookups wander between neighbouring towns, so they are only the fallback.
     */
    suspend fun locateAuto(
        ctx: android.content.Context,
        force: Boolean = false,
    ): Place? {
        val fix = suspendRunCatching { DeviceLocation.fix(ctx) }.getOrNull()
        if (fix != null) {
            return suspendRunCatching { reverseGeocode(fix.lat, fix.lon) }.getOrNull()
                ?: Place("", "", "", fix.lat, fix.lon)
        }
        return locateByIp(force)
    }

    /** Approximate location from the public IP. TVs have no GPS; this is city-level and cached for hours. */
    suspend fun locateByIp(force: Boolean = false): Place? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            ipHit?.takeIf { !force && now - it.first < IP_TTL_MS }?.let { return@withContext it.second }
            val primary =
                suspendRunCatching {
                    val o =
                        HttpSupport.json
                            .parseToJsonElement(
                                HttpSupport.get("https://ipwho.is/", connectMs = 6_000, readMs = 8_000),
                            ).jsonObject
                    if (o["success"]?.jsonPrimitive?.booleanOrNull == false) return@suspendRunCatching null
                    Place(o.str("city"), o.str("region"), o.str("country"), o.num("latitude"), o.num("longitude"))
                }.getOrNull()
            val fallback =
                if (primary == null) {
                    suspendRunCatching {
                        val o =
                            HttpSupport.json
                                .parseToJsonElement(
                                    HttpSupport.get("https://get.geojs.io/v1/ip/geo.json", connectMs = 6_000, readMs = 8_000),
                                ).jsonObject
                        Place(o.str("city"), o.str("region"), o.str("country"), o.num("latitude"), o.num("longitude"))
                    }.getOrNull()
                } else {
                    null
                }
            val place = (primary ?: fallback)?.takeIf { it.name.isNotBlank() }
            if (place != null) ipHit = now to place
            place ?: ipHit?.second
        }

    /** Make [p] the active place for the whole app (Weather screen and widgets). */
    fun activate(p: Place) {
        AppPreferences.weatherLat = p.lat
        AppPreferences.weatherLon = p.lon
        AppPreferences.cityName = p.shortLabel
    }
}

private fun JsonObject.str(k: String): String =
    (this[k] as? JsonPrimitive)
        ?.takeIf {
            it.isString || it.content != "null"
        }?.content
        .orEmpty()

private fun JsonObject.num(k: String): Double = this[k].asDouble()

private fun JsonObject.arr(k: String): JsonArray = this[k]?.jsonArray ?: JsonArray(emptyList())

private fun JsonArray.dbl(i: Int): Double = getOrNull(i).asDouble()

/** Null for a missing or JSON-null sample, so a model that has no value never reads as "0". */
private fun JsonArray.realOrNull(i: Int): Double? = (getOrNull(i) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

private fun JsonElement?.asDouble(): Double = (this as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
