package com.carteltv.tv.data.stremio

import java.text.Normalizer
import kotlinx.serialization.json.JsonPrimitive

/**
 * Strict validation that a torrent release actually belongs to the requested
 * movie or episode.
 *
 * Ported from Cauldron's app/scrapers/title_matching.py (MIT,
 * github.com/wizaarddlabs/cauldron), with the defects found while porting fixed:
 *
 *  - the catalogue year is passed in explicitly instead of being re-derived
 *    from the title text, and only the first title-independent year token in
 *    the release is checked against it: "Blade Runner 2049" accepts its own
 *    2017 releases, and a request that carries a year rejects releases named
 *    with a different one;
 *  - the requested title is matched with its raw tokens, so a title that is
 *    only a number ("1917") or that contains a release vocabulary word
 *    ("A Complete Unknown", "Vision Quest") still matches;
 *  - multi-episode releases ("S01E01E02") pass for any episode they contain,
 *    and season packs pass because they contain the requested episode.
 *
 * Deliberately not ported: Cauldron's localized-release exception for
 * IMDb-ID-backed sources, which needs language detection and the source name.
 */

/** Resolution, source, codec, audio and flag vocabulary that may follow a title. */
private val RELEASE_NOISE = setOf(
    "2160p", "1080p", "720p", "576p", "480p", "360p", "240p",
    "4k", "uhd", "fhd", "hd",
    "web", "webrip", "webdl", "web-dl",
    "bluray", "brrip", "bdrip", "hdrip", "dvdrip", "hdtv", "remux",
    "proper", "repack", "extended", "uncut", "complete",
    "multi", "dual", "audio", "dubbed", "subbed", "subs",
    "x264", "x265", "h264", "h265", "hevc", "avc", "av1",
    "aac", "ac3", "ddp", "dd", "atmos", "dts", "truehd",
    "10bit", "8bit",
    "hdr", "hdr10", "hdr10+", "dolby", "vision", "dv",
)

/** Streaming-source and language tags ("amzn", "ita") treated as release metadata. */
private val SOURCE_TAGS = setOf(
    "amzn", "nf", "nfweb", "hmax", "max", "dsnp", "atvp", "pmtp", "cr", "bbc",
    "it", "us", "uk", "multi", "subs", "dub",
    "eng", "ger", "fre", "spa", "ita", "jpn", "kor", "chs", "cht",
)

private val SEPARATORS = Regex("""[._-]+""")
private val BRACKETS = Regex("""[\[\](){}]""")
private val WHITESPACE = Regex("""\s+""")
private val YEAR_TOKEN = Regex("""\d{4}""")
private val SHORT_NUMBER = Regex("""\d{1,2}""")
private val RESOLUTION = Regex("""\d{3,4}p""")
private val BIT_DEPTH = Regex("""\d{1,2}bit""")

// Season/episode notation: "s01e01", "s1e1", multi-episode "s01e01e02", "1x01".
private val SEASON_BLOCK = Regex("""\bs0*(\d{1,2})((?:e\d{1,3})+)""")
private val ALT_EPISODE = Regex("""\b0*(\d{1,2})x0*(\d{1,3})(?!\d)""")
private val SEASON_ONLY = Regex("""\bs0*(\d{1,2})(?![\de])""")
private val SEASON_WORD = Regex("""\bseasons?\s*0*(\d{1,2})\b""")
private val COMPLETE_PACK = Regex("""\bcomplete\b""")

/** Tokens that mark season/episode info once the show title has ended. */
private val SEASON_EPISODE_TOKEN = Regex("""s\d{1,2}(?:e\d{1,3})*""")
private val ALT_EPISODE_TOKEN = Regex("""\d{1,2}x\d{1,3}""")

private const val MAX_ALIASES = 10

/**
 * Whether [release] is a release of the requested movie. [year] is the
 * catalogue year, not something parsed back out of [requested]; pass null to
 * skip the year check entirely.
 */
fun matchesMovie(requested: String, release: String, year: Int? = null, aliases: List<String> = emptyList()): Boolean =
    candidates(requested, aliases).any { movieTitleMatches(it, release, year) }

/**
 * Whether [release] is a release of the requested series containing
 * [season]/[episode]. With either null, only the title is checked.
 */
fun matchesSeries(
    requested: String,
    release: String,
    season: Int? = null,
    episode: Int? = null,
    aliases: List<String> = emptyList(),
): Boolean {
    val got = tokens(release)
    if (got.isEmpty()) return false

    val titleMatches = candidates(requested, aliases).any { candidate ->
        val wanted = tokens(candidate)
        wanted.isNotEmpty() &&
            got.size >= wanted.size &&
            got.subList(0, wanted.size) == wanted &&
            seriesRestIsMetadata(got.subList(wanted.size, got.size))
    }
    if (!titleMatches) return false
    if (season == null || episode == null) return true
    return seriesContains(normalize(release), season, episode)
}

/**
 * Gate for Stremio stream tuples. A torrent stream (info hash present) whose
 * release name can be read — behaviorHints.filename, or a title/description
 * line shaped like a release name — must belong to the request, or the addon's
 * search surfaced unrelated junk. Decoration-only titles carry no release name
 * to judge, and direct streams without an info hash carry display titles
 * rather than release names, so both pass untouched.
 */
