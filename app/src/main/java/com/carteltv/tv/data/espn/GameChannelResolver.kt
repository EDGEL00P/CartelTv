package com.carteltv.tv.data.espn

import com.carteltv.tv.data.xtream.XtreamClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Finds the live channel that carries a game: broadcast network first, then event channels named
 * after the teams, then guide listings that mention the game. Pure logic, no Android types.
 */
object GameChannelResolver {

    data class Match(val stream: XtreamClient.LiveStream, val score: Int, val reason: String)

    /** Networks that never exist as an IPTV channel, so matching on them would only produce noise. */
    private val STREAMING_ONLY = setOf(
        "peacock", "prime video", "amazon prime video", "apple tv", "apple tv+", "espn+", "espn plus",
        "paramount+", "mlb tv", "mlb.tv", "netflix", "youtube", "max", "hulu", "dazn", "victory+", "fubo",
        // Streaming services and team apps in the other regions and in ESPN's local listings.
        "kayo", "kayo sports", "stan sport", "binge", "optus sport", "spark sport", "sky sport now", "sky sports+",
        "itvx", "bbc iplayer", "iplayer", "discovery+", "fubo sports", "tubi", "nfl+", "nba league pass", "nhl.tv",
        "espn select", "blues app", "thunder app", "jazz+", "mammoth+", "suns live", "pelicans.com", "bn+",
        "9now", "7plus", "10 play", "sbs on demand", "tvnz+", "threenow", "neon", "crave", "dazn canada",
        // Conference and league streaming services ESPN lists beside the TV channels.
        "b1g+", "secn+", "accnx", "nba app", "flosports", "rugbypass", "roku", "fandango", "nchchockey.com",
    )

    private val ALIASES: Map<String, List<String>> = mapOf(
        "fs1" to listOf("fox sports 1"),
        "fs2" to listOf("fox sports 2"),
        "nfln" to listOf("nfl network"),
        "nhln" to listOf("nhl network"),
        "mlbn" to listOf("mlb network"),
        "cbssn" to listOf("cbs sports network"),
        "btn" to listOf("big ten network"),
        "secn" to listOf("sec network"),
        "accn" to listOf("acc network"),
        "espnu" to listOf("espn u"),
        "usa net" to listOf("usa network"),
        "tudn" to listOf("tudn"),
        "nbcsn" to listOf("nbc sports network"),
        "sportsnet" to listOf("sportsnet one", "sportsnet 360"),
        "sky sports main event" to listOf("sky sports main event"),
        "tnt sports 1" to listOf("tnt sports 1", "bt sport 1"),
        "tnt sports 2" to listOf("tnt sports 2", "bt sport 2"),
        "abc" to listOf("abc"),
        // Short codes the NHL's own feed uses for its broadcasters.
        "sn" to listOf("sportsnet", "sportsnet one", "sportsnet 360"),
        "sn360" to listOf("sportsnet 360"),
        "sn1" to listOf("sportsnet one"),
        "tvas" to listOf("tva sports"),
        "tvas2" to listOf("tva sports 2"),
        "rds" to listOf("rds"),
        "cbc" to listOf("cbc"),
        "tsn1" to listOf("tsn 1"), "tsn2" to listOf("tsn 2"), "tsn3" to listOf("tsn 3"), "tsn4" to listOf("tsn 4"),
        "tsn5" to listOf("tsn 5"),
        // Spellings ESPN's US listings use.
        "nhl net" to listOf("nhl network"),
        "usa net" to listOf("usa network"),
        "spectrum sports net" to listOf("spectrum sportsnet"),
        "nbc sports phil" to listOf("nbc sports philadelphia"),
        "nbc sports ba" to listOf("nbc sports bay area"),
        "msgb" to listOf("msg"), "msg2" to listOf("msg"), "msgsn" to listOf("msg"),
        "chsn" to listOf("chicago sports network"),
        "tele" to listOf("telemundo"),
        "midco sports net" to listOf("midco sports"),
        "cbssn" to listOf("cbs sports network"),
        "espn deportes" to listOf("espn deportes"),
        "bein sports" to listOf("bein sports", "bein sports xtra"),
        "nbc sports regional" to listOf("nbc sports"),
        "scripps sports" to listOf("scripps sports", "ion"),
    )

    /** Words that make a channel a poor guess for "the channel that usually carries this league". */
    private val NOT_A_GAME_CHANNEL = setOf("news", "deportes", "espanol", "weather", "movies", "kids", "music", "radio")

    /** "ESPN+" must not become "espn": the plus is the difference between a TV channel and a streaming service. */
    private fun isStreamingOnly(network: String): Boolean {
        val raw = network.lowercase().trim()
        if (raw in STREAMING_ONLY) return true
        // Names with a "+" or "." are matched exactly above; reading "ESPN+" as the token "espn" would hide the
        // ESPN channel. Plain names also match when followed by more words: "DAZN (YES)", "Prime Video (Local)".
        val key = tokens(network).joinToString(" ")
        return STREAMING_ONLY.any { s ->
            if ('+' in s || '.' in s) return@any false
            val sk = tokens(s).joinToString(" ")
            key == sk || key.startsWith("$sk ")
        }
    }

