package com.carteltv.tv.data.xtream

import com.carteltv.tv.data.xtream.EpgMatcher.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgSourcesTest {

    private val provider = "http://prov.example/xmltv.php?username=u&password=p"

    @Test fun theProviderGuideComesFirstThenTheExtras() {
        val s = EpgSync.sources(provider, listOf("https://a.example/a.xml.gz", "https://b.example/b.xml.gz"))
        assertEquals(listOf(provider, "https://a.example/a.xml.gz", "https://b.example/b.xml.gz"), s)
    }

    @Test fun invalidAndDuplicateLinksAreDropped() {
        val s = EpgSync.sources(
            provider,
            listOf("ftp://x/y", "", "  ", "not a url", "HTTPS://A.example/a.xml.gz", "https://a.example/a.xml.gz", provider.uppercase()),
        )
        assertEquals(listOf(provider, "HTTPS://A.example/a.xml.gz"), s)
    }

    @Test fun neverMoreThanTheLimit() {
        val extras = (1..20).map { "https://e$it.example/g.xml.gz" }
        assertEquals(EpgSync.MAX_SOURCES, EpgSync.sources(provider, extras).size)
        assertEquals(provider, EpgSync.sources(provider, extras).first())
    }

    @Test fun anInvalidPrimaryStillLetsTheExtrasRun() {
        assertEquals(listOf("https://a.example/a.xml.gz"), EpgSync.sources("", listOf("https://a.example/a.xml.gz")))
    }

    private fun ch(id: Long, name: String, epg: String? = null) = Channel(id, name, epg)

    @Test fun pendingSkipsChannelsAnEarlierGuideAlreadyServes() {
        val own = listOf(
            ch(1, "CNN", "cnn.us"),        // its id is declared by guide 1: served directly
            ch(2, "TSN 1", "tsn1.ca"),     // not declared anywhere yet
            ch(3, "Dave", null),           // matched by name in guide 1
            ch(4, "Fox News", ""),         // still unmatched
        )
        val left = EpgSync.pending(own, matched = mapOf(3L to "dave.uk"), seenGuideIds = setOf("cnn.us"))
        assertEquals(listOf(2L, 4L), left.map { it.streamId })
    }

    @Test fun pendingMatchesIdsRegardlessOfCase() {
        val left = EpgSync.pending(listOf(ch(1, "CNN", " CNN.us ")), emptyMap(), setOf("cnn.us"))
        assertTrue(left.isEmpty())
    }

    @Test fun nameMatchingContinuesInTheSecondGuideForWhatTheFirstMissed() {
        val own = listOf(ch(1, "US| Fox News Channel", null), ch(2, "CA| TSN 1 HD", null))
        val guide1 = listOf(EpgMatcher.MajorEventChannel("TSN1.ca", listOf("TSN 1")))
        val guide2 = listOf(EpgMatcher.MajorEventChannel("FoxNews.us", listOf("Fox News")))

        val matched = HashMap<Long, String>()
        matched += EpgMatcher.match(EpgSync.pending(own, matched, emptySet()), guide1)
        assertEquals(setOf(2L), matched.keys)

        val seen = guide1.map { it.id.lowercase() }.toSet()
        matched += EpgMatcher.match(EpgSync.pending(own, matched, seen), guide2)
        assertEquals(mapOf(2L to "tsn1.ca", 1L to "foxnews.us"), matched)
    }

    @Test fun theExtraGuideListIsValid() {
        val urls = EpgPresets.extras.map { it.url }
        assertEquals(urls.size, urls.toSet().size)
        assertTrue(urls.all { it.startsWith("https://") && it.endsWith(".xml.gz") })
        assertTrue(EpgPresets.extras.map { it.label }.toSet().size == EpgPresets.extras.size)
        assertTrue("fewer extras than the sync can read", EpgPresets.extras.size >= 1)
    }
}
