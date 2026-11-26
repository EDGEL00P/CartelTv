package com.carteltv.tv.data.stremio

import com.carteltv.tv.data.preferences.StreamPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker's rule set: which rows survive a filter, and what order the rest take. */
class StreamOrderingTest {

    private fun stream(
        title: String,
        url: String? = "https://example.test/video",
        hash: String? = null,
        addon: String? = "A",
        cached: Boolean = false,
    ) = StremioClient.StreamTuple(title = title, url = url, infoHash = hash, addon = addon, cached = cached)

    @Test fun `resolution filter keeps only the named resolution`() {
        val rows = listOf(stream("Movie.2160p.WEB-DL"), stream("Movie.1080p.WEB-DL"), stream("Movie.SD"))
        val out = StreamOrdering.apply(rows, StreamOrdering.Sort.BEST, StreamOrdering.Filters(resolutions = setOf("1080p")))
        assertEquals(listOf("Movie.1080p.WEB-DL"), out.map { it.title })
    }

    @Test fun `cached filter keeps cached rows only`() {
        val out = StreamOrdering.apply(
            listOf(stream("cached.1080p", cached = true), stream("cold.1080p")),
            StreamOrdering.Sort.ORIGINAL,
            StreamOrdering.Filters(cachedOnly = true),
        )
        assertEquals(listOf("cached.1080p"), out.map { it.title })
    }

    @Test fun `hdr filter keeps hdr and dolby vision`() {
        val out = StreamOrdering.apply(
            listOf(stream("a.2160p.DV"), stream("b.1080p"), stream("c.1080p.HDR10")),
            StreamOrdering.Sort.ORIGINAL,
            StreamOrdering.Filters(hdrOnly = true),
        )
        assertEquals(listOf("a.2160p.DV", "c.1080p.HDR10"), out.map { it.title })
    }

    @Test fun `hidden sources and rows with no playable target drop`() {
        val out = StreamOrdering.apply(
            listOf(stream("a.1080p", addon = "Torrentio"), stream("b.1080p", addon = "Other"), stream("c.1080p", url = null)),
            StreamOrdering.Sort.ORIGINAL,
            StreamOrdering.Filters(hiddenSources = setOf("Torrentio")),
        )
        assertEquals(listOf("b.1080p"), out.map { it.title })
    }

    @Test fun `regex keeps matches only`() {
        val out = StreamOrdering.apply(
            listOf(stream("Show.S01E02.1080p.GROUP"), stream("Show.S01E02.2160p.OTHER")),
            StreamOrdering.Sort.ORIGINAL,
            StreamOrdering.Filters(regex = "group"),
        )
        assertEquals(listOf("Show.S01E02.1080p.GROUP"), out.map { it.title })
    }

    @Test fun `best order puts cached then resolution first`() {
        val out = StreamOrdering.apply(
            listOf(stream("a.720p"), stream("b.2160p"), stream("c.1080p", cached = true)),
            StreamOrdering.Sort.BEST,
        )
        assertEquals(listOf("c.1080p", "b.2160p", "a.720p"), out.map { it.title })
    }

    @Test fun `size and seeders read from the row text`() {
        val small = stream("a.1080p 700 MB 👤 4")
        val big = stream("b.1080p 4.2 GB 👤 40")
        assertEquals(listOf("b.1080p 4.2 GB 👤 40", "a.1080p 700 MB 👤 4"), StreamOrdering.apply(listOf(small, big), StreamOrdering.Sort.SIZE).map { it.title })
        assertEquals(listOf("b.1080p 4.2 GB 👤 40", "a.1080p 700 MB 👤 4"), StreamOrdering.apply(listOf(small, big), StreamOrdering.Sort.SEEDERS).map { it.title })
    }

    @Test fun `tags carry parsed quality and cached`() {
        val tags = StreamOrdering.tags(stream("Movie.2160p.WEB-DL.DV.Atmos-GRP 12.5 GB", cached = true))
        assertTrue("⚡ Cached" in tags)
        assertTrue("2160p" in tags)
        assertTrue("DV" in tags)
        assertTrue("Atmos" in tags)
    }

    @Test fun `auto pick honours the filters`() {
        val rows = listOf(stream("a.2160p"), stream("b.1080p"))
        assertEquals("b.1080p", StreamOrdering.autoPick(rows, StreamOrdering.Filters(resolutions = setOf("1080p")))?.title)
        assertNull(StreamOrdering.autoPick(emptyList(), StreamOrdering.Filters()))
    }

    @Test fun `preference sort mapping covers every mode`() {
        StreamPreferences.Sort.entries.forEach { StreamOrdering.sortOf(it) }
        assertFalse(StreamOrdering.Sort.entries.isEmpty())
    }
}
