package com.carteltv.tv.data.espn

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * Every world time zone Java knows, grouped by region for a two-step picker (region, then zone), each shown with
 * its UTC offset right now so "Brisbane" and "(UTC+10:00)" are in the same line.
 */
object TimeZoneCatalog {

    data class Zone(val id: String, val label: String, val offsetSeconds: Int)

    /** Regions in the order a picker shows them; "Other" holds UTC. */
    val REGIONS: List<String> =
        listOf("America", "Europe", "Asia", "Australia", "Pacific", "Africa", "Atlantic", "Indian", "Antarctica", "Other")

    private val REGION_PREFIX = Regex("^(Africa|America|Antarctica|Asia|Atlantic|Australia|Europe|Indian|Pacific)/.+")

    /** "Argentina/Buenos_Aires" becomes "Argentina, Buenos Aires"; "Brisbane" stays "Brisbane". */
    internal fun cityOf(id: String): String =
        id.substringAfter('/').split('/').joinToString(", ") { it.replace('_', ' ') }

    /** "UTC+10:00", "UTC-03:30", "UTC". */
    fun offsetLabel(offsetSeconds: Int): String {
        if (offsetSeconds == 0) return "UTC"
        val sign = if (offsetSeconds < 0) "-" else "+"
        val abs = kotlin.math.abs(offsetSeconds)
        return "UTC%s%02d:%02d".format(Locale.ROOT, sign, abs / 3600, abs % 3600 / 60)
    }

    fun offsetAt(zone: ZoneId, nowMs: Long): Int = zone.rules.getOffset(Instant.ofEpochMilli(nowMs)).totalSeconds

    /** The zone ids offered: regional ones only, no "Etc/" or legacy aliases. */
    fun allIds(available: Set<String> = ZoneId.getAvailableZoneIds()): List<String> =
        available.filter { REGION_PREFIX.matches(it) }.sorted()

    fun regionOf(id: String): String =
        if (id == "UTC") "Other" else id.substringBefore('/').takeIf { it in REGIONS } ?: "Other"

    /** The zones of [region], earliest offset first, then by name. */
    fun zones(region: String, nowMs: Long, available: Set<String> = ZoneId.getAvailableZoneIds()): List<Zone> {
        val ids = if (region == "Other") listOf("UTC") else allIds(available).filter { regionOf(it) == region }
        return ids.map { id ->
            val off = offsetAt(ZoneId.of(id), nowMs)
            val city = if (id == "UTC") "Coordinated Universal Time" else cityOf(id)
            Zone(id, "$city (${offsetLabel(off)})", off)
        }.sortedWith(compareBy({ it.offsetSeconds }, { it.label }))
    }

    /** A one-line label for any zone id, for showing the current choice. */
    fun label(id: String, nowMs: Long): String {
        val zone = SportsTimeZone.parseZone(id) ?: return id
        return "${cityOf(id)} (${offsetLabel(offsetAt(zone, nowMs))})"
    }

    /** Fixed offset used only to prove [offsetLabel] is zone-independent in tests. */
    internal fun utc(): ZoneOffset = ZoneOffset.UTC
}
