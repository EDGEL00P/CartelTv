package com.carteltv.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import java.util.Locale

/** Which track family the picker is showing. Shared with the control bar in PlayerControlBar. */
enum class TrackType { AUDIO, SUBTITLE }

/** Everything the picker needs to draw one row, derived from a Media3 [Format]. */
internal data class TrackInfo(
    val title: String,
    val detail: String,
    val badges: List<String>,
)

internal data class TrackRow(
    val title: String,
    val detail: String,
    val badges: List<String>,
    val isSelected: Boolean,
    val groupIndex: Int,
    val trackIndex: Int,
    val isOff: Boolean = false,
    val language: String? = null,
    val channels: Int = 0,
)

/**
 * The row worth trying first: the viewer's language, skipping forced, commentary and
 * audio-description tracks, then the fullest channel layout (audio) or fewest extras (subtitles).
 * Null when nothing matches, so the picker never promotes a guess.
 */
internal fun recommendedRow(rows: List<TrackRow>, type: TrackType, deviceLanguage: String): TrackRow? {
    val wanted = trackLanguage(deviceLanguage) ?: return null
    return rows.asSequence()
        .filter { !it.isOff && trackLanguage(it.language) == wanted }
        .filter { r -> r.badges.none { it == "FORCED" || it == "COMMENTARY" || it == "AD" } }
        .maxWithOrNull(compareBy<TrackRow> { if (type == TrackType.SUBTITLE) -it.badges.size else it.channels })
}

private val languageNames = mapOf(
    "en" to "English", "eng" to "English",
    "es" to "Spanish", "spa" to "Spanish",
    "fr" to "French", "fra" to "French", "fre" to "French",
    "de" to "German", "deu" to "German", "ger" to "German",
    "it" to "Italian", "ita" to "Italian",
    "pt" to "Portuguese", "por" to "Portuguese",
    "nl" to "Dutch", "nld" to "Dutch", "dut" to "Dutch",
    "pl" to "Polish", "pol" to "Polish",
    "tr" to "Turkish", "tur" to "Turkish",
    "ar" to "Arabic", "ara" to "Arabic",
    "ru" to "Russian", "rus" to "Russian", "uk" to "Ukrainian", "ukr" to "Ukrainian",
    "el" to "Greek", "ell" to "Greek", "gre" to "Greek",
    "hi" to "Hindi", "hin" to "Hindi", "ur" to "Urdu", "urd" to "Urdu",
    "bn" to "Bengali", "ben" to "Bengali", "ta" to "Tamil", "tam" to "Tamil",
    "te" to "Telugu", "tel" to "Telugu",
    "zh" to "Chinese", "zho" to "Chinese", "chi" to "Chinese",
    "ja" to "Japanese", "jpn" to "Japanese",
    "ko" to "Korean", "kor" to "Korean",
    "sv" to "Swedish", "swe" to "Swedish", "no" to "Norwegian", "nor" to "Norwegian",
    "da" to "Danish", "dan" to "Danish", "fi" to "Finnish", "fin" to "Finnish",
    "cs" to "Czech", "ces" to "Czech", "sk" to "Slovak", "slk" to "Slovak",
    "hu" to "Hungarian", "hun" to "Hungarian", "ro" to "Romanian", "ron" to "Romanian",
    "bg" to "Bulgarian", "bul" to "Bulgarian", "hr" to "Croatian", "hrv" to "Croatian",
    "sr" to "Serbian", "srp" to "Serbian", "sl" to "Slovenian", "slv" to "Slovenian",
    "he" to "Hebrew", "heb" to "Hebrew", "fa" to "Persian", "fas" to "Persian",
    "th" to "Thai", "tha" to "Thai", "vi" to "Vietnamese", "vie" to "Vietnamese",
    "id" to "Indonesian", "ind" to "Indonesian", "ms" to "Malay", "msa" to "Malay",
    "tl" to "Filipino", "tgl" to "Filipino", "af" to "Afrikaans", "afr" to "Afrikaans",
)

/** Full language name for a Media3 language code, or null when the code is absent. */
internal fun trackLanguage(code: String?): String? {
    val raw = code?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val key = raw.lowercase(Locale.ROOT)
    languageNames[key]?.let { return it }
    val display = runCatching { Locale.forLanguageTag(key).getDisplayLanguage(Locale.ENGLISH) }.getOrNull()
    return display?.takeIf { it.isNotBlank() && !it.equals(key, ignoreCase = true) }
        ?: raw.uppercase(Locale.ROOT)
}

