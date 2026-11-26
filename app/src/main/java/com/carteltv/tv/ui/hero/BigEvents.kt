package com.carteltv.tv.ui.hero
import com.carteltv.tv.data.espn.EspnScoreboardClient
import kotlin.math.abs

/** Why a game earned a place at the front of the hero, best reason first. */
internal enum class EventReason { CREW_LIVE, FAVOURITE_LIVE, CREW_SOON, LIVE, FAVOURITE_SOON }

internal data class BigEvent(val league: String, val game: EspnScoreboardClient.Score, val reason: EventReason) {
    val key: String get() = "$league:${game.name}"
    val live: Boolean get() = game.state == "in"
}

private const val MINUTE_MS = 60_000L
private const val FEED_LAG_MS = 10 * MINUTE_MS
private const val CREW_SOON_MS = 90 * MINUTE_MS
private const val FAVOURITE_SOON_MS = 60 * MINUTE_MS
internal const val MAX_BIG_EVENTS = 3

/**
 * The games worth interrupting the hero for: anything live, ranked by whether it involves a followed
 * team or the favourite league, plus followed-team and favourite-league games about to start.
 * Closer scores rank first within a reason; finished and distant games never qualify.
 */
internal fun bigEvents(
    scores: Map<String, List<EspnScoreboardClient.Score>>,
    favouriteLeague: String,
    nowMs: Long,
    involvesCrew: (league: String, game: EspnScoreboardClient.Score) -> Boolean,
    limit: Int = MAX_BIG_EVENTS,
): List<BigEvent> =
    scores.flatMap { (league, games) ->
        games.mapNotNull { g ->
            reasonFor(league, g, favouriteLeague, nowMs, involvesCrew)?.let { BigEvent(league, g, it) }
        }
    }
        .sortedWith(compareBy<BigEvent>({ it.reason.ordinal }, { margin(it.game) ?: Int.MAX_VALUE }, { it.game.startMs }))
        .take(limit)

private fun reasonFor(
    league: String,
    game: EspnScoreboardClient.Score,
    favouriteLeague: String,
    nowMs: Long,
    involvesCrew: (String, EspnScoreboardClient.Score) -> Boolean,
): EventReason? {
    val crew = involvesCrew(league, game)
    val favourite = favouriteLeague.isNotBlank() && league == favouriteLeague
    return when (game.state) {
        "in" -> when {
            crew -> EventReason.CREW_LIVE
            favourite -> EventReason.FAVOURITE_LIVE
            else -> EventReason.LIVE
        }
        "pre" -> {
            val until = game.startMs - nowMs
            when {
                game.startMs <= 0L -> null
                crew && until in -FEED_LAG_MS..CREW_SOON_MS -> EventReason.CREW_SOON
                favourite && until in -FEED_LAG_MS..FAVOURITE_SOON_MS -> EventReason.FAVOURITE_SOON
                else -> null
            }
        }
        else -> null
    }
}

private fun margin(game: EspnScoreboardClient.Score): Int? {
    val a = game.scoreA?.toIntOrNull() ?: return null
    val b = game.scoreB?.toIntOrNull() ?: return null
    return abs(a - b)
}
