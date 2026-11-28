package com.carteltv.tv.data.discovery

import com.carteltv.tv.ui.player.timeLeftLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Fixtures follow the documented shapes of TMDB's /find and /tv/{id}/season/{n}; they are not live captures.
class TmdbTitlesTest {
    @Test
    fun movie_find_uses_tmdbs_title_year_and_original_name() {
        val body = """{"movie_results":[{"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-30"}],"tv_results":[]}"""
        assertEquals(TmdbTitles.Title("The Matrix", 1999, "The Matrix", 603), TmdbTitles.parseFind(body, series = false))
    }

    @Test
    fun series_find_reads_name_and_first_air_date() {
        val body = """{"movie_results":[],"tv_results":[{"id":1396,"name":"Breaking Bad","original_name":"Breaking Bad","first_air_date":"2008-01-20"}]}"""
        val t = TmdbTitles.parseFind(body, series = true)
        assertEquals("Breaking Bad", t?.name)
        assertEquals(2008, t?.year)
        assertEquals(1396, t?.tmdbId)
    }

    @Test
    fun a_movie_lookup_ignores_tv_hits_and_unreadable_bodies_give_nothing() {
        val tvOnly = """{"movie_results":[],"tv_results":[{"id":1,"name":"Show"}]}"""
        assertNull(TmdbTitles.parseFind(tvOnly, series = false))
        assertNull(TmdbTitles.parseFind("""{"status_code":7,"status_message":"Invalid API key"}""", series = false))
        assertNull(TmdbTitles.parseFind("oops", series = true))
    }

    @Test
    fun season_reply_maps_episode_numbers_to_their_names() {
        val body = """{"episodes":[{"episode_number":1,"name":"Pilot"},{"episode_number":2,"name":"Cat's in the Bag..."},{"episode_number":3,"name":""}]}"""
        assertEquals(mapOf(1 to "Pilot", 2 to "Cat's in the Bag..."), TmdbTitles.parseSeason(body))
        assertEquals(emptyMap<Int, String>(), TmdbTitles.parseSeason("""{"success":false}"""))
    }

    @Test
    fun time_left_reads_naturally() {
        assertEquals("1h 51m left", timeLeftLabel(111 * 60_000L))
        assertEquals("42m left", timeLeftLabel(42 * 60_000L))
        assertEquals("2h 0m left", timeLeftLabel(120 * 60_000L))
        assertEquals("Under a minute left", timeLeftLabel(20_000L))
        assertEquals("Finished", timeLeftLabel(0L))
    }
}
