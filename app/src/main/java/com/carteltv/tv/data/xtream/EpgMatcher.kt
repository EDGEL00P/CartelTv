package com.carteltv.tv.data.xtream

import java.text.Normalizer

/**
 * Finds the guide channel for provider channels whose own `epg_channel_id` is missing or names nothing in the
 * guide file. A direct id that exists in the guide always wins and is never touched. The leftovers go through
 * these tiers, strongest evidence first, and the first tier that answers decides:
 *
 * 1. [Tier.OVERRIDE]   a viewer-supplied pairing (stream id to guide id), if that guide id exists.
 * 2. [Tier.ID_STEM]    the provider id and a guide id agree once country and quality tags are dropped.
 * 3. [Tier.EXACT_NAME] the cleaned channel name equals a guide display-name or id stem.
 * 4. [Tier.FUZZY]      the best-scoring guide channel by token overlap and trigram similarity, accepted only when
 *                      it clears [FUZZY_MIN], beats the runner-up by [FUZZY_MARGIN], and passes the guards below.
 *
 * Guards that apply to every name-based tier:
 * - Countries: refused when both sides name a country and they differ ("US|Discovery" never takes another
 *   country's schedule). With no country on the channel, a name must be unambiguous in the guide.
 * - Numbers: "TSN 1" is never "TSN 2", "CTV +1" is never "CTV". Number words count as digits ("BBC One" = "BBC 1").
 * - Time-zone feeds: "CTV East" is never "CTV West"; a name with no feed tag may take a tagged one only as a
 *   fuzzy match, and ambiguity (both East and West exist) is refused by the margin.
 */
object EpgMatcher {

    data class Channel(val streamId: Long, val name: String, val epgId: String?)

    data class MajorEventChannel(val id: String, val displayNames: List<String>)

    enum class Tier { OVERRIDE, ID_STEM, EXACT_NAME, FUZZY }

    /** A guide channel for a stream, how it was found, and (for [Tier.FUZZY]) how confident the score was. */
    data class Match(val epgId: String, val tier: Tier, val score: Double = 1.0)

    const val FUZZY_MIN = 0.86
    const val FUZZY_MARGIN = 0.07
    private const val QUALIFIER_PENALTY = 0.08
    private const val COUNTRY_BONUS = 0.04
    private const val MAX_CANDIDATES = 300
    private const val COMMON_TOKEN = 500

    private val COUNTRY_TOKEN = Regex("^[a-z]{2}\\d?$")
    private val QUALITY_TOKENS = setOf("hd", "sd", "fhd", "uhd", "4k")