    private val QUALITY_WORDS = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "h264", "raw", "50fps", "60fps", "1080p", "720p", "backup", "vip",
    )
    private val COUNTRY_WORDS = setOf(
        "us", "usa", "uk", "gb", "ca", "can", "au", "aus", "de", "fr", "es", "it", "mx", "br", "nl", "pt", "tr", "ar", "ie",
    )
    private val TEAM_FILLER = setOf("fc", "cf", "sc", "the", "afc", "club", "de", "la", "el")

    // Compiled once: tokenizing runs for every channel in the list, so a pattern built per call is millions of objects.
    private val NOT_ALNUM_OR_AT = Regex("[^a-z0-9@ ]")

    // One pass, same result as lowercasing and splitting on everything outside a-z0-9.
    private fun tokens(s: String): List<String> {
        val low = s.lowercase()
        val out = ArrayList<String>(6)
        var start = -1
        for (i in low.indices) {
            val c = low[i]
            if (c in 'a'..'z' || c in '0'..'9') { if (start < 0) start = i }
            else if (start >= 0) { out.add(low.substring(start, i)); start = -1 }
        }
        if (start >= 0) out.add(low.substring(start))
        return out
    }

    private fun core(s: String): List<String> =
        tokens(s).filterNot { it in QUALITY_WORDS || it in COUNTRY_WORDS }

    /** Like [tokens] but "@" survives as a token, for reading "Celtics @ Lakers". */
    internal fun tokensKeepingAt(s: String): List<String> =
        s.lowercase().replace('+', ' ').replace("@", " @ ").replace(NOT_ALNUM_OR_AT, " ")
            .split(' ').filter { it.isNotEmpty() }

    /** Index of the first place [needle] occurs as a run of whole tokens in [hay], or null. */
    internal fun indexOfRun(hay: List<String>, needle: List<String>): Int? {
        if (needle.isEmpty() || needle.size > hay.size) return null
        for (i in 0..hay.size - needle.size) {
            if (hay.subList(i, i + needle.size) == needle) return i
        }
        return null
    }

    private fun containsRun(hay: List<String>, needle: List<String>): Boolean = indexOfRun(hay, needle) != null

    /**
     * Words to look the game up by in the guide: one distinctive word per team (the nickname, else the
     * abbreviation). The guide search needs every word to be present, and listings usually say "Lakers at
     * Celtics", not "Los Angeles Lakers at Boston Celtics", so full names would miss them.
     */
    fun searchTerms(game: EspnScoreboardClient.Score): List<String> =
        listOf(game.nameA to game.abbrA, game.nameB to game.abbrB).mapNotNull { (full, abbr) ->
            full?.let { core(it).filterNot { w -> w in TEAM_FILLER }.lastOrNull()?.takeIf { w -> w.length >= 3 } }
                ?: abbr?.let { tokens(it).singleOrNull()?.takeIf { w -> w.length >= 3 } }
        }.distinct()

    /** The key a viewer's channel choice is remembered under: the network when known, else the game. */
    fun pickKey(game: EspnScoreboardClient.Score): String =
        game.network?.takeIf { it.isNotBlank() }?.let { "net:" + tokens(it).joinToString(" ") }
            ?: "game:" + game.name.lowercase()

    fun qualityBonus(name: String): Int {
        val t = tokens(name).toSet()
        return when {
            "4k" in t || "uhd" in t -> 8
            "fhd" in t || "1080p" in t -> 6
            "hd" in t -> 4
            "sd" in t || "480p" in t -> -4
            else -> 0
        }
    }

    private fun countryBonus(name: String, countries: List<String>): Int {
        if (countries.isEmpty()) return 0
        val t = tokens(name).toSet()
        val wanted = countries.map { it.lowercase() }.toSet()
        val aliases = mapOf("us" to setOf("us", "usa"), "uk" to setOf("uk", "gb"), "ca" to setOf("ca", "can"))
        return if (wanted.any { w -> (aliases[w] ?: setOf(w)).any { it in t } }) 15 else 0
    }

    internal fun teamTokens(full: String?, abbr: String?): List<List<String>> {
        val names = mutableListOf<List<String>>()
        full?.let { n -> core(n).filterNot { it in TEAM_FILLER }.takeIf { it.isNotEmpty() }?.let(names::add) }
        // The last word is the distinctive part ("Lakers", "Celtics"); an abbreviation of 3+ letters also works.
        full?.let { n -> core(n).lastOrNull()?.takeIf { it.length >= 4 }?.let { names.add(listOf(it)) } }
        abbr?.let { a -> tokens(a).singleOrNull()?.takeIf { it.length >= 3 }?.let { names.add(listOf(it)) } }
        return names.distinct()
    }

    /**
     * Ranks channels for [game]. [epgStreamIds] are streams whose guide lists the game right now;
     * [pickedStreamId] is a channel the viewer chose before and always ranks first.
     */
    fun rank(
        game: EspnScoreboardClient.Score,
        streams: List<XtreamClient.LiveStream>,
        preferredCountries: List<String> = emptyList(),
        preferQuality: Boolean = true,
        epgStreamIds: Set<Long> = emptySet(),
        pickedStreamId: Long? = null,
        guideHits: List<GuideGameMatcher.Hit> = emptyList(),
        leagueLabel: String? = null,
    ): List<Match> {
        // Channels that usually carry this league: the fallback when the feed names no broadcaster (ESPN gives
        // none for AFL, NRL, rugby, cricket, UK football ...). They are candidates, never certainties.
        val typicalVariants = BroadcastNetworks.forLeague(leagueLabel).map { it to tokens(it) }
        val guideByStream = guideHits.associateBy { it.streamId }
        // Every broadcaster any source lists. Each is expanded through the alias table, and keeps its own
        // spelling so the result can say which network matched.
        val networkVariants: List<Pair<String, List<String>>> = (listOfNotNull(game.network) + game.networks)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .filterNot(::isStreamingOnly)
            .flatMap { net ->
                val key = tokens(net).joinToString(" ")
                (listOf(key) + (ALIASES[key] ?: emptyList())).map { net to tokens(it) }
            }
        val network = game.network?.takeIf { it.isNotBlank() }
        val teamsA = teamTokens(game.nameA, game.abbrA)
        val teamsB = teamTokens(game.nameB, game.abbrB)

        // Trimmed once here, not once per channel.
        val netTrimmed = networkVariants.map { (n, v) -> n to v.filterNot { it in COUNTRY_WORDS } }.filter { it.second.isNotEmpty() }
        val typicalTrimmed = typicalVariants.map { (n, v) -> n to v.filterNot { it in COUNTRY_WORDS } }.filter { it.second.isNotEmpty() }

        val out = ArrayList<Match>()
        for (s in streams) {
            if (s.name.isBlank()) continue
            val chCore = core(s.name)
            var best = 0
            var reason = ""

            if (s.streamId == pickedStreamId) { best = 1000; reason = "your pick" }

            for ((netName, vc) in netTrimmed) {
                val score = when {
                    chCore == vc -> 100
                    containsRun(chCore, vc) -> 82 - 6 * (chCore.size - vc.size).coerceAtMost(6)
                    else -> 0
                }
                if (score > best) { best = score; reason = netName }
            }

            if (best < 70 && typicalVariants.isNotEmpty() && chCore.none { it in NOT_A_GAME_CHANNEL }) {
                for ((typicalName, vc) in typicalTrimmed) {
                    val score = when {
                        chCore == vc -> 70
                        containsRun(chCore, vc) -> 58 - 10 * (chCore.size - vc.size).coerceAtMost(5)
                        else -> 0
                    }
                    if (score > best) { best = score; reason = "usually on $typicalName" }
                }
            }

            val both = teamsA.any { containsRun(chCore, it) } && teamsB.any { containsRun(chCore, it) }
            val one = teamsA.any { containsRun(chCore, it) } || teamsB.any { containsRun(chCore, it) }
            if (both && 120 > best) { best = 120; reason = "event channel" }
            else if (one && best == 0 && chCore.size <= 5) { best = 60; reason = "team channel" }

            if (s.streamId in epgStreamIds && 110 > best) { best = 110; reason = "on the guide now" }
            // A scored guide listing: 90 for the weakest accepted one up to 130 for a certain one, so a
            // strong listing outranks an event channel that merely carries a team's name.
            guideByStream[s.streamId]?.let { hit ->
                val guideScore = 90 + (hit.score - GuideGameMatcher.ON_GUIDE).coerceIn(0, 40)
                if (guideScore > best) { best = guideScore; reason = "guide: ${hit.reason}" }
            }

            if (best <= 0) continue
            var total = best + countryBonus(s.name, preferredCountries)
            if (preferQuality) total += qualityBonus(s.name)
            out += Match(s, total, reason)
        }
        return out.sortedWith(compareByDescending<Match> { it.score }.thenBy { it.stream.name.length }).take(8)
    }

    /* ── remembered picks (JSON object: pickKey → streamId) ── */

    fun savedPick(raw: String, game: EspnScoreboardClient.Score): Long? = runCatching {
        Json.parseToJsonElement(raw).jsonObject[pickKey(game)]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }.getOrNull()

    fun withPick(raw: String, game: EspnScoreboardClient.Score, streamId: Long): String {
        val cur = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val next = LinkedHashMap(cur)
        next[pickKey(game)] = JsonPrimitive(streamId.toString())
        // Keep the ledger bounded: the oldest picks fall off first.
        while (next.size > 60) next.remove(next.keys.first())
        return JsonObject(next).toString()
    }
}
