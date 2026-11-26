package com.carteltv.tv.data.espn

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/**
 * Several scoreboards behind one [ScoreboardSource]. Every source that covers a league is asked; a game that two
 * sources both list becomes one game carrying the union of their broadcasters, and a game only one source lists
 * is kept. A source that fails or returns nothing never hides the others.
 */
class CompositeScoreboardSource(private val sources: List<ScoreboardSource>) : ScoreboardSource {

    override suspend fun scoreboard(sportPath: String, date: String?): List<EspnScoreboardClient.Score> =
        scoreboardOrNull(sportPath, date) ?: emptyList()

    /** Null only when every source failed; one source answering is enough. Cancellation is never swallowed. */
    override suspend fun scoreboardOrNull(sportPath: String, date: String?): List<EspnScoreboardClient.Score>? {
        val boards: List<List<EspnScoreboardClient.Score>?> = kotlinx.coroutines.coroutineScope {
            sources.map { src ->
                async {
                    try {
                        src.scoreboardOrNull(sportPath, date)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
        if (boards.all { it == null }) return null
        return merge(boards.filterNotNull())
    }

    companion object {
        val default: ScoreboardSource by lazy { CompositeScoreboardSource(listOf(EspnScoreboardClient(), NhlScoreboardClient())) }

        /** Two listings are the same game when the same two team nicknames play within three hours of each other. */
        private const val SAME_GAME_MS = 3 * 60 * 60_000L

        private fun key(s: EspnScoreboardClient.Score): Pair<String, String>? {
            val a = s.nameA?.lowercase()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val b = s.nameB?.lowercase()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return a to b
        }

        private fun sameGame(x: EspnScoreboardClient.Score, y: EspnScoreboardClient.Score): Boolean {
            val kx = key(x) ?: return false
            val ky = key(y) ?: return false
            if (kx != ky) return false
            return x.startMs == 0L || y.startMs == 0L || kotlin.math.abs(x.startMs - y.startMs) <= SAME_GAME_MS
        }

        private fun allNetworks(s: EspnScoreboardClient.Score): List<String> =
            (listOfNotNull(s.network) + s.networks).filter { it.isNotBlank() }.distinct()

        /** The first non-empty board is the primary: its details win, the others add broadcasters and extra games. */
        fun merge(boards: List<List<EspnScoreboardClient.Score>>): List<EspnScoreboardClient.Score> {
            val nonEmpty = boards.filter { it.isNotEmpty() }
            if (nonEmpty.isEmpty()) return emptyList()
            val out = nonEmpty.first().toMutableList()
            for (extra in nonEmpty.drop(1)) {
                for (game in extra) {
                    val i = out.indexOfFirst { sameGame(it, game) }
                    if (i < 0) { out += game; continue }
                    val primary = out[i]
                    val nets = (allNetworks(primary) + allNetworks(game)).distinct()
                    out[i] = primary.copy(
                        network = primary.network ?: nets.firstOrNull(), networks = nets,
                        // Whatever the primary lacks, the other source may know.
                        venue = primary.venue ?: game.venue, location = primary.location ?: game.location,
                    )
                }
            }
            return out
        }
    }
}
