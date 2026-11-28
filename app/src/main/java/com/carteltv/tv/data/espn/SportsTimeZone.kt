package com.carteltv.tv.data.espn

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DateTimeException
import java.time.ZoneId

/**
 * The time zone the sports screens live in: what "today" means, the day strip, and every kickoff time. A TV's own
 * zone setting is often wrong (UTC, or the box's factory region), so by default it comes from the viewer's IP
 * address; the device zone is one setting away.
 */
object SportsTimeZone {

    enum class Mode(val key: String, val label: String) {
        AUTO("auto", "Automatic (from your IP)"),
        DEVICE("device", "This device's setting"),
        MANUAL("manual", "Choose a zone");

        companion object {
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key } ?: AUTO
        }
    }

    /** Refresh the IP lookup once it is this old; an address rarely changes zone. */
    const val STALE_MS = 12 * 60 * 60_000L

    /** A zone id that Java knows, or null. Rejects blanks and offsets-only junk a service might return. */
    fun parseZone(raw: String?): ZoneId? {
        val id = raw?.trim().orEmpty()
        if (id.isEmpty() || '/' !in id) return null // "UTC" alone is a sign of a missing answer, not a place
        return try { ZoneId.of(id) } catch (_: DateTimeException) { null }
    }

    /**
     * The zone to use, given the setting, the chosen zone, the last IP answer and the device zone. A chosen zone that
     * is missing or invalid falls back to the IP zone, then the device, so the screen is never left without one.
     */
    fun resolve(
        mode: Mode, ipZoneId: String?, manualZoneId: String? = null, device: ZoneId = ZoneId.systemDefault(),
    ): ZoneId = when (mode) {
        Mode.MANUAL -> parseZone(manualZoneId) ?: parseZone(ipZoneId) ?: device
        Mode.AUTO -> parseZone(ipZoneId) ?: device
        Mode.DEVICE -> device
    }

    fun current(): ZoneId = resolve(Mode.of(AppPreferences.sportsZoneMode), AppPreferences.ipZoneId, AppPreferences.sportsZoneManual)

    /** The zone the sports screens are using right now. Compose reads of this recompose when [reload] changes it. */
    var live: ZoneId by mutableStateOf(ZoneId.systemDefault())
        private set

    /** Re-reads the setting after the viewer changes it or an IP lookup finishes. */
    fun reload() { live = current() }

    /** What Settings shows: the zone and where it came from. */
    fun describe(nowMs: Long = System.currentTimeMillis()): String {
        val mode = Mode.of(AppPreferences.sportsZoneMode)
        val zone = current()
        val source = when {
            mode == Mode.MANUAL && parseZone(AppPreferences.sportsZoneManual) != null -> "chosen"
            mode == Mode.AUTO && parseZone(AppPreferences.ipZoneId) != null -> "from your IP"
            else -> "device"
        }
        return "${TimeZoneCatalog.label(zone.id, nowMs)} · $source"
    }

    /** ipwho.is: {"success":true,"timezone":{"id":"Australia/Brisbane"}} */
    internal fun parseIpwho(json: String): String? = runCatching {
        val root = HttpSupport.lenientJson.parseToJsonElement(json).jsonObject
        if (root["success"]?.jsonPrimitive?.contentOrNull == "false") return null
        (root["timezone"] as? kotlinx.serialization.json.JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /** ipapi.co and ipinfo.io: {"timezone":"Australia/Brisbane"} */
    internal fun parseFlat(json: String): String? = runCatching {
        HttpSupport.lenientJson.parseToJsonElement(json).jsonObject["timezone"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private val services: List<Pair<String, (String) -> String?>> = listOf(
        "https://ipwho.is/?fields=success,timezone" to ::parseIpwho,
        "https://ipapi.co/json/" to ::parseFlat,
        "https://ipinfo.io/json" to ::parseFlat,
    )

    /**
     * Asks the IP services in turn for the viewer's zone and remembers the first valid answer. A failure leaves the
     * previous answer in place. Returns the zone id now stored, or null if there never was one.
     */
    suspend fun refreshIfStale(nowMs: Long = System.currentTimeMillis(), force: Boolean = false): String? {
        if (Mode.of(AppPreferences.sportsZoneMode) != Mode.AUTO && !force) return AppPreferences.ipZoneId.ifBlank { null }
        if (!force && parseZone(AppPreferences.ipZoneId) != null && nowMs - AppPreferences.ipZoneAtMs < STALE_MS) return AppPreferences.ipZoneId
        for ((url, parse) in services) {
            val zone = try {
                parseZone(parse(HttpSupport.get(url)))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            AppPreferences.ipZoneId = zone.id
            AppPreferences.ipZoneAtMs = nowMs
            return zone.id
        }
        return AppPreferences.ipZoneId.ifBlank { null }
    }
}
