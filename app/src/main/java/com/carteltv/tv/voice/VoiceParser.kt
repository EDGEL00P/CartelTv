package com.carteltv.tv.voice

enum class Section { HOME, APPS, LIVE, GUIDE, SPORTS, MOVIES, TV, YOUTUBE, RECENTS, SEARCH, SETTINGS }
enum class Transport { PLAY, PAUSE, STOP, NEXT, PREVIOUS }

sealed interface VolumeChange {
    data object Up : VolumeChange
    data object Down : VolumeChange
    data object Mute : VolumeChange
    data object Unmute : VolumeChange
    data class Set(val percent: Int) : VolumeChange
}

sealed interface VoiceIntent {
    data class OpenSection(val section: Section) : VoiceIntent
    data class OpenApp(val query: String) : VoiceIntent
    data class WatchChannel(val query: String) : VoiceIntent
    data class Search(val query: String) : VoiceIntent
    data class Media(val action: Transport) : VoiceIntent
    data class Volume(val change: VolumeChange) : VoiceIntent
    /** [minutes] null cancels the timer. */
    data class Sleep(val minutes: Int?) : VoiceIntent
    data object Back : VoiceIntent
    /** Opens the phone-companion QR overlay. */
    data object ShowCompanion : VoiceIntent
    /** Several commands in one breath: "open youtube and volume down". */
    data class Sequence(val intents: List<VoiceIntent>) : VoiceIntent
    data class Unknown(val heard: String) : VoiceIntent
}

/** Turns a spoken or typed phrase into one [VoiceIntent]. Pure, so it runs the same for the mic, the phone and Assistant handoff. */
object VoiceParser {

    private val leadIn = Regex("^(?:(?:hey|ok|okay) )?(?:cartel(?: tv)? )?(?:(?:please|can you|could you|would you) )*")

    private val sections: Map<String, Section> = mapOf(
        "apps" to Section.APPS, "all apps" to Section.APPS, "my apps" to Section.APPS,
        "live tv" to Section.LIVE, "live television" to Section.LIVE, "live" to Section.LIVE, "tv" to Section.LIVE,
        "tv guide" to Section.GUIDE, "guide" to Section.GUIDE, "program guide" to Section.GUIDE, "epg" to Section.GUIDE,
        "sports" to Section.SPORTS, "sport" to Section.SPORTS, "scores" to Section.SPORTS,
        "movies" to Section.MOVIES, "shows" to Section.MOVIES, "movies and shows" to Section.MOVIES,
        "movies shows" to Section.MOVIES, "tv shows" to Section.TV, "series" to Section.TV,
        "youtube" to Section.YOUTUBE, "you tube" to Section.YOUTUBE,
        "recents" to Section.RECENTS, "recent" to Section.RECENTS, "recent apps" to Section.RECENTS,
        "search" to Section.SEARCH,
        "settings" to Section.SETTINGS, "setup" to Section.SETTINGS, "preferences" to Section.SETTINGS,
    )

