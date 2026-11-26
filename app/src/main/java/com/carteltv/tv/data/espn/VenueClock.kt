package com.carteltv.tv.data.espn

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The venue's own wall clock and IANA timezone, resolved from the scoreboard's city/region. */
object VenueClock {
    private data class Cached(val atMs: Long, val zone: ZoneId?)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val TTL_MS = 7 * 24 * 60 * 60_000L

    suspend fun zoneFor(location: String?): ZoneId? {
        val key = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now - it.atMs < TTL_MS) return it.zone }
        val zone = withContext(Dispatchers.IO) {
            try {
                val (city, _) = MatchConditionsSource.splitLocation(key)
                if (city.isBlank()) return@withContext null
                val url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name=" +
                    URLEncoder.encode(city, "UTF-8")
                val place = MatchConditionsSource.pickPlace(MatchConditionsSource.parseGeo(HttpSupport.get(url)), key)
                    ?: return@withContext null
                place.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        cache[key] = Cached(now, zone)
        return zone
    }

    /** "19:42 EST" at the venue; null when its zone is not known. */
    fun localTime(zone: ZoneId?, nowMs: Long, locale: Locale = Locale.getDefault()): String? =
        zone?.let {
            Instant.ofEpochMilli(nowMs).atZone(it)
                .format(DateTimeFormatter.ofPattern("HH:mm zzz", locale))
        }
}