/** The format fields the picker reads, kept separate so naming can be tested without Media3/Android. */
internal data class TrackFacts(
    val language: String? = null,
    val label: String? = null,
    val codecs: String? = null,
    val sampleMimeType: String? = null,
    val roleFlags: Int = 0,
    val channelCount: Int = 0,
    val sampleRate: Int = 0,
)

internal fun describeTrack(format: Format, type: TrackType): TrackInfo = describeFacts(
    TrackFacts(
        language = format.language,
        label = format.label,
        codecs = format.codecs,
        sampleMimeType = format.sampleMimeType,
        roleFlags = format.roleFlags,
        channelCount = format.channelCount,
        sampleRate = format.sampleRate,
    ),
    type,
)

/** A readable row for a subtitle or audio format. Pure so it can be tested without a player. */
internal fun describeFacts(facts: TrackFacts, type: TrackType): TrackInfo {
    val language = trackLanguage(facts.language)
    val label = facts.label?.trim()?.takeIf { it.isNotBlank() }
    val distinctLabel = label?.takeIf { own -> language == null || !own.equals(language, ignoreCase = true) }
    val title = language
        ?: distinctLabel
        ?: if (type == TrackType.SUBTITLE) "Subtitles" else "Audio"

    val detail = if (type == TrackType.SUBTITLE) {
        listOfNotNull(distinctLabel, subtitleCodec(facts.codecs)).joinToString(" · ")
            .ifBlank { "Text track" }
    } else {
        val channels = when (val n = facts.channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            in 3..Int.MAX_VALUE -> "$n ch"
            else -> null
        }
        val rate = facts.sampleRate.takeIf { it > 0 }?.let { "${it / 1000} kHz" }
        listOfNotNull(distinctLabel, audioCodec(facts), channels, rate).joinToString(" · ")
            .ifBlank { "Audio track" }
    }
    return TrackInfo(title, detail, roleBadges(facts, type))
}

/** Off first (added by the caller), then a stable language order so feeds do not jump around. */
internal fun sortRows(rows: List<TrackRow>): List<TrackRow> =
    rows.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, TrackRow::title)
            .thenBy { it.badges.joinToString(",") }
            .thenBy { it.detail },
    )

private fun roleBadges(facts: TrackFacts, type: TrackType): List<String> {
    val roles = facts.roleFlags
    val badges = mutableListOf<String>()
    // Media3 1.11 has no forced role flag; providers put "Forced" in the track label.
    if (facts.label?.contains("forced", ignoreCase = true) == true) badges += "FORCED"
    if (roles and C.ROLE_FLAG_TRANSCRIBES_DIALOG != 0) badges += "SDH"
    else if (roles and C.ROLE_FLAG_CAPTION != 0) badges += "CC"
    if (type == TrackType.SUBTITLE && roles and C.ROLE_FLAG_EASY_TO_READ != 0) badges += "EASY READ"
    if (roles and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) badges += "AD"
    if (roles and C.ROLE_FLAG_COMMENTARY != 0) badges += "COMMENTARY"
    if (roles and C.ROLE_FLAG_DUB != 0) badges += "DUB"
    return badges.distinct()
}

private fun subtitleCodec(codecs: String?): String? {
    val c = codecs?.lowercase(Locale.ROOT)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        "subrip" in c || c == "srt" -> "SubRip"
        "vtt" in c -> "WebVTT"
        "ass" in c || "ssa" in c -> "SSA/ASS"
        "ttml" in c || "dfxp" in c || c.startsWith("stpp") -> "TTML"
        "cea-608" in c || "cea608" in c -> "CEA-608"
        "cea-708" in c || "cea708" in c -> "CEA-708"
        "tx3g" in c -> "Tx3g"
        else -> codecs.uppercase(Locale.ROOT).take(24)
    }
}

/** Compact codec name; tracks whose codec nothing recognises show the string as the container wrote it. */
private fun audioCodec(facts: TrackFacts): String? =
    audioCodecOf(facts.codecs, facts.sampleMimeType)?.short
        ?: facts.codecs?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)?.take(24)