    internal val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
        "forty five" to 45, "sixty" to 60, "ninety" to 90,
    )

    fun normalize(raw: String): String {
        val flat = raw.lowercase().replace(Regex("[^a-z0-9% ]"), " ").replace(Regex("\\s+"), " ").trim()
        return flat.replace(leadIn, "").trim().removeSuffix(" please").trim()
    }

    /** Chained-command separator: "open netflix and volume down", "search dune then sleep 30". */
    private val clauseSplit = Regex("\\b(?:and then|and|then)\\b")

    fun parse(raw: String): VoiceIntent {
        val clauses = clauseSplit.split(normalize(raw)).map { it.trim() }.filter { it.isNotEmpty() }
        if (clauses.size > 1) {
            val intents = clauses.map { parseOne(it, raw) }.filter { it !is VoiceIntent.Unknown }
            if (intents.size > 1) return VoiceIntent.Sequence(intents)
        }
        return parseOne(normalize(raw), raw)
    }

    private fun parseOne(t: String, raw: String): VoiceIntent {
        if (t.isEmpty()) return VoiceIntent.Unknown(raw.trim())

        if (Regex("^(?:cancel|stop|clear|turn off|remove) (?:the )?sleep(?: timer)?$").matches(t)) return VoiceIntent.Sleep(null)
        sleepMinutes(t)?.let { return VoiceIntent.Sleep(it) }

        if (t == "back" || t == "go back" || t == "previous screen") return VoiceIntent.Back
        if (t in setOf("home", "go home", "go to home", "home screen", "take me home", "show home", "show me home")) {
            return VoiceIntent.OpenSection(Section.HOME)
        }

        volume(t)?.let { return VoiceIntent.Volume(it) }
        transport(t)?.let { return VoiceIntent.Media(it) }

        if (Regex("^(?:connect|pair|link) (?:my |the )?phone$|^phone(?: remote| companion)?$|^(?:show|open|get) (?:me )?(?:the )?(?:qr(?: code)?|phone(?: remote| companion)?|companion)$").matches(t)) {
            return VoiceIntent.ShowCompanion
        }

        Regex("^(?:search(?: for)?|find|look up|look for|play) (.+)$").find(t)?.let { return VoiceIntent.Search(it.groupValues[1]) }
        Regex("^(?:watch|tune (?:in )?to|switch to|put on|change to|go to channel|channel) (.+)$").find(t)?.let {
            return VoiceIntent.WatchChannel(it.groupValues[1])
        }

        // Bare channel number: "42", "channel seven" already matched above, so this is the digits/number-word alone.
        (t.toIntOrNull() ?: numberWords[t])?.let { return VoiceIntent.WatchChannel(t) }

        val verbMatch = Regex("^(open|go to|show me|show|take me to|launch|start|run|exit|close|quit|leave) (?:the )?(.+)$").find(t)
        val verb = verbMatch?.groupValues?.get(1)
        val target = (verbMatch?.groupValues?.get(2) ?: t).removePrefix("the ").trim()

        // "open home", "exit home": exit-style verbs mean navigation, not app launch.
        val exitVerb = verb in setOf("exit", "close", "quit", "leave")
        if (exitVerb && (target == "home" || target == "this")) return VoiceIntent.OpenSection(Section.HOME)

        sections[target]?.let { return if (exitVerb) VoiceIntent.OpenSection(Section.HOME) else VoiceIntent.OpenSection(it) }
        // Fuzzy section catch: "open youtub", "go to tv gide" still land on the right tab.
        if (target.length >= 4 && !exitVerb) {
            VoiceMatch.best(target, sections.keys.toList())?.let {
                return VoiceIntent.OpenSection(sections.getValue(sections.keys.toList()[it]))
            }
        }
        return when (verb) {
            "open", "launch", "start", "run", "go to", "take me to" -> VoiceIntent.OpenApp(target)
            "show", "show me" -> VoiceIntent.Search(target)
            else -> VoiceIntent.Unknown(raw.trim())
        }
    }

    private fun sleepMinutes(t: String): Int? {
        if (Regex("^(?:sleep|turn off|shut down|go to sleep)(?: timer)?(?: in| for)? half an hour$").matches(t)) return 30
        val m = Regex("^(?:set (?:a )?)?(?:sleep|turn off|shut down|go to sleep)(?: timer)?(?: in| for)? (\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty five|sixty|ninety) (minutes?|mins?|hours?|hrs?)$").find(t)
            ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: numberWords[m.groupValues[1]] ?: return null
        val minutes = if (m.groupValues[2].startsWith("h")) n * 60 else n
        return minutes.coerceIn(1, 12 * 60)
    }

    private fun volume(t: String): VolumeChange? {
        Regex("^(?:set )?volume (?:to )?(\\d{1,3})(?: percent| %|%)?$").find(t)?.let {
            return VolumeChange.Set(it.groupValues[1].toInt().coerceIn(0, 100))
        }
        return when {
            t == "mute" || t == "mute it" || t == "mute the tv" -> VolumeChange.Mute
            t == "unmute" || t == "unmute it" -> VolumeChange.Unmute
            Regex("^(?:volume up|louder|turn it up|turn (?:the )?volume up|raise (?:the )?volume|increase (?:the )?volume)$").matches(t) -> VolumeChange.Up
            Regex("^(?:volume down|quieter|softer|turn it down|turn (?:the )?volume down|lower (?:the )?volume|decrease (?:the )?volume)$").matches(t) -> VolumeChange.Down
            else -> null
        }
    }

    private fun transport(t: String): Transport? = when (t) {
        "play", "resume", "continue", "unpause", "keep playing" -> Transport.PLAY
        "pause", "hold on", "pause it" -> Transport.PAUSE
        "stop", "stop playing", "stop it" -> Transport.STOP
        "next", "skip", "next episode", "next video", "next track", "skip this" -> Transport.NEXT
        "previous", "last episode", "previous episode", "previous track", "go previous" -> Transport.PREVIOUS
        else -> null
    }
}

/** Fuzzy name matching for apps and channels. */
object VoiceMatch {
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Index of the best candidate for [query], or null when nothing is close enough. */
    fun best(query: String, candidates: List<String>): Int? {
        val q = norm(query)
        if (q.isEmpty()) return null
        val qTokens = q.split(' ')
        var bestIdx = -1
        var bestScore = 0
        candidates.forEachIndexed { i, c ->
            val n = norm(c)
            if (n.isEmpty()) return@forEachIndexed
            val gap = minOf(kotlin.math.abs(n.length - q.length), 20)
            val score = when {
                n == q -> 100
                n.startsWith(q) -> 85 - gap
                q.startsWith(n) && n.length >= 4 -> 75 - gap
                n.contains(q) -> 65 - gap
                else -> {
                    // Whole-word order mismatch, e.g. "prime video" vs "video prime".
                    val tokens = n.split(' ').toSet()
                    val hits = qTokens.count { it in tokens }
                    val tokenScore = if (hits == qTokens.size && qTokens.isNotEmpty()) 60 else 0
                    // Spoken-word typos from the recognizer, e.g. "youtub" or "novab".
                    val edit = levenshtein(q, n)
                    val editScore = if (edit <= (n.length / 4).coerceAtLeast(2)) 70 - edit * 5 else 0
                    maxOf(tokenScore, editScore)
                }
            }
            if (score > bestScore) { bestScore = score; bestIdx = i }
        }
        return bestIdx.takeIf { bestScore >= 55 }
    }

    /** Small-DP edit distance; queries and labels are short so this is cheap. */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
