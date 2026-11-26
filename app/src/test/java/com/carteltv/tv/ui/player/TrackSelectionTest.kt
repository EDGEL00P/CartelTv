package com.carteltv.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTracksTest {
    private fun audio(lang: String, channels: Int = 2, selected: Boolean = false, vararg badges: String) =
        TrackRow("a $lang", "", badges.toList(), selected, 0, 0, language = lang, channels = channels)

    private fun sub(lang: String, vararg badges: String, detail: String = "") =
        TrackRow("s $lang", detail, badges.toList(), false, 1, 0, language = lang)

    @Test
    fun audio_in_the_viewers_language_wins_and_prefers_more_channels() {
        val rows = listOf(audio("fra", 6), audio("eng", 2), audio("eng", 6), audio("eng", 6, false, "COMMENTARY"))
        val choice = chooseTracks(rows, emptyList(), "", "auto", "en")
        assertEquals(6, choice.audio?.channels)
        assertEquals("eng", choice.audio?.language)
        assertTrue("COMMENTARY" !in choice.audio!!.badges)
    }

    @Test
    fun the_track_already_playing_is_kept_when_it_is_in_the_right_language() {
        val playing = audio("eng", 2, selected = true)
        val choice = chooseTracks(listOf(playing, audio("eng", 6)), emptyList(), "eng", "auto", "fr")
        assertEquals(playing, choice.audio)
    }

    @Test
    fun auto_subtitles_stay_off_when_the_audio_is_understood() {
        val choice = chooseTracks(listOf(audio("eng", 6, true)), listOf(sub("eng")), "", "auto", "en")
        assertNull(choice.subtitle)
        assertTrue(choice.subtitlesOff)
    }

    @Test
    fun auto_subtitles_turn_on_for_foreign_audio_using_full_dialogue_not_forced_or_signs() {
        val subs = listOf(
            sub("eng", "FORCED"),
            sub("eng", detail = "Signs & Songs"),
            sub("eng", "SDH", "CC"),
            sub("eng"),
        )
        val choice = chooseTracks(listOf(audio("jpn", 2, true)), subs, "", "auto", "en")
        assertEquals(emptyList<String>(), choice.subtitle?.badges)
        assertEquals("", choice.subtitle?.detail)
    }

    @Test
    fun forced_only_track_is_used_when_nothing_fuller_exists() {
        val choice = chooseTracks(listOf(audio("jpn", 2, true)), listOf(sub("eng", "FORCED")), "", "auto", "en")
        assertEquals(listOf("FORCED"), choice.subtitle?.badges)
    }

    @Test
    fun forced_narrative_shows_while_the_audio_is_understood() {
        val choice = chooseTracks(listOf(audio("eng", 6, true)), listOf(sub("eng", "FORCED"), sub("eng")), "", "auto", "en")
        assertEquals(listOf("FORCED"), choice.subtitle?.badges)
    }

    @Test
    fun off_never_shows_subtitles_and_a_language_always_does() {
        val rows = listOf(audio("jpn", 2, true))
        assertTrue(chooseTracks(rows, listOf(sub("eng")), "", "off", "en").subtitlesOff)
        val always = chooseTracks(listOf(audio("eng", 2, true)), listOf(sub("spa"), sub("eng")), "", "spa", "en")
        assertEquals("spa", always.subtitle?.language)
    }

    @Test
    fun nothing_matching_leaves_the_players_own_choice() {
        val choice = chooseTracks(listOf(audio("jpn", 2, true)), emptyList(), "", "auto", "en")
        assertNull(choice.audio)
        assertNull(choice.subtitle)
    }
}
