package com.carteltv.tv.data.stremio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Specification for torrent release validation. The numbered-sequel and
 * unknown-word rejections are the cases public indexes get wrong most often
 * ("Cars" must not surface "Cars 2" or "Counting Cars"); the year-only and
 * vocabulary-word titles are the false negatives the strict rule must not have.
 */
class ReleaseTitleMatchTest {

    @Test fun `a release naming the title and year passes`() {
        assertTrue(matchesMovie("Cars", "Cars 2006 1080p BluRay", year = 2006))
        assertTrue(matchesMovie("Cars", "Cars.2006.1080p.x264", year = 2006))
        assertTrue(matchesMovie("Cars", "Cars (2006) WEB-DL", year = 2006))
    }

    @Test fun `numbered sequels do not pass for the original`() {
        assertFalse(matchesMovie("Cars", "Cars 2 2011", year = 2006))
        assertFalse(matchesMovie("Cars", "Cars 3 2017 1080p", year = 2006))
    }

    @Test fun `titles that merely start with the request do not pass`() {
        assertFalse(matchesMovie("Cars", "Counting Cars 2018", year = 2006))
        assertFalse(matchesMovie("Cars", "Used Cars 2008", year = 2008))
        assertFalse(matchesMovie("Cars", "Cars on the Road 2022", year = 2022))
        assertFalse(matchesMovie("Cars", "Cars That Ate Paris 1976 1080p", year = 2006))
    }

    @Test fun `a number that belongs to the title is not a sequel marker`() {
        assertTrue(matchesMovie("Cars 3", "Cars 3 2017 1080p", year = 2017))
        assertTrue(matchesMovie("Ocean's 8", "Oceans.8.2018.1080p.BluRay", year = 2018))
        assertTrue(matchesMovie("Blade Runner 2049", "Blade Runner.2049.2017.1080p", year = 2017))
    }

    @Test fun `a release named with a different year than the catalogue rejects`() {
        assertFalse(matchesMovie("Blade Runner 2049", "Blade Runner.2049.1999.1080p", year = 2017))
        assertFalse(matchesMovie("Cars", "Cars 2011 1080p BluRay", year = 2006))
    }

    @Test fun `year-only and vocabulary-word titles match their own release`() {
        assertTrue(matchesMovie("1917", "1917 2019 1080p BluRay", year = 2019))
        assertTrue(matchesMovie("A Complete Unknown", "A.Complete.Unknown.2024.1080p.WEB-DL", year = 2024))
        assertTrue(matchesMovie("Vision Quest", "Vision.Quest.1985.1080p", year = 1985))
    }

    @Test fun `a yearless title skips the year check rather than guessing`() {
        assertTrue(matchesMovie("Blade Runner", "Blade Runner 2049 1080p"))
    }

    @Test fun `release group and source tags are tolerated once metadata has started`() {
        assertTrue(matchesMovie("Cars", "Cars 2006 1080p BluRay x264 groupName", year = 2006))
    }

    @Test fun `an alias can carry the match`() {
        assertTrue(matchesMovie("The Godfather", "Il Padrino 1972 1080p", year = 1972, aliases = listOf("Il Padrino")))
        assertFalse(matchesMovie("The Godfather", "Il Padrino 1972 1080p", year = 1972))
    }

    @Test fun `accents separators and ampersands do not matter`() {
        assertTrue(matchesMovie("Amélie", "Amelie.2001.1080p.BluRay", year = 2001))
        assertTrue(matchesMovie("Minions & Monsters", "Minions and Monsters 2022 1080p"))
    }

    @Test fun `the requested episode passes in every common notation`() {
        assertTrue(matchesSeries("Breaking Bad", "Breaking.Bad.S01E01.1080p.WEB-DL", season = 1, episode = 1))
        assertTrue(matchesSeries("Breaking Bad", "Breaking Bad 1x01 1080p", season = 1, episode = 1))
        assertTrue(matchesSeries("Breaking Bad", "Breaking.Bad.S1E1.720p", season = 1, episode = 1))
    }

    @Test fun `another episode of the same show does not pass`() {
        assertFalse(matchesSeries("Breaking Bad", "Breaking.Bad.S01E02.1080p", season = 1, episode = 1))
        assertFalse(matchesSeries("Breaking Bad", "Breaking.Bad.S02E01.1080p", season = 1, episode = 1))
    }

    @Test fun `multi-episode releases pass for any episode they contain`() {
        assertTrue(matchesSeries("Show", "Show.S01E01E02.1080p", season = 1, episode = 1))
        assertTrue(matchesSeries("Show", "Show.S01E01E02.1080p", season = 1, episode = 2))
        assertFalse(matchesSeries("Show", "Show.S01E01E02.1080p", season = 1, episode = 3))
    }

