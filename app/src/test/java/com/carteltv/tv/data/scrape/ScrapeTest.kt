package com.carteltv.tv.data.scrape

import com.carteltv.tv.data.debrid.TorboxClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Fixtures follow the documented response shapes of each service; they are not live captures.
class ScrapeTest {
    private val h1 = "a".repeat(40)
    private val h2 = "b".repeat(40)
    private val h3 = "c".repeat(40)

    @Test
    fun yts_names_every_torrent_after_its_movie() {
        val body = """{"status":"ok","data":{"movie_count":1,"movies":[{"title":"Fight Club","year":1999,"torrents":[
            {"hash":"${h1.uppercase()}","quality":"1080p","type":"bluray","video_codec":"x264","seeds":120,"size_bytes":2147483648},
            {"hash":"bad","quality":"720p","type":"web","seeds":5,"size_bytes":1}]}]}}"""
        val out = YtsSource.parse(body)
        assertEquals(1, out.size)
        assertEquals(Release(h1, "Fight Club 1999 1080p bluray x264", 120, 2147483648L, "YTS"), out[0])
    }

    @Test
    fun yts_without_movies_is_empty() {
        assertTrue(YtsSource.parse("""{"status":"ok","data":{"movie_count":0}}""").isEmpty())
        assertTrue(YtsSource.parse("<html>blocked</html>").isEmpty())
    }

    @Test
    fun eztv_reads_string_numbers() {
        val body = """{"torrents_count":1,"torrents":[{"title":"Show S01E02 720p HDTV x264-GRP","hash":"$h2","seeds":9,"size_bytes":"734003200","season":"1","episode":"2"}]}"""
        assertEquals(listOf(Release(h2, "Show S01E02 720p HDTV x264-GRP", 9, 734003200L, "EZTV")), EztvSource.parse(body))
    }

    @Test
    fun piratebay_drops_the_no_results_row() {
        val body = """[{"id":"0","name":"No results returned","info_hash":"0000000000000000000000000000000000000000","seeders":"0","size":"0"}]"""
        assertTrue(PirateBaySource.parse(body).isEmpty())
        val real = """[{"id":"1","name":"Fight.Club.1999.1080p.BluRay.x264","info_hash":"$h3","seeders":"44","size":"2000000000"}]"""
        assertEquals(44, PirateBaySource.parse(real).single().seeders)
    }

    @Test
    fun doh_answer_keeps_only_a_records() {
        val body = """{"Status":0,"Answer":[{"name":"x","type":5,"data":"alias.example."},{"name":"x","type":1,"data":"104.21.5.9"},{"name":"x","type":28,"data":"2606::1"}]}"""
        assertEquals(listOf("104.21.5.9"), DohDns.parseAnswers(body))
        assertTrue(DohDns.parseAnswers("""{"Status":3}""").isEmpty())
    }

    private val movie = ScrapeRequest("tt0137523", "movie", "Fight Club", 1999, null, null)
    private val episode = ScrapeRequest("tt0944947", "series", "Show", null, 1, 2)

    @Test
    fun movie_candidates_reject_other_titles_and_keep_best_seeded_copy() {
        val found = listOf(
            Release(h1, "Fight Club 1999 1080p bluray x264", 10, 1, "YTS"),
            Release(h1, "Fight.Club.1999.1080p.BluRay.x264", 80, 1, "TPB"),
            Release(h2, "Fight Club 2 2005 720p", 99, 1, "TPB"),
        )
        val out = BuiltInSources.candidates(movie, found)
        assertEquals(listOf(h1), out.map { it.hash })
        assertEquals(80, out.single().seeders)
    }

    @Test
    fun series_candidates_need_the_exact_episode_not_a_pack() {
        val found = listOf(
            Release(h1, "Show S01E02 720p HDTV", 5, 1, "EZTV"),
            Release(h2, "Show S01E03 720p HDTV", 50, 1, "EZTV"),
            Release(h3, "Show Season 1 Complete 1080p", 70, 1, "TPB"),
        )
        assertEquals(listOf(h1), BuiltInSources.candidates(episode, found).map { it.hash })
    }

    private val ranked = listOf(
        Release(h1, "Fight Club 1999 720p", 500, 1_000_000_000L, "TPB"),
        Release(h2, "Fight Club 1999 1080p", 20, 2_147_483_648L, "YTS"),
    )

    @Test
    fun cached_results_lead_and_are_marked() {
        val out = BuiltInSources.toStreams(movie, ranked, cached = setOf(h2), keepUncached = true)
        assertEquals(listOf(h2, h1), out.map { it.infoHash })
        assertEquals("Fight Club (1999)", out[0].title)
        assertEquals(listOf("⚡ TorBox", "1080p", "2.0 GB", "👤 20"), out[0].badges)
        assertEquals("YTS", out[0].description)
        assertFalse(out[1].badges.any { it.startsWith("⚡") })
    }

    @Test
    fun badges_read_encode_audio_hdr_and_group_from_the_release_name() {
        val name = "Fight.Club.1999.2160p.BluRay.DV.HDR.x265.DTS-HD.MA.5.1-FraMeSToR"
        val r = Release(h1, name, 31, 62_000_000_000L, "TPB")
        assertEquals(listOf("2160p", "BluRay", "DV HDR", "HEVC", "DTS-HD", "57.7 GB", "👤 31"), BuiltInSources.badges(r, cached = false))
        assertEquals("FraMeSToR", ReleaseQuality.group(name))
        assertEquals(null, ReleaseQuality.group("Fight Club 1999 1080p"))
        assertEquals("DD+", ReleaseQuality.audioLabel("Show.S01E02.1080p.WEB-DL.DDP5.1.H.264-NTb"))
        assertEquals("x264", ReleaseQuality.codecLabel("Show.S01E02.1080p.WEB-DL.DDP5.1.H.264-NTb"))
    }

