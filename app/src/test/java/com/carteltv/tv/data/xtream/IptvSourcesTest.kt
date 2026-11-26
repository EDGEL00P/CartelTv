package com.carteltv.tv.data.xtream

import com.carteltv.tv.data.provider.Provider
import com.carteltv.tv.data.provider.ProviderFailover
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvSourcesTest {
    private fun parse(text: String) = M3uParser.parse(text.lineSequence())

    @Test fun m3uReadsChannelsAttributesAndHeaderGuides() {
        val p = parse(
            """
            #EXTM3U url-tvg="http://a/epg.xml, http://b/epg.xml.gz"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-logo="http://x/l.png" group-title="UK, News",BBC One
            http://host/live/1.m3u8
            #EXTINF:-1 group-title="Sport",Sky Sports
            #EXTVLCOPT:http-user-agent=x
            http://host/live/2.ts
            """.trimIndent(),
        )
        assertEquals(listOf("http://a/epg.xml", "http://b/epg.xml.gz"), p.guideUrls)
        assertEquals(2, p.entries.size)
        val bbc = p.entries[0]
        assertEquals("BBC One", bbc.name)
        assertEquals("bbc1.uk", bbc.tvgId)
        assertEquals("UK, News", bbc.group)
        assertEquals("http://host/live/1.m3u8", bbc.url)
        assertEquals("Sky Sports", p.entries[1].name)
    }

    @Test fun m3uSkipsBomBlankLinesAndOrphanUrls() {
        val p = parse("\uFEFF#EXTM3U\n\nhttp://orphan/1\n#EXTINF:-1,Only\r\nhttp://host/a\r\n")
        assertEquals(1, p.entries.size)
        assertEquals("Only", p.entries[0].name)
    }

    @Test fun m3uUsesExtgrpWhenNoGroupTitle() {
        val p = parse("#EXTM3U\n#EXTGRP:Movies\n#EXTINF:-1,Film\nhttp://h/x.mp4")
        assertEquals("Movies", p.entries[0].group)
    }

    @Test fun m3uTellsLiveMoviesAndSeriesApart() {
        fun kind(name: String, url: String) = M3uParser.kindOf(M3uParser.Entry(name, url))
        assertEquals(M3uParser.Kind.LIVE, kind("CNN", "http://h/live/u/p/1.m3u8"))
        assertEquals(M3uParser.Kind.LIVE, kind("CNN", "http://h/1234"))
        assertEquals(M3uParser.Kind.MOVIE, kind("Heat", "http://h/movie/u/p/9.mkv"))
        assertEquals(M3uParser.Kind.MOVIE, kind("Heat", "http://h/files/heat.mp4?x=1"))
        assertEquals(M3uParser.Kind.SERIES, kind("Show S01E02", "http://h/series/u/p/5.mkv"))
        assertEquals(M3uParser.Kind.SERIES, kind("Show S01E02", "http://h/files/a.mkv"))
    }

    @Test fun m3uSplitsEpisodeNames() {
        val (show, ep) = M3uParser.episodeOf(M3uParser.Entry("The Wire - S03E07 - Back Burners", "http://h/a.mkv"))!!
        assertEquals("The Wire", show)
        assertEquals(3, ep.season)
        assertEquals(7, ep.number)
        assertNull(M3uParser.episodeOf(M3uParser.Entry("Just A Film", "http://h/a.mkv")))
    }

    @Test fun m3uIdsAreStableNonNegativeAndDistinct() {
        val a = M3uParser.idOf("http://h/1")
        assertEquals(a, M3uParser.idOf("http://h/1"))
        assertNotEquals(a, M3uParser.idOf("http://h/2"))
        assertTrue(a >= 0)
    }

    @Test fun m3uFillsCatchupTemplates() {
        assertEquals("http://h/a?s=100&e=200", M3uParser.catchupUrl("http://h/a?s=\${start}&e=\${end}", "http://h/live", 100_000, 200_000))
        assertEquals("http://h/live?utc=100", M3uParser.catchupUrl("?utc={utc}", "http://h/live", 100_000, 200_000))
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun stalkerReadsTokenAndRefusals() {
        assertEquals("ABC", StalkerParser.token(StalkerParser.js("""{"js":{"token":"ABC"}}""", json)))
        assertNull(StalkerParser.js("Authorization failed.", json))
        assertNull(StalkerParser.profileError(StalkerParser.js("""{"js":{"id":"1","name":"x"}}""", json)))
        assertEquals("Blocked", StalkerParser.profileError(StalkerParser.js("""{"js":{"status":1,"msg":"Blocked"}}""", json)))
        assertTrue(StalkerParser.profileError(StalkerParser.js("""{"js":{"status":2}}""", json))!!.contains("username"))
    }

    @Test fun stalkerReadsGenresAndChannels() {
        val genres = StalkerParser.genres(StalkerParser.js("""{"js":[{"id":"*","title":"All"},{"id":"5","title":"News"}]}""", json))
        assertEquals(listOf("5" to "News"), genres.map { it.category_id to it.category_name })

        val ch = StalkerParser.channels(
            StalkerParser.js(
                """{"js":{"data":[
                    {"id":"31915","name":"BBC","number":"3","logo":"/logos/bbc.png","cmd":"ffrt http://localhost/ch/31915","tv_genre_id":"5","xmltv_id":"bbc.uk"},
                    {"id":"7","name":"Direct","cmd":"ffmpeg http://cdn.example.com/live/7.ts","use_http_tmp_link":"0"},
                    {"id":"","name":"Broken","cmd":"x"}
                ]}}""",
                json,
            ),
        )
        assertEquals(2, ch.size)
        assertEquals(31915L, ch[0].longId)
        assertEquals("bbc.uk", ch[0].xmltvId)
        assertTrue(StalkerParser.needsCreateLink(ch[0]))      // localhost link must be resolved by the portal
        assertFalse(StalkerParser.needsCreateLink(ch[1]))     // a real host plays as is
        assertEquals("http://cdn.example.com/live/7.ts", StalkerParser.playable(ch[1].cmd))
    }

    @Test fun stalkerReadsCreatedLinkAndLogoPaths() {
        assertEquals(
            "http://ip.tv:8000/u/p/47534?play_token=x",
            StalkerParser.linkFrom(StalkerParser.js("""{"js":{"id":"1","cmd":"ffmpeg http://ip.tv:8000/u/p/47534?play_token=x"}}""", json)),
        )
        assertNull(StalkerParser.linkFrom(StalkerParser.js("""{"js":{"cmd":""}}""", json)))
        assertEquals("http://p.tv/logos/a.png", StalkerParser.absolute("http://p.tv", "/logos/a.png"))
        assertEquals("http://x/a.png", StalkerParser.absolute("http://p.tv", "http://x/a.png"))
        assertNull(StalkerParser.absolute("http://p.tv", ""))
    }

    @Test fun stalkerBuildsEndpointCandidates() {
        assertEquals(
            listOf("http://h:80/portal.php", "http://h:80/server/load.php", "http://h:80/stalker_portal/server/load.php"),
            StalkerParser.endpoints("h:80/c/"),
        )
        assertEquals(listOf("http://h/x/portal.php"), StalkerParser.endpoints("http://h/x/portal.php"))
        assertEquals("http://h/stalker_portal/server/load.php", StalkerParser.endpoints("http://h/stalker_portal/c/")[0])
        assertTrue(StalkerParser.macValid("00:1A:79:12:34:56"))
        assertFalse(StalkerParser.macValid("00-1A-79-12-34-56"))
    }

    private fun cat(id: String, name: String = "G$id") = XtreamClient.Category(id, name)
    private fun stream(id: Long, cat: String?) = XtreamClient.LiveStream(streamId = id, name = "s$id", categoryId = cat)

    @Test fun groupsHideRenameAndOrder() {
        val groups = listOf(cat("1"), cat("2"), cat("3"), cat("4"))
        val cfg = ChannelGroupConfig(hidden = setOf("2"), names = mapOf("3" to "Sport"), order = listOf("4", "3"))
        val out = ChannelGroups.apply(groups, cfg)
        assertEquals(listOf("4", "3", "1"), out.map { it.category_id })
        assertEquals("Sport", out[1].category_name)
        assertEquals(groups, ChannelGroups.apply(groups, ChannelGroupConfig()))
    }

    @Test fun groupsHideTheirChannels() {
        val s = listOf(stream(1, "1"), stream(2, "2"), stream(3, null))
        assertEquals(listOf(1L, 3L), ChannelGroups.visible(s, ChannelGroupConfig(hidden = setOf("2"))).map { it.streamId })
    }

    @Test fun groupEditsToggleRenameAndMove() {
        var cfg = ChannelGroups.toggleHidden(ChannelGroupConfig(), "1")
        assertEquals(setOf("1"), cfg.hidden)
        assertTrue(ChannelGroups.toggleHidden(cfg, "1").hidden.isEmpty())

        cfg = ChannelGroups.rename(ChannelGroupConfig(), "1", "Old", "  New ")
        assertEquals("New", cfg.names["1"])
        assertTrue(ChannelGroups.rename(cfg, "1", "Old", "Old").names.isEmpty())
        assertTrue(ChannelGroups.rename(cfg, "1", "Old", "").names.isEmpty())

        val moved = ChannelGroups.move(ChannelGroupConfig(), listOf("a", "b", "c"), "c", -1)
        assertEquals(listOf("a", "c", "b"), moved.order)
        assertEquals(ChannelGroupConfig(), ChannelGroups.move(ChannelGroupConfig(), listOf("a", "b"), "a", -1)) // already first
    }

    @Test fun failoverTriesOthersInListOrder() {
        val all = listOf("a", "b", "c").map { Provider(it, it, "h", "u", "p") }
        assertEquals(listOf("a", "c"), ProviderFailover.candidates(all, "b").map { it.id })
    }
}
