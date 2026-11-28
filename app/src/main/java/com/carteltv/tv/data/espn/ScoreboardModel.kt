package com.carteltv.tv.data.espn

import java.time.ZoneId
import java.util.Locale

/**
 * Everything a broadcast-style scorebug shows for one game, worked out without any UI: both sides with their scores,
 * the status line (game clock, a live countdown to kickoff, or the final), the local kickoff time, the ground and
 * its city, and the TV networks. Away is on the left and home on the right, as on air.
 */
object ScoreboardModel {

    enum class Phase { LIVE, UPCOMING, FINAL }

    data class Side(val abbr: String, val name: String, val logo: String?, val score: String?, val record: String?)

    data class Bug(
        val phase: Phase,
        val away: Side,
        val home: Side,
        /** "5:32 - 2nd", "Halftime", "STARTS IN 2h 15m", "FINAL". */
        val status: String,
        /** Local kickoff time for an upcoming game ("09:30"); blank otherwise. */
        val kickoff: String,
        /** "Lenovo Center · Raleigh, NC", or null when the feed names neither. */
        val place: String?,
        val networks: List<String>,
        /** The book line ("CIN -6 · O/U 54.5"); null when absent or the game is final. */
        val odds: String? = null,
    )

    /** What replaces a hidden score when the spoiler shield is on. */
    const val HIDDEN = "\u2022"

    fun phase(state: String): Phase = when (state) {
        "in" -> Phase.LIVE
        "post" -> Phase.FINAL
        else -> Phase.UPCOMING
    }

    /**
     * Time until kickoff as a countdown: "2d 3h", "2h 15m", "14:05" inside the last hour, "42s", and "Starting"
     * once it is due. [msUntil] is start minus now.
     */
    fun countdown(msUntil: Long): String {
        if (msUntil <= 0L) return "Starting"
        val totalSec = msUntil / 1000
        val days = totalSec / 86_400
        val hours = totalSec % 86_400 / 3600
        val mins = totalSec % 3600 / 60
        val secs = totalSec % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            totalSec >= 3600 -> "${hours}h ${mins}m"
            totalSec >= 60 -> "%d:%02d".format(Locale.ROOT, mins, secs)
            else -> "${secs}s"
        }
    }

    /** How soon the countdown on screen must refresh: every second in the last hour, else every half minute. */
    fun tickMs(msUntil: Long): Long = if (msUntil in 0..3_600_000L) 1_000L else 30_000L

    /**
     * The time at the ground ("19:15 EST") for [ms], but only when the ground's clock differs from the viewer's:
     * if both zones are on the same offset then, repeating the time would just be noise. Null if the venue zone is
     * unknown or [ms] is not a real time.
     */
    fun venueTime(ms: Long, venueZone: ZoneId?, viewerZone: ZoneId, locale: Locale = Locale.getDefault()): String? {
        if (venueZone == null || ms <= 0L) return null
        val at = java.time.Instant.ofEpochMilli(ms)
        if (venueZone.rules.getOffset(at) == viewerZone.rules.getOffset(at)) return null
        return at.atZone(venueZone).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm zzz", locale))
    }

    /** The ground and the city, joined; either alone if that is all there is. */
    fun place(venue: String?, location: String?): String? =
        listOfNotNull(venue?.trim()?.takeIf { it.isNotEmpty() }, location?.trim()?.takeIf { it.isNotEmpty() })
            .distinct().joinToString(" · ").ifBlank { null }

    /** The feed's own live line, else the clock, else a plain "LIVE". */
    private fun liveStatus(g: EspnScoreboardClient.Score): String =
        g.shortDetail?.takeIf { it.isNotBlank() } ?: g.detail.takeIf { it.isNotBlank() }
            ?: g.clock?.takeIf { it.isNotBlank() } ?: "LIVE"

    /** "Final", "FT", "Final/OT" as the feed words them; anything else (a stale string) becomes plain "FINAL". */
    private fun finalStatus(g: EspnScoreboardClient.Score): String =
        g.shortDetail?.takeIf { it.startsWith("F", ignoreCase = true) && it.length <= 10 }?.uppercase(Locale.ROOT) ?: "FINAL"

    private fun side(abbr: String?, name: String?, logo: String?, score: String?, record: String?, phase: Phase, shielded: Boolean) = Side(
        abbr = abbr?.takeIf { it.isNotBlank() } ?: name?.take(3)?.uppercase(Locale.ROOT) ?: "?",
        name = name?.takeIf { it.isNotBlank() } ?: abbr ?: "",
        logo = logo,
        score = when {
            phase == Phase.UPCOMING -> null
            shielded -> HIDDEN
            else -> score ?: "0"
        },
        record = record,
    )

    fun build(g: EspnScoreboardClient.Score, nowMs: Long, zone: ZoneId, shielded: Boolean = false, locale: Locale = Locale.getDefault()): Bug {
        val phase = phase(g.state)
        val away = side(g.abbrB, g.nameB, g.logoB, g.scoreB, g.recordB, phase, shielded)
        val home = side(g.abbrA, g.nameA, g.logoA, g.scoreA, g.recordA, phase, shielded)
        val status = when (phase) {
            Phase.LIVE -> liveStatus(g)
            Phase.FINAL -> finalStatus(g)
            Phase.UPCOMING -> if (g.startMs > 0) "STARTS IN ${countdown(g.startMs - nowMs).uppercase(Locale.ROOT)}" else "UPCOMING"
        }
        return Bug(
            phase = phase, away = away, home = home, status = status,
            kickoff = if (phase == Phase.UPCOMING) EspnDateMapper.clock(g.startMs, zone, locale) else "",
            place = place(g.venue, g.location),
            networks = (listOfNotNull(g.network) + g.networks).map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            odds = if (phase == Phase.FINAL) null else g.odds,
        )
    }
}
