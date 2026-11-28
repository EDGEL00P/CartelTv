package com.carteltv.tv.data.espn

/**
 * Scores guide listings against a game: is this programme the game itself, live or about to start?
 * Evidence, strongest first: both teams in the title, then in the description; a "vs" / "at" / "@" between
 * them; and a listing time that fits the game. Replays, highlights and pre/post-game shows are marked down
 * so they never outrank the real broadcast. Pure logic, no Android types.
 */
object GuideGameMatcher {

    /** One guide programme on one channel. */
    data class Listing(val streamId: Long, val title: String, val description: String?, val startMs: Long, val stopMs: Long)

    data class Hit(val streamId: Long, val score: Int, val reason: String)

    /** Listings at or above this score are treated as "the game is on this channel". */
    const val ON_GUIDE = 60

    private const val BOTH_IN_TITLE = 70
    private const val BOTH_IN_DESCRIPTION = 45
    private const val ONE_IN_TITLE = 20
    private const val VERSUS = 15
    private const val TIME_FITS = 25
    private const val TIME_FAR = -40
    private const val REPLAY = -60
    private const val SHOW_AROUND = -60
    private const val DESCRIPTION_CHARS = 400

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    private val REPLAY_WORDS = Regex(
        "\\b(replay|re-?air|rerun|classic|encore|highlights?|condensed|recap|tape[- ]delayed?|archive|full game replay|rewind|throwback|re-?broadcast)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val AROUND_WORDS = Regex(
        "\\b(pre-?game|post-?game|pre-?match|post-?match|countdown|kick-?off show|game ?day live|halftime show|gameday|" +
            "previews?|breakdown|analysis|reaction|studio)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val VERSUS_TOKENS = setOf("vs", "v", "at", "versus", "x", "@")

    /** Tokens of text with "@" kept as its own token, so "Celtics @ Lakers" reads as a versus. */
    private fun words(s: String): List<String> = GameChannelResolver.tokensKeepingAt(s)

    private fun teamRuns(game: EspnScoreboardClient.Score) =
        GameChannelResolver.teamTokens(game.nameA, game.abbrA) to GameChannelResolver.teamTokens(game.nameB, game.abbrB)

    private fun positions(hay: List<String>, teams: List<List<String>>): List<IntRange> =
        teams.mapNotNull { run -> GameChannelResolver.indexOfRun(hay, run)?.let { it until it + run.size } }

    /** A "vs"/"at"/"@" token sitting between the two teams' mentions, in either order. */
    private fun hasVersusBetween(hay: List<String>, a: List<IntRange>, b: List<IntRange>): Boolean {
        for (x in a) for (y in b) {
            val (first, second) = if (x.first <= y.first) x to y else y to x
            if (first.last + 1 > second.first) continue
            if ((first.last + 1 until second.first).any { hay[it] in VERSUS_TOKENS }) return true
        }
        return false
    }

    /** Score for one listing: 0 means it has nothing to do with the game. */
    fun score(game: EspnScoreboardClient.Score, l: Listing, nowMs: Long): Pair<Int, String> {
        val (teamsA, teamsB) = teamRuns(game)
        if (teamsA.isEmpty() || teamsB.isEmpty()) return 0 to ""
        val title = words(l.title)
        val description = words(l.description.orEmpty().take(DESCRIPTION_CHARS))

        val aTitle = positions(title, teamsA); val bTitle = positions(title, teamsB)
        val aDesc = positions(description, teamsA); val bDesc = positions(description, teamsB)

        var score: Int
        var reason: String
        when {
            aTitle.isNotEmpty() && bTitle.isNotEmpty() -> {
                score = BOTH_IN_TITLE; reason = "both teams in the title"
                if (hasVersusBetween(title, aTitle, bTitle)) { score += VERSUS; reason += " with a vs/at" }
            }
            (aTitle.isNotEmpty() || aDesc.isNotEmpty()) && (bTitle.isNotEmpty() || bDesc.isNotEmpty()) -> {
                score = BOTH_IN_DESCRIPTION; reason = "both teams in the listing"
                if (aTitle.isNotEmpty() || bTitle.isNotEmpty()) score += ONE_IN_TITLE
            }
            else -> return 0 to ""
        }

        val replay = REPLAY_WORDS.containsMatchIn(l.title) || REPLAY_WORDS.containsMatchIn(l.description.orEmpty().take(DESCRIPTION_CHARS))
        val live = game.state == "in"
        val fits = timeFits(game, l, nowMs)
        if (fits) { score += TIME_FITS; reason += ", at game time" }
        else if (game.startMs > 0 || live) { score += TIME_FAR; reason += ", but at another time" }
        if (replay) { score += REPLAY; reason += ", looks like a replay" }
        if (AROUND_WORDS.containsMatchIn(l.title)) { score += SHOW_AROUND; reason += ", a pre/post-game show" }
        return score.coerceAtLeast(0) to reason
    }

    /**
     * Whether the listing's time fits the game. A live game fits a listing running now; an upcoming or finished
     * one fits a listing starting within 45 minutes of the scheduled start.
     */
    private fun timeFits(game: EspnScoreboardClient.Score, l: Listing, nowMs: Long): Boolean = when {
        game.state == "in" -> l.startMs <= nowMs + 30 * MINUTE && l.stopMs >= nowMs - HOUR
        game.startMs > 0 -> kotlin.math.abs(l.startMs - game.startMs) <= 45 * MINUTE
        else -> false
    }

    /** The best-scoring listing per channel, strongest first, only those that count as "on the guide". */
    fun rank(game: EspnScoreboardClient.Score, listings: List<Listing>, nowMs: Long): List<Hit> =
        listings.mapNotNull { l ->
            val (s, why) = score(game, l, nowMs)
            if (s >= ON_GUIDE) Hit(l.streamId, s, why) else null
        }.groupBy { it.streamId }.map { (_, hits) -> hits.maxBy { it.score } }.sortedByDescending { it.score }
}