    @Test fun `season packs pass because they contain the episode`() {
        assertTrue(matchesSeries("Show", "Show.Season.1.1080p", season = 1, episode = 1))
        assertTrue(matchesSeries("Show", "Show.S01.Complete.720p", season = 1, episode = 5))
    }

    @Test fun `a movie or another show whose name starts the request does not pass`() {
        assertFalse(matchesSeries("Supergirl", "Supergirl.1984.1080p.BluRay", season = 1, episode = 1))
        assertFalse(matchesSeries("Monster", "Monarch.Legacy.of.Monsters.S01E01.1080p", season = 1, episode = 1))
        assertFalse(matchesSeries("Cars", "Counting.Cars.S01E01.1080p", season = 1, episode = 1))
    }

    @Test fun `without season or episode info only the title is checked`() {
        assertTrue(matchesSeries("Breaking Bad", "Breaking.Bad.S01E01.1080p"))
        assertTrue(matchesSeries("Breaking Bad", "Breaking Bad 1080p"))
    }

    @Test fun `torrent streams with a readable release name are gated on the request`() {
        assertTrue(
            streamMatchesRequest(
                tuple(title = "Torrentio\n4K\n👤 152\nCars.2006.2160p.BluRay.mkv"),
                type = "movie",
                requested = "Cars",
                year = 2006,
            )
        )
        assertFalse(
            streamMatchesRequest(
                tuple(title = "Torrentio\n4K\n👤 152\nCounting.Cars.2018.720p.mkv"),
                type = "movie",
                requested = "Cars",
                year = 2006,
            )
        )
    }

    @Test fun `a filename in behavior hints can carry the match`() {
        assertTrue(
            streamMatchesRequest(
                tuple(title = "Torrentio\n4K", filename = "The.Godfather.1972.1080p.BluRay.mkv"),
                type = "movie",
                requested = "The Godfather",
                year = 1972,
            )
        )
    }

    @Test fun `direct streams without an info hash pass untouched`() {
        assertTrue(
            streamMatchesRequest(
                tuple(title = "Cars • 4K • English", infoHash = null),
                type = "movie",
                requested = "Cars",
                year = 2006,
            )
        )
    }

    @Test fun `decoration-only titles with no readable release name are kept`() {
        assertTrue(
            streamMatchesRequest(
                tuple(title = "Torrentio\n4K\n👤 152"),
                type = "movie",
                requested = "Cars",
                year = 2006,
            )
        )
    }

    @Test fun `series streams are gated on season and episode`() {
        assertFalse(
            streamMatchesRequest(tuple(title = "Show.S01E02.1080p.mkv"), type = "series", requested = "Show", season = 1, episode = 1)
        )
        assertTrue(
            streamMatchesRequest(tuple(title = "Show.S01E01.1080p.mkv"), type = "series", requested = "Show", season = 1, episode = 1)
        )
        assertTrue(
            streamMatchesRequest(tuple(title = "Show.Season.1.Complete.720p.mkv"), type = "series", requested = "Show", season = 1, episode = 1)
        )
    }

    @Test fun `year parsing takes the first year in the release info`() {
        assertEquals(2006, yearIn("2006"))
        assertEquals(2006, yearIn("2006–2011"))
        assertEquals(null, yearIn(null))
        assertEquals(null, yearIn("Specials"))
    }

    @Test fun `cinemeta aliases accept strings objects and skip junk`() {
        val mixed = Json.parseToJsonElement(
            """["Il Padrino", {"title": "Der Pate"}, {"name": "El Padrino"}, "", {"bogus": 1}]""",
        )
        assertEquals(
            listOf("Il Padrino", "Der Pate", "El Padrino"),
            StremioClient.Metum(id = "tt0068646", aliases = mixed).titleAliases(),
        )
        assertEquals(emptyList<String>(), StremioClient.Metum(id = "tt0068646").titleAliases())
    }

    @Test fun `a localized torrent stream passes when an alias covers it`() {
        assertTrue(
            streamMatchesRequest(
                tuple(title = "Torrentio\n4K\n👤 152\nIl.Padrino.1972.1080p.BluRay.mkv"),
                type = "movie",
                requested = "The Godfather",
                year = 1972,
                aliases = listOf("Il Padrino"),
            )
        )
        assertFalse(
            streamMatchesRequest(
                tuple(title = "Torrentio\n4K\n👤 152\nIl.Padrino.1972.1080p.BluRay.mkv"),
                type = "movie",
                requested = "The Godfather",
                year = 1972,
            )
        )
    }

    private fun tuple(
        title: String? = null,
        infoHash: String? = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        filename: String? = null,
    ): StremioClient.StreamTuple = StremioClient.StreamTuple(
        title = title,
        url = null,
        infoHash = infoHash,
        sources = emptyList(),
        behaviorHints = filename?.let { buildJsonObject { put("filename", JsonPrimitive(it)) } },
        name = null,
        description = null,
    )
}
