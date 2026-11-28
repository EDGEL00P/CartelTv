package com.carteltv.tv.data.espn

/**
 * How long to wait before asking for scores again. ESPN has no push feed, so "real time" means polling often
 * while it matters and rarely when it does not: every [LIVE_MS] while any game is in progress, sooner as a kick-off
 * approaches, and only every few minutes when nothing is on. Pure logic, shared by Home and Sports.
 */
object ScoreRefreshPolicy {
    const val LIVE_MS = 15_000L
    const val KICKOFF_SOON_MS = 30_000L
    const val TODAY_MS = 60_000L
    const val QUIET_MS = 180_000L

    /** Polls within this window of each other share one request through the cache. */
    const val SHARE_MS = 8_000L

    private const val SOON_WINDOW_MS = 15 * 60_000L
    private const val TODAY_WINDOW_MS = 2 * 60 * 60_000L
    private const val JUST_STARTED_MS = 10 * 60_000L

    fun nextDelayMs(games: List<EspnScoreboardClient.Score>, nowMs: Long): Long {
        if (games.any { it.state == "in" }) return LIVE_MS
        val upcoming = games.filter { it.state == "pre" && it.startMs > 0 }.map { it.startMs - nowMs }
        // A game "pre" shortly after its scheduled start is about to flip to live: keep checking quickly.
        if (upcoming.any { it in -JUST_STARTED_MS..SOON_WINDOW_MS }) return KICKOFF_SOON_MS
        if (upcoming.any { it in 0..TODAY_WINDOW_MS }) return TODAY_MS
        return QUIET_MS
    }
}
