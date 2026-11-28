package com.carteltv.tv.data.espn

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Maps a day in the viewer's zone onto ESPN's feed. ESPN files every game under the US Eastern date it starts on, so
 * a viewer in Brisbane sees Saturday's games under two Eastern dates (Friday's slate, and Saturday's morning), and
 * one in Los Angeles sees an evening game under tomorrow's. A local day therefore fetches every Eastern date it
 * touches and keeps only the games that really start inside it.
 */
object EspnDateMapper {

    /** US Eastern — the zone ESPN files every game's date in. Other feeds' Eastern stamps (Xtream event slots) read it too. */
    val EASTERN: ZoneId = ZoneId.of("America/New_York")

    private val ESPN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

    /** One calendar day in some zone: [startMs] inclusive, [endMs] exclusive. */
    data class Window(val date: LocalDate, val startMs: Long, val endMs: Long)

    /** The day [offset] days from today in [zone]. A day is 23 or 25 hours long across a clock change. */
    fun window(zone: ZoneId, offset: Int, nowMs: Long): Window {
        val date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(offset.toLong())
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return Window(date, start, end)
    }

    /** The `dates=` values (yyyyMMdd, Eastern) whose games can start inside [w]; one or two. */
    fun espnDates(w: Window): List<String> {
        fun etDate(ms: Long) = Instant.ofEpochMilli(ms).atZone(EASTERN).toLocalDate()
        val first = etDate(w.startMs)
        val last = etDate(w.endMs - 1)
        return generateSequence(first) { d -> d.plusDays(1).takeIf { !it.isAfter(last) } }.map { it.format(ESPN_DATE) }.toList()
    }

    /**
     * Whether [game] belongs on [w]. It must start inside the window; a game already in progress also shows on
     * today's window whatever its start, and one with no start time is kept (nothing says it does not belong).
     */
    fun belongs(game: EspnScoreboardClient.Score, w: Window, nowMs: Long): Boolean = when {
        game.state == "in" && nowMs in w.startMs until w.endMs -> true
        game.startMs <= 0L -> true
        else -> game.startMs in w.startMs until w.endMs
    }

    /** Boards for several Eastern dates as one list: each game once, earliest start first. */
    fun merge(boards: List<List<EspnScoreboardClient.Score>>): List<EspnScoreboardClient.Score> =
        boards.flatten().distinctBy { it.name to it.startMs }.sortedBy { if (it.startMs > 0) it.startMs else Long.MAX_VALUE }

    /** "Yesterday", "Today", "Tomorrow", then "Sun 23", in [zone]. */
    fun label(offset: Int, zone: ZoneId, nowMs: Long, locale: Locale = Locale.getDefault()): String = when (offset) {
        -1 -> "Yesterday"
        0 -> "Today"
        1 -> "Tomorrow"
        else -> window(zone, offset, nowMs).date.format(DateTimeFormatter.ofPattern("EEE d", locale))
    }

    /** Kickoff as a clock time in [zone], or "" when unknown. */
    fun clock(ms: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        if (ms <= 0L) "" else Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", locale))
}
