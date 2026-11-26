package com.carteltv.tv.data.espn

import android.content.Context
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.EpgIndexDatabase
import com.carteltv.tv.data.xtream.LiveChannels
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.engine.stability.BackgroundWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Which of the viewer's own provider channels carries a game: the guide listings that name the teams first, then
 * the channels whose names fit the broadcaster. The sheet that lists every candidate and the hero's one-press
 * Watch both ask here, so they can never disagree about where a game is on.
 */
object GameChannelFinder {
    private class Entry(val atMs: Long, val matches: List<GameChannelResolver.Match>)

    private val cache = ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 5 * 60_000L

    /** Every candidate channel, best first; empty with no provider signed in. */
    suspend fun rank(ctx: Context, game: EspnScoreboardClient.Score, league: String): List<GameChannelResolver.Match> {
        val client = ProviderSession.current ?: return emptyList()
        val key = "${client.hashCode()}|$league|${GameChannelResolver.pickKey(game)}"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.atMs < TTL_MS }?.let { return it.matches }
        val streams = LiveChannels.all(client)
        val guideHits: List<GuideGameMatcher.Hit> = withContext(BackgroundWorkScheduler.reads) {
            runCatching {
                val terms = GameChannelResolver.searchTerms(game)
                if (terms.size < 2) {
                    emptyList()
                } else {
                    GuideGameMatcher.rank(
                        game,
                        EpgIndexDatabase.get(ctx).searchPrograms(terms.joinToString(" "), 40).map {
                            GuideGameMatcher.Listing(it.streamId, it.title, it.description, it.startMs, it.stopMs)
                        },
                        now,
                    )
                }
            }.getOrDefault(emptyList())
        }
        // Scoring every channel is real work, so it runs off the main thread.
        val ranked = withContext(Dispatchers.Default) {
            GameChannelResolver.rank(
                game = game,
                streams = streams,
                preferredCountries = AppPreferences.sportsPreferredCountries(),
                preferQuality = AppPreferences.sportsPreferQuality,
                pickedStreamId = GameChannelResolver.savedPick(AppPreferences.sportsChannelPicks, game),
                guideHits = guideHits,
                leagueLabel = league,
            )
        }
        cache[key] = Entry(now, ranked)
        return ranked
    }

    /** The channel to tune for [game], or null when nothing in the provider fits. */
    suspend fun best(ctx: Context, game: EspnScoreboardClient.Score, league: String): GameChannelResolver.Match? =
        rank(ctx, game, league).firstOrNull()
}