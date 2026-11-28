package com.carteltv.tv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** What an Up Next card says: its title, the line beneath, and what the banner says under the same title. */
class UpNextLinesTest {
    private val film = TitleParts("The Bodyguard")

    @Test
    fun a_film_in_progress_says_continue_and_how_many_whole_minutes_are_left() {
        assertEquals("Continue \u00b7 41m left", resumeLine(film, positionMs = 10 * 60_000L, durationMs = 51 * 60_000L))
    }

    @Test
    fun a_resume_card_never_promises_less_than_a_minute() {
        assertEquals("Continue \u00b7 1m left", resumeLine(film, positionMs = 59_900L, durationMs = 60_000L))
    }

    @Test
    fun a_resume_card_only_says_continue_when_the_player_kept_no_duration() {
        assertEquals("Continue", resumeLine(film, positionMs = 0L, durationMs = 0L))
        assertEquals("Continue", resumeLine(film, positionMs = 90_000L, durationMs = -1L))
    }

    @Test
    fun an_episode_in_progress_names_its_season_and_episode() {
        val episode = splitTitle("My Name Is Earl S1 E2")
        assertEquals("Continue \u00b7 S1, E2", resumeLine(episode, positionMs = 0L, durationMs = 0L))
        assertEquals("Continue \u00b7 S1, E2 \u00b7 41m left", resumeLine(episode, positionMs = 10 * 60_000L, durationMs = 51 * 60_000L))
    }

    @Test
    fun a_title_keeps_its_show_name_and_loses_the_episode_tail_the_app_writes() {
        assertEquals(TitleParts("My Name Is Earl", 1, 2), splitTitle("My Name Is Earl S1 E2"))
        assertEquals(TitleParts("The Bear", 2, 3), splitTitle("The Bear S2 E3: Sundae"))
        assertEquals(TitleParts("The Bear", 2, 3), splitTitle("The Bear S2E3"))
        assertEquals(TitleParts("The Bear", 12, 103), splitTitle("The Bear S12 E103"))
        assertEquals(TitleParts("Shogun", 1, 5), splitTitle("Shogun S01E05"))
        assertEquals("S12, E103", splitTitle("The Bear S12 E103").episodeLabel)
    }

    @Test
    fun a_language_tag_in_front_of_a_title_is_dropped() {
        assertEquals(TitleParts("My Name Is Earl", 1, 2), splitTitle("EN My Name Is Earl S1 E2"))
        assertEquals(TitleParts("My Name Is Earl", 1, 2), splitTitle("EN  My Name Is Earl S1 E2"))
        assertEquals(TitleParts("El Camino", null, null), splitTitle("ES El Camino"))
    }

    @Test
    fun a_title_that_only_looks_tagged_is_left_alone() {
        assertEquals(TitleParts("IT Chapter Two", null, null), splitTitle("IT Chapter Two"))
        assertEquals(TitleParts("IT Crowd", null, null), splitTitle("IT Crowd"))
        assertEquals(TitleParts("NO TIME TO DIE", null, null), splitTitle("NO TIME TO DIE"))
        assertEquals(TitleParts("US Marshals", null, null), splitTitle("US Marshals"))
        assertEquals(TitleParts("S.W.A.T.", null, null), splitTitle("S.W.A.T."))
        assertEquals(TitleParts("Se7en", null, null), splitTitle("Se7en"))
        assertEquals(TitleParts("1917", null, null), splitTitle("1917"))
    }

    @Test
    fun the_banner_keeps_saying_what_is_left_of_a_film() {
        assertEquals("41m left \u00b7 19% watched", resumeDescription(film, positionMs = 10 * 60_000L, durationMs = 51 * 60_000L))
        assertEquals("Press OK to play.", resumeDescription(film, positionMs = 0L, durationMs = 0L))
    }

    @Test
    fun the_banner_names_the_episode_before_what_is_left() {
        assertEquals(
            "S1, E2 \u00b7 41m left \u00b7 19% watched",
            resumeDescription(
                splitTitle("EN My Name Is Earl S1 E2"),
                positionMs =
                    10 * 60_000L,
                durationMs = 51 * 60_000L,
            ),
        )
        assertEquals(
            "S1, E2 \u00b7 Press OK to play.",
            resumeDescription(splitTitle("My Name Is Earl S1 E2"), positionMs = 0L, durationMs = 0L),
        )
    }

    @Test
    fun a_card_from_another_app_names_the_row_the_system_filed_it_under() {
        assertEquals("Continue", watchNextLine(0))
        assertEquals("Next episode", watchNextLine(1))
        assertEquals("New", watchNextLine(2))
        assertEquals("Watchlist", watchNextLine(3))
    }

    @Test
    fun a_programme_with_no_known_type_still_reads_as_something_to_watch_next() {
        assertEquals("Up next", watchNextLine(Int.MAX_VALUE))
        assertEquals("Up next", watchNextLine(-1))
    }
}
