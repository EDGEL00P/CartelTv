package com.carteltv.tv.data.weather
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** Open-Meteo current conditions — keyless, with shared TTL cache so Dock & the Home glance widgets never double-fetch. */
/** Seam for current conditions so weather consumers can be driven by a fake. */
interface WeatherSource {
    suspend fun current(lat: Double, lon: Double): WeatherClient.Conditions?
}

class WeatherClient : WeatherSource {
    data class Conditions(
        val tempC: Double, val humidity: Int, val isDay: Boolean,
        val highC: Double? = null, val lowC: Double? = null,
        val air: AirQuality? = null,
        /** Open-Meteo's live offset for this location, including its current daylight-saving state. */
        val utcOffsetSec: Int = 0,
        /** The WMO weather code; [Sky.describe] names it and [Sky.of] picks the matching art. */
        val code: Int = 3,
    ) {
        val sky: Sky get() = Sky.of(code)
    }

    override suspend fun current(lat: Double, lon: Double): Conditions? = withContext(Dispatchers.IO) {
        suspendRunCatching {
            coroutineScope {
                val weather = async {
                    val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,relative_humidity_2m,is_day,weather_code" +
                        "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"
                    val txt = HttpSupport.get(url, connectMs = 8_000, readMs = 12_000)
                    HttpSupport.json.parseToJsonElement(txt).jsonObject
                }
                val air = async { if (AppPreferences.weatherAqi) AirQualityService.current(lat, lon) else null }
                val root = weather.await()
                val cur = root["current"]?.jsonObject ?: return@coroutineScope null
                val daily = root["daily"]?.jsonObject
                fun first(key: String) = daily?.get(key)?.jsonArray?.firstOrNull()?.jsonPrimitive?.content?.toDoubleOrNull()
                val code = cur["weather_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@coroutineScope null
                Conditions(
                    tempC = cur["temperature_2m"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@coroutineScope null,
                    humidity = cur["relative_humidity_2m"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@coroutineScope null,
                    isDay = cur["is_day"]?.jsonPrimitive?.content == "1",
                    highC = first("temperature_2m_max"),
                    lowC = first("temperature_2m_min"),
                    air = air.await(),
                    utcOffsetSec = root["utc_offset_seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    code = code,
                )
            }
        }.getOrNull()
    }

    companion object {
        private const val TTL_MS = 10 * 60_000L
        private data class Entry(val atMs: Long, val value: Conditions)
        private val cache = ConcurrentHashMap<String, Entry>()

        suspend fun cachedCurrent(lat: Double, lon: Double, force: Boolean = false): Conditions? {
            val key = "%.2f,%.2f".format(java.util.Locale.ROOT, lat, lon)
            val now = System.currentTimeMillis()
            if (!force) {
                cache[key]?.takeIf { now - it.atMs < TTL_MS }?.let { return it.value }
            }
            val fresh = WeatherClient().current(lat, lon)
            if (fresh != null) cache[key] = Entry(now, fresh)
            return fresh ?: cache[key]?.value
        }
    }
}