    @Test
    fun episode_rows_are_titled_with_the_episode() {
        assertEquals("Show S01E02", BuiltInSources.displayTitle(episode))
        assertEquals("Fight Club", BuiltInSources.displayTitle(movie.copy(year = null)))
    }

    @Test
    fun ranking_goes_resolution_then_source_then_hdr_then_seeders() {
        val names = listOf(
            "Fight Club 1999 720p BluRay" to 900,
            "Fight Club 1999 1080p WEBRip" to 10,
            "Fight Club 1999 1080p WEB-DL" to 10,
            "Fight Club 1999 1080p BluRay" to 10,
            "Fight Club 1999 1080p BluRay HDR" to 5,
            "Fight Club 1999 1080p Remux" to 1,
            "Fight Club 1999 2160p WEB-DL" to 1,
        )
        val releases = names.mapIndexed { i, (n, s) -> Release(i.toString().padStart(40, '0'), n, s, 1, "TPB") }
        val ranked = BuiltInSources.toStreams(movie, releases, cached = null, keepUncached = true).map { it.behaviorHints!!["filename"].toString().trim('"') }
        assertEquals(
            listOf(
                "Fight Club 1999 2160p WEB-DL",
                "Fight Club 1999 1080p Remux",
                "Fight Club 1999 1080p BluRay HDR",
                "Fight Club 1999 1080p BluRay",
                "Fight Club 1999 1080p WEB-DL",
                "Fight Club 1999 1080p WEBRip",
                "Fight Club 1999 720p BluRay",
            ),
            ranked,
        )
    }

    @Test
    fun cam_and_screener_copies_are_dropped_but_titles_containing_the_word_survive() {
        for (junk in listOf("Fight Club 1999 HDCAM x264", "Fight.Club.1999.TS.x264", "Fight Club 1999 TELESYNC", "Fight Club 1999 DVDScr")) {
            assertFalse(junk, BuiltInSources.accepts(movie, junk))
        }
        assertTrue(BuiltInSources.accepts(movie, "Fight Club 1999 1080p WEB-DL DDP5.1 x264"))
        val sample = movie.copy(title = "The Sample", year = 2010)
        assertTrue(BuiltInSources.accepts(sample, "The Sample 2010 1080p BluRay"))
        assertFalse(BuiltInSources.accepts(sample, "The Sample 2010 1080p BluRay SAMPLE"))
    }

    @Test
    fun movie_titles_match_exactly_including_numeric_and_numbered_titles() {
        val numeric = ScrapeRequest("tt8579674", "movie", "1917", 2019, null, null)
        assertTrue(BuiltInSources.accepts(numeric, "1917.2019.1080p.BluRay.x264"))
        assertFalse(BuiltInSources.accepts(numeric, "1917.1080p.Something.Else.2001"))
        val blade = ScrapeRequest("tt1856101", "movie", "Blade Runner 2049", 2017, null, null)
        assertTrue(BuiltInSources.accepts(blade, "Blade.Runner.2049.2017.2160p.WEB-DL"))
        assertFalse(BuiltInSources.accepts(blade, "Blade.Runner.1982.1080p.BluRay"))
        assertFalse(BuiltInSources.accepts(movie, "Fight Club 1999 Behind The Scenes"))
    }

    @Test
    fun episode_titles_accept_every_notation_of_the_right_episode_only() {
        for (ok in listOf("Show S01E02 720p HDTV", "Show.S01E02.1080p.WEB-DL", "Show S1E2 WEBRip", "Show 1x02 HDTV", "Show S01E01E02 720p")) {
            assertTrue(ok, BuiltInSources.accepts(episode, ok))
        }
        for (bad in listOf("Show S01E03 720p", "Show S02E02 720p", "Show S01 Complete 1080p", "Other Show S01E02 720p", "Show Extra S01E02 720p")) {
            assertFalse(bad, BuiltInSources.accepts(episode, bad))
        }
    }

    @Test
    fun torbox_only_hides_uncached() {
        val out = BuiltInSources.toStreams(movie, ranked, cached = setOf(h2), keepUncached = false)
        assertEquals(listOf(h2), out.map { it.infoHash })
    }

    @Test
    fun failed_cache_check_hides_nothing() {
        val out = BuiltInSources.toStreams(movie, ranked, cached = null, keepUncached = false)
        assertEquals(2, out.size)
    }

    @Test
    fun torbox_cache_reply_shapes() {
        assertEquals(setOf(h1), TorboxClient.parseCachedHashes("""{"success":true,"data":["${h1.uppercase()}"]}"""))
        assertEquals(setOf(h1), TorboxClient.parseCachedHashes("""{"success":true,"data":[{"hash":"$h1","name":"x"}]}"""))
        assertEquals(setOf(h2), TorboxClient.parseCachedHashes("""{"success":true,"data":{"$h2":{"name":"x"}}}"""))
        assertEquals(emptySet<String>(), TorboxClient.parseCachedHashes("""{"success":true,"data":null}"""))
        assertNull(TorboxClient.parseCachedHashes("""{"success":false,"error":"BAD"}"""))
        assertNull(TorboxClient.parseCachedHashes("oops"))
    }
}