    /** Words that carry no identity: "Discovery Channel" and "Discovery" are one name. */
    private val FILLER = setOf("tv", "television", "channel", "network", "the", "hd", "sd", "fhd", "uhd", "4k", "hevc")
    private val NUMBER_WORDS = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    )
    // Only words that never double as part of a channel's own name. "Central" ("Comedy Central"), "North" and
    // "South" are left out: reading them as feed tags would split a name from itself.
    private val FEED_WORDS = mapOf(
        "east" to "east", "eastern" to "east", "west" to "west", "western" to "west",
        "pacific" to "pacific", "atlantic" to "atlantic",
    )
    private val MARKS = Regex("\\p{Mn}+")
    private val PLUS_NUMBER = Regex("\\+\\s*(\\d)")
    private val LETTER_DIGIT = Regex("(?<=\\p{L})(?=\\d)|(?<=\\d)(?=\\p{L})")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val NUMERIC = Regex("^(?:\\d+|plus\\d+)$")

    /** Guide id without its country suffix and quality tokens: "cnn.hd.us2" and "cnn.us" both give "cnn". */
    internal fun stem(id: String): String {
        val parts = id.lowercase().trim().split('.').filter { it.isNotEmpty() }.toMutableList()
        if (parts.size > 1 && COUNTRY_TOKEN.matches(parts.last())) parts.removeAt(parts.lastIndex)
        return alnum(parts.filter { it !in QUALITY_TOKENS }.joinToString(""))
    }

    /** Two-letter country carried by a guide id's last dotted token, if any. */
    internal fun countryOfId(id: String): String? {
        val parts = id.lowercase().trim().split('.')
        val last = parts.lastOrNull() ?: return null
        return if (parts.size > 1 && COUNTRY_TOKEN.matches(last)) last.take(2) else null
    }

    internal fun nameKey(name: String): String = alnum(GuideModel.cleanName(name))

    private fun alnum(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun normCountry(c: String?): String? = when (val x = c?.lowercase()?.trim()) {
        null, "" -> null
        "gb" -> "uk"
        else -> x.take(2)
    }

    /**
     * Identity tokens of a channel name: accents folded, "&" read as "and", "+1" as "plus1", letters and digits
     * split apart ("TSN1" = "TSN 1"), number words as digits, filler words dropped.
     */
    internal fun tokens(name: String): List<String> {
        val folded = MARKS.replace(Normalizer.normalize(GuideModel.cleanName(name), Normalizer.Form.NFD), "")
            .lowercase().replace("&", " and ")
        // Split letters from digits first, so the "plus1" built next is not torn apart again.
        val split = LETTER_DIGIT.replace(folded, " ")
        val plus = PLUS_NUMBER.replace(split) { "plus${it.groupValues[1]}" }.replace("+", " plus ")
        return plus.split(NON_ALNUM)
            .filter { it.isNotEmpty() }
            .map { NUMBER_WORDS[it] ?: FEED_WORDS[it] ?: it }
            .filter { it !in FILLER }
    }

    private class Profile(val tokens: List<String>) {
        val feeds = tokens.filterTo(HashSet()) { it in FEED_WORDS.values }
        val numeric = tokens.filterTo(HashSet()) { NUMERIC.matches(it) }

        /** The name without feed tags: a feed tag is judged on its own, not as part of the name's similarity. */
        val core = tokens.filter { it !in FEED_WORDS.values }
        val set = core.toSet()
        val grams = trigrams(core.joinToString(" "))
    }

    private fun trigrams(s: String): Set<String> {
        val p = " $s "
        if (p.length < 3) return emptySet()
        return (0..p.length - 3).mapTo(HashSet()) { p.substring(it, it + 3) }
    }

    private fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return 2.0 * inter / (a.size + b.size)
    }

    /** Similarity of two name profiles in 0..1, or null when a guard forbids the pairing outright. */
    private fun similarity(a: Profile, b: Profile): Double? {
        if (a.set.isEmpty() || b.set.isEmpty()) return null
        if (a.numeric != b.numeric) return null
        var penalty = 0.0
        if (a.feeds != b.feeds) {
            if (a.feeds.isNotEmpty() && b.feeds.isNotEmpty()) return null
            penalty = QUALIFIER_PENALTY
        }
        val union = (a.set + b.set).size
        val jaccard = a.set.count { it in b.set }.toDouble() / union
        return 0.55 * jaccard + 0.45 * dice(a.grams, b.grams) - penalty
    }

    private class GuideEntry(val channel: MajorEventChannel, val country: String?, val profiles: List<Profile>)

    /** Stream id to guide channel id, for the channels that needed (and found) a match. */
    fun match(
        channels: List<Channel>,
        guide: List<MajorEventChannel>,
        overrides: Map<Long, String> = emptyMap(),
    ): Map<Long, String> = matchDetailed(channels, guide, overrides).mapValues { it.value.epgId.lowercase() }

    /** As [match], with the tier and score behind each pairing, for diagnostics. */
    fun matchDetailed(
        channels: List<Channel>,
        guide: List<MajorEventChannel>,
        overrides: Map<Long, String> = emptyMap(),
    ): Map<Long, Match> {
        if (channels.isEmpty() || guide.isEmpty()) return emptyMap()
        val guideIds = guide.mapTo(HashSet()) { it.id.lowercase() }
        val byLowerId = guide.associateBy { it.id.lowercase() }
        val byStem = HashMap<String, MutableList<MajorEventChannel>>()
        val byName = HashMap<String, MutableList<MajorEventChannel>>()
        for (g in guide) {
            byStem.getOrPut(stem(g.id)) { ArrayList() } += g
            val keys = (g.displayNames + g.id.substringBeforeLast('.', g.id)).map(::nameKey).filter { it.isNotEmpty() }.toSet()
            for (k in keys) byName.getOrPut(k) { ArrayList() } += g
        }
        val fuzzy by lazy(LazyThreadSafetyMode.NONE) { buildFuzzyIndex(guide) }

        fun resolve(ch: Channel): Match? {
            overrides[ch.streamId]?.lowercase()?.takeIf { it in guideIds }
                ?.let { return Match(byLowerId.getValue(it).id.lowercase(), Tier.OVERRIDE) }
            val own = ch.epgId?.trim().orEmpty()
            if (own.isNotEmpty() && own.lowercase() in guideIds) return null
            val country = normCountry(GuideModel.countryTag(ch.name))
            byIdStem(own, country, byStem)?.let { return Match(it.id.lowercase(), Tier.ID_STEM) }
            byDisplayName(ch.name, country, byName)?.let { return Match(it.id.lowercase(), Tier.EXACT_NAME) }
            return fuzzyMatch(ch.name, country, fuzzy)
        }

        val out = HashMap<Long, Match>()
        for (ch in channels) resolve(ch)?.let { out[ch.streamId] = it }
        return out
    }

    private class FuzzyIndex(val entries: List<GuideEntry>, val postings: Map<String, List<Int>>)

    private fun buildFuzzyIndex(guide: List<MajorEventChannel>): FuzzyIndex {
        val entries = guide.map { g ->
            val names = (g.displayNames + g.id.substringBeforeLast('.', g.id)).filter { it.isNotBlank() }
            GuideEntry(g, normCountry(countryOfId(g.id)), names.map { Profile(tokens(it)) }.filter { it.set.isNotEmpty() }.distinctBy { it.tokens })
        }
        val postings = HashMap<String, MutableList<Int>>()
        entries.forEachIndexed { i, e -> e.profiles.flatMap { it.set }.toSet().forEach { postings.getOrPut(it) { ArrayList() } += i } }
        return FuzzyIndex(entries, postings)
    }

    private fun fuzzyMatch(name: String, country: String?, index: FuzzyIndex): Match? {
        val query = Profile(tokens(name))
        if (query.set.isEmpty()) return null
        // Candidates share a token with the name; very common tokens ("sports", "news") only count when
        // the name has nothing rarer to go on.
        val rare = query.set.filter { (index.postings[it]?.size ?: 0) <= COMMON_TOKEN }
        val usable = rare.ifEmpty { query.set.toList() }
        val hits = HashMap<Int, Int>()
        for (t in usable) index.postings[t]?.forEach { hits.merge(it, 1, Int::plus) }
        val candidates = hits.entries.sortedByDescending { it.value }.take(MAX_CANDIDATES).map { it.key }

        var best: Pair<GuideEntry, Double>? = null
        var second = 0.0
        for (i in candidates) {
            val e = index.entries[i]
            if (country != null && e.country != null && e.country != country) continue
            val base = e.profiles.mapNotNull { similarity(query, it) }.maxOrNull() ?: continue
            val score = base + if (country != null && e.country == country) COUNTRY_BONUS else 0.0
            val b = best
            if (b == null || score > b.second) {
                if (b != null) second = maxOf(second, b.second)
                best = e to score
            } else second = maxOf(second, score)
        }
        val (entry, score) = best ?: return null
        if (score < FUZZY_MIN || score - second < FUZZY_MARGIN) return null
        return Match(entry.channel.id.lowercase(), Tier.FUZZY, score)
    }

    private fun pick(candidates: List<MajorEventChannel>?, country: String?): MajorEventChannel? {
        if (candidates.isNullOrEmpty()) return null
        val allowed = candidates.filter { c ->
            val g = normCountry(countryOfId(c.id))
            country == null || g == null || g == country
        }
        if (allowed.isEmpty()) return null
        if (country != null) {
            allowed.firstOrNull { normCountry(countryOfId(it.id)) == country }?.let { return it }
        }
        return if (allowed.size == 1) allowed[0] else if (country != null) allowed[0] else null
    }

    private fun byIdStem(epgId: String, country: String?, byStem: Map<String, List<MajorEventChannel>>): MajorEventChannel? {
        if (epgId.isEmpty()) return null
        val idCountry = normCountry(countryOfId(epgId)) ?: country
        return pick(byStem[stem(epgId)], idCountry)
    }

    private fun byDisplayName(name: String, country: String?, byName: Map<String, List<MajorEventChannel>>): MajorEventChannel? {
        val key = nameKey(name)
        if (key.isEmpty()) return null
        return pick(byName[key], country)
    }
}
