package com.carteltv.tv.data
import com.carteltv.tv.data.espn.LeagueCatalog

import com.carteltv.tv.data.debrid.DebridClient
import com.carteltv.tv.data.debrid.TorboxClient
import com.carteltv.tv.data.espn.EspnScoreboardClient
import com.carteltv.tv.data.trakt.TraktClient
import com.carteltv.tv.data.youtube.YouTubeApiClient
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the YouTube, ESPN and Trakt response contracts. */
class ServiceContractsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun youtube_picks_60fps_hw_codec_over_av1_and_respects_cap() {
        val candidates = listOf(
            YouTubeApiClient.FormatCandidate("399", "https://v/av1", "video/mp4; codecs=\"av01.0.09M.08\"", 1080, 60, 4_000_000),
            YouTubeApiClient.FormatCandidate("303", "https://v/vp9", "video/webm; codecs=\"vp9\"", 1080, 60, 3_500_000),
            YouTubeApiClient.FormatCandidate("299", "https://v/avc", "video/mp4; codecs=\"avc1.64002A\"", 1080, 60, 3_200_000),
            YouTubeApiClient.FormatCandidate("298", "https://v/720", "video/mp4; codecs=\"avc1.640020\"", 720, 60, 1_800_000),
            YouTubeApiClient.FormatCandidate("140", "https://a/aac", "audio/mp4; codecs=\"mp4a.40.2\"", 0, 0, 128_000),
            YouTubeApiClient.FormatCandidate("251", "https://a/opus", "audio/webm; codecs=\"opus\"", 0, 0, 160_000),
        )

        val best1080 = YouTubeApiClient.pickBestVideo(candidates, 2160)
        assertNotNull(best1080)
        assertEquals("299", best1080!!.itag)

        val capped720 = YouTubeApiClient.pickBestVideo(candidates, 720)
        assertNotNull(capped720)
        assertEquals("298", capped720!!.itag)

        assertNull(YouTubeApiClient.pickBestVideo(candidates, 0))

        val bestAudio = YouTubeApiClient.pickBestAudio(candidates)
        assertNotNull(bestAudio)
        assertEquals("140", bestAudio!!.itag)
    }

    @Test
    fun trakt_watched_parsers_unwrap_nested_movie_and_show_envelopes() {
        val c = TraktClient("cid", "tok", "sec")
        val moviesRaw = """[{"plays":2,"last_watched_at":"2026-11-20T00:00:00Z","movie":{"title":"Heat","year":1995}}]"""
        val movies = c.parseWatchedMovies(moviesRaw)
        assertEquals(1, movies.size)
        assertEquals("Heat", movies[0].title)
        assertEquals(1995, movies[0].year)
        assertEquals(2, movies[0].plays)

        val showsRaw = """[{"plays":5,"show":{"title":"The Wire","year":2002},"seasons":[{"number":1,"episodes":[{"number":1,"plays":1}]}]}]"""
        val shows = c.parseWatchedShows(showsRaw)
        assertEquals(1, shows.size)
        assertEquals("The Wire", shows[0].title)
        assertEquals(1, shows[0].seasons.size)
        assertEquals(1, shows[0].seasons[0].episodes[0].number)
    }

    @Test
    fun debrid_and_torbox_user_premium_flags_match_real_api_fields() {
        val rd = json.decodeFromString<DebridClient.RdUser>("""{"username":"u","type":"premium","premium":86400}""")
        assertTrue(rd.isPremium)
        val rdFree = json.decodeFromString<DebridClient.RdUser>("""{"username":"u","type":"free","premium":0}""")
        assertFalse(rdFree.isPremium)

        val tb = json.decodeFromString<TorboxClient.TbUser>("""{"email":"a@b.c","is_subscribed":true,"plan":2}""")
        assertTrue(tb.isPremium)
        val tbFree = json.decodeFromString<TorboxClient.TbUser>("""{"email":"a@b.c","is_subscribed":false,"plan":0}""")
        assertFalse(tbFree.isPremium)
    }

    @Test
    fun espn_path_resolver_maps_labels_and_preserves_raw_paths() {
        assertEquals("basketball/nba", LeagueCatalog.pathFor("NBA"))
        assertEquals("football/nfl", LeagueCatalog.pathFor("nfl"))
        assertEquals("soccer/eng.1", LeagueCatalog.pathFor("PremierLeague"))
        assertEquals("soccer/eng.1", LeagueCatalog.pathFor("soccer/eng.1"))
    }
}
