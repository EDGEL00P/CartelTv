package com.carteltv.tv.ui.player

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/** What to switch to when a title first starts. A null row means "leave what the player picked". */
internal data class TrackChoice(
    val audio: TrackRow? = null,
    val subtitle: TrackRow? = null,
    val subtitlesOff: Boolean = false,
)

private const val AUTO = "auto"
private const val OFF = "off"

private fun TrackRow.isForced() = "FORCED" in badges
private fun TrackRow.isExtra() = "COMMENTARY" in badges || "AD" in badges
private fun TrackRow.isSignsAndSongs() = detail.contains("sign", ignoreCase = true) || detail.contains("song", ignoreCase = true)

/**
 * Picks the audio and subtitle tracks the viewer would pick by hand.
 *
 * Audio: the preferred language (the device's when none is set), never commentary or audio description,
 * keeping the track already playing if it is in that language, else the fullest channel layout.
 * Subtitles ([subtitlePref] "auto"): off while the audio is in the viewer's language, a full-dialogue track in
 * that language when it is not. "off" never shows subtitles; a language code always shows that language.
 */
internal fun chooseTracks(
    audio: List<TrackRow>,
    subtitles: List<TrackRow>,
    audioPref: String,
    subtitlePref: String,
    deviceLanguage: String,
): TrackChoice {
    val wantedAudio = trackLanguage(audioPref.ifBlank { deviceLanguage })
    val candidates = audio.filter { !it.isOff && !it.isExtra() }
    val matching = candidates.filter { wantedAudio != null && trackLanguage(it.language) == wantedAudio }
    val chosenAudio = matching.firstOrNull { it.isSelected } ?: matching.maxByOrNull { it.channels }

    val pref = subtitlePref.trim().lowercase().ifEmpty { AUTO }
    if (pref == OFF) return TrackChoice(audio = chosenAudio, subtitlesOff = true)

    val playing = chosenAudio ?: audio.firstOrNull { it.isSelected }
    val audioLanguage = trackLanguage(playing?.language)
    val wantedSubs = if (pref == AUTO) wantedAudio else trackLanguage(pref)
    val inWanted = subtitles.filter { !it.isOff && wantedSubs != null && trackLanguage(it.language) == wantedSubs }
    val full = inWanted.filter { !it.isForced() && !it.isExtra() && !it.isSignsAndSongs() }
    val bestFull = full.minByOrNull { it.badges.size }

    return when {
        pref != AUTO -> TrackChoice(audio = chosenAudio, subtitle = bestFull, subtitlesOff = bestFull == null)
        audioLanguage == null -> TrackChoice(audio = chosenAudio)
        audioLanguage == wantedSubs -> {
            val forced = inWanted.firstOrNull { it.isForced() }
            TrackChoice(audio = chosenAudio, subtitle = forced, subtitlesOff = forced == null)
        }
        else -> {
            val fallback = inWanted.firstOrNull { it.isForced() }
            val pick = bestFull ?: fallback
            TrackChoice(audio = chosenAudio, subtitle = pick, subtitlesOff = pick == null)
        }
    }
}

internal fun applyTrackChoice(player: ExoPlayer, choice: TrackChoice) {
    choice.audio?.takeIf { !it.isSelected }?.let { selectTrack(player, TrackType.AUDIO, it) }
    when {
        choice.subtitle != null -> selectTrack(player, TrackType.SUBTITLE, choice.subtitle)
        choice.subtitlesOff -> player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }
}
