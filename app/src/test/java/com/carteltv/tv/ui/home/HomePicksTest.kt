package com.carteltv.tv.ui.home

import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.ui.hero.buildHeroItems
import org.junit.Assert.assertEquals
import org.junit.Test

/** What stands on Home once and never twice: the Up Next cards, and what the banner may pick. */
class HomePicksTest {

    private fun watched(title: String, ref: String = title, live: Boolean = false) = AppPreferences.RecentEntry(
        ref = if (live) "live:$ref" else "r:$ref",
        title = title,
        url = "https://example.com/$ref.mp4",
    )

    @Test
    fun up_next_keeps_one_card_per_show_and_the_episode_played_last_stands_for_it() {
        val recents = listOf(watched("The Bear S1 E3"), watched("Shogun S1 E5"), watched("The Bear S1 E2"))
        assertEquals(listOf("The Bear S1 E3", "Shogun S1 E5"), resumeRecents(recents, limit = 16).map { it.title })
    }

    @Test
    fun up_next_stops_at_the_row_s_limit() {
        val recents = (1..20).map { watched("Show $it") }
        assertEquals(16, resumeRecents(recents, limit = 16).size)
    }

    @Test
    fun a_live_channel_never_stands_on_up_next() {
        val recents = listOf(watched("ESPN", live = true), watched("Shogun S1 E5"))
        assertEquals(listOf("Shogun S1 E5"), resumeRecents(recents, limit = 16).map { it.title })
    }

    @Test
    fun the_same_title_counts_as_shown_however_it_is_spelled() {
        val rest = unseenTitles(listOf("The Bear!", "Shogun", "the bear"), shown = listOf("The Bear")) { it }
        assertEquals(listOf("Shogun"), rest)
    }

    @Test
    fun nothing_is_taken_out_when_nothing_has_been_shown() {
        val rest = unseenTitles(listOf("Shogun", "The Bear"), shown = emptyList()) { it }
        assertEquals(listOf("Shogun", "The Bear"), rest)
    }

    @Test
    fun the_banner_never_repeats_a_title_the_row_under_it_shows() {
        val hero = buildHeroItems(
            listOf(watched("Shogun S1 E5"), watched("The Bear S1 E3")),
            emptyList(), emptyList(), hour = 20, shown = setOf(titleKey("the BEAR!")),
        )
        assertEquals(listOf("Shogun"), hero.map { it.title })
    }

    @Test
    fun the_banner_shows_a_show_once_however_many_episodes_were_watched() {
        val hero = buildHeroItems(
            listOf(watched("The Bear S1 E3"), watched("The Bear S1 E2"), watched("Shogun S1 E5")),
            emptyList(), emptyList(), hour = 20,
        )
        assertEquals(listOf("The Bear", "Shogun"), hero.map { it.title })
    }

    @Test
    fun a_live_channel_is_never_a_banner_pick() {
        val hero = buildHeroItems(listOf(watched("ESPN", live = true), watched("Shogun S1 E5")), emptyList(), emptyList(), hour = 20)
        assertEquals(listOf("Shogun"), hero.map { it.title })
    }

    @Test
    fun an_event_takes_the_mark_of_its_own_league_however_either_is_cased() {
        val marks = mapOf("Formula 1" to "f1.png", "NFL" to "nfl.png")
        assertEquals("f1.png", leagueMark(marks, "formula 1"))
        assertEquals("nfl.png", leagueMark(marks, "NFL"))
    }

    @Test
    fun an_event_of_a_league_that_is_not_on_show_carries_none_rather_than_another_league_s() {
        assertEquals(null, leagueMark(mapOf("NFL" to "nfl.png", "AFL" to "afl.png"), "Formula 1"))
    }

    @Test
    fun a_movie_banner_carries_its_stream_as_the_muted_preview() {
        val client = XtreamClient("https://example.com", "user", "pass")
        val before = ProviderSession.current
        ProviderSession.current = client
        try {
            val hero = buildHeroItems(
                emptyList(), emptyList(),
                listOf(XtreamClient.VodStream(name = "Dune", streamId = 7)), hour = 20,
            )
            assertEquals(client.movieUrl(7, "mp4"), hero.single().previewUrl)
        } finally {
            ProviderSession.current = before
        }
    }

    @Test
    fun a_resume_banner_never_autoplays_mid_title() {
        val midTitle = watched("Shogun S1 E5").copy(positionMs = 20 * 60_000, durationMs = 60 * 60_000)
        val hero = buildHeroItems(listOf(midTitle), emptyList(), emptyList(), hour = 20)
        assertEquals(null, hero.single().previewUrl)
    }
}