fun streamMatchesRequest(
    stream: StremioClient.StreamTuple,
    type: String?,
    requested: String,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    aliases: List<String> = emptyList(),
): Boolean {
    if (requested.isBlank() || stream.infoHash.isNullOrBlank()) return true
    val names = releaseNameCandidates(stream)
    if (names.isEmpty()) return true
    return when (type?.lowercase()) {
        "movie" -> names.any { matchesMovie(requested, it, year, aliases) }
        "series" -> names.any { matchesSeries(requested, it, season, episode, aliases) }
        else -> true
    }
}

/** The first 19xx/20xx year in [text]; release info reads like "2006" or "2006-2011". */
fun yearIn(text: String?): Int? = text?.let { FIRST_YEAR.find(it)?.value?.toIntOrNull() }

private val FIRST_YEAR = Regex("""(19|20)\d{2}""")

private fun releaseNameCandidates(stream: StremioClient.StreamTuple): List<String> {
    val filename = (stream.behaviorHints?.get("filename") as? JsonPrimitive)?.content
    val lines = buildList {
        filename?.let { add(it.substringBeforeLast('.')) }
        stream.title?.let { addAll(it.lines()) }
        stream.name?.let { add(it) }
        stream.description?.let { addAll(it.lines()) }
    }
    return lines.asSequence()
        .map { it.trim() }
        .filter { line ->
            line.firstOrNull()?.isLetterOrDigit() == true &&
                line.split(' ', '.').count { it.isNotBlank() } >= 2
        }
        .distinct()
        .toList()
}

private fun movieTitleMatches(requested: String, release: String, year: Int?): Boolean {
    val wanted = tokens(requested)
    val got = tokens(release)
    if (wanted.isEmpty() || got.isEmpty() || got.size < wanted.size) return false
    if (got.subList(0, wanted.size) != wanted) return false

    val rest = got.subList(wanted.size, got.size)
    if (rest.isEmpty()) return true

    // A leading short number is a sequel marker: "Cars 2" is not "Cars".
    if (SHORT_NUMBER.matches(rest.first())) return false

    // Numbers that belong to the title ("2049", "1917", "8") are not release
    // years when they reappear in the release.
    val titleNumbers = wanted.mapNotNull { it.toIntOrNull() }.toSet()
    var metadataSeen = false
    var yearChecked = false
    for (token in rest) {
        when {
            token in RELEASE_NOISE || token in SOURCE_TAGS -> metadataSeen = true
            RESOLUTION.matches(token) || BIT_DEPTH.matches(token) -> metadataSeen = true
            YEAR_TOKEN.matches(token) -> {
                val number = token.toInt()
                if (!yearChecked && number !in titleNumbers) {
                    // The release-year slot: the first four-digit token that is
                    // not part of the title.
                    yearChecked = true
                    if (year != null && number != year) return false
                }
                metadataSeen = true
            }
            metadataSeen -> Unit // release group and technical tags, tolerated after the metadata started
            else -> return false // an unknown word before any metadata: a different title
        }
    }
    return true
}

private fun seriesRestIsMetadata(rest: List<String>): Boolean {
    if (rest.isEmpty()) return true
    var metadataSeen = false
    for (token in rest) {
        when {
            token in RELEASE_NOISE || token in SOURCE_TAGS -> metadataSeen = true
            RESOLUTION.matches(token) || BIT_DEPTH.matches(token) -> metadataSeen = true
            YEAR_TOKEN.matches(token) -> metadataSeen = true
            SEASON_EPISODE_TOKEN.matches(token) || ALT_EPISODE_TOKEN.matches(token) -> metadataSeen = true
            token == "season" || token == "seasons" -> metadataSeen = true
            metadataSeen -> Unit
            else -> return false // an unknown word before any metadata: a different title
        }
    }
    return true
}

private fun seriesContains(release: String, season: Int, episode: Int): Boolean {
    for (match in SEASON_BLOCK.findAll(release)) {
        val markerSeason = match.groupValues[1].toIntOrNull() ?: continue
        if (markerSeason != season) continue
        val episodes = match.groupValues[2].split('e').mapNotNull(String::toIntOrNull)
        if (episode in episodes) return true
    }
    val byAltNotation = ALT_EPISODE.findAll(release).any {
        it.groupValues[1].toIntOrNull() == season && it.groupValues[2].toIntOrNull() == episode
    }
    if (byAltNotation) return true

    // Season packs contain every episode of their season.
    return SEASON_ONLY.findAll(release).any { it.groupValues[1].toIntOrNull() == season } ||
        SEASON_WORD.findAll(release).any { it.groupValues[1].toIntOrNull() == season } ||
        COMPLETE_PACK.containsMatchIn(release)
}

private fun normalize(value: String): String {
    val folded = Normalizer.normalize(value, Normalizer.Form.NFKD).filterNot { char ->
        val type = Character.getType(char)
        type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }
    return folded.lowercase()
        .replace(SEPARATORS, " ")
        .replace(BRACKETS, " ")
        .replace("&", " and ")
        .replace("'", "")
        .replace("\u2019", "")
        .replace(WHITESPACE, " ")
        .trim()
}

private fun tokens(value: String): List<String> = normalize(value).split(' ').filter { it.isNotEmpty() }

private fun candidates(requested: String, aliases: List<String>): List<String> {
    val titles = mutableListOf(requested.trim())
    for (alias in aliases) {
        val clean = alias.trim()
        if (clean.isNotEmpty() && clean !in titles) titles.add(clean)
        if (titles.size > MAX_ALIASES) break
    }
    return titles
}
