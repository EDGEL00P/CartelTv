package com.carteltv.tv.data.espn

/** A provider of live scoreboards. Implementations are data sources; screens and monitors go through [ScoreboardRepository]. */
interface ScoreboardSource {
    suspend fun scoreboard(sportPath: String, date: String? = null): List<EspnScoreboardClient.Score>

    /**
     * Like [scoreboard], but null when the fetch failed, so "no games" and "could not reach the feed" differ.
     * Sources that cannot tell the two apart keep this default.
     */
    suspend fun scoreboardOrNull(sportPath: String, date: String? = null): List<EspnScoreboardClient.Score>? = scoreboard(sportPath, date)
}
