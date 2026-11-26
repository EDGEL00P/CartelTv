package com.carteltv.tv.data.xtream

import com.carteltv.tv.data.xtream.EpgMatcher.Channel
import com.carteltv.tv.data.xtream.EpgMatcher.MajorEventChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMatcherTest {

    private fun g(id: String, vararg names: String) = MajorEventChannel(id, names.toList())

    @Test fun directIdsAreNeverTouched() {
        val out = EpgMatcher.match(
            listOf(Channel(1, "US| CNN HD", "cnn.us")),
            listOf(g("cnn.us", "CNN"), g("cnn.uk", "CNN")),
        )
        assertTrue(out.isEmpty())
    }

    @Test fun unmatchedIdFindsGuideIdByStem() {
        val out = EpgMatcher.match(
            listOf(Channel(1, "CNN", "CNN.HD.us2")),
            listOf(g("cnn.us", "CNN")),
        )
        assertEquals("cnn.us", out[1])
    }

    @Test fun missingIdFallsBackToTheCleanedName() {
        val out = EpgMatcher.match(
            listOf(Channel(7, "US| Discovery Channel HD", null)),
            listOf(g("DiscoveryChannel.us", "Discovery Channel"), g("DiscoveryChannel.in", "Discovery Channel")),
        )
        assertEquals("discoverychannel.us", out[7])
    }

    @Test fun differentCountriesAreRefused() {
        val out = EpgMatcher.match(
            listOf(Channel(3, "UK| NTV", null)),
            listOf(g("NTV.bd", "NTV")),
        )
        assertTrue(out.isEmpty())
    }

    @Test fun anAmbiguousNameWithoutACountryIsRefused() {
        val out = EpgMatcher.match(
            listOf(Channel(4, "Dave", null)),
            listOf(g("Dave.uk", "Dave"), g("Dave.au", "Dave")),
        )
        assertTrue(out.isEmpty())
    }

    @Test fun anUnambiguousNameWithoutACountryIsAccepted() {
        val out = EpgMatcher.match(
            listOf(Channel(4, "Dave", null)),
            listOf(g("Dave.uk", "Dave")),
        )
        assertEquals("dave.uk", out[4])
    }

    @Test fun gbIsTreatedAsUk() {
        val out = EpgMatcher.match(
            listOf(Channel(5, "GB| BBC Two", null)),
            listOf(g("BBC2.us", "BBC Two"), g("BBCTwo.uk", "BBC Two")),
        )
        assertEquals("bbctwo.uk", out[5])
    }

    @Test fun anIdThatExistsInTheGuideStaysDirectEvenIfNamesDiffer() {
        val out = EpgMatcher.match(
            listOf(Channel(6, "Something Else", "abc.us")),
            listOf(g("ABC.us", "ABC")),
        )
        assertTrue(out.isEmpty())
    }

    private fun detailed(name: String, vararg guide: MajorEventChannel, epgId: String? = null, overrides: Map<Long, String> = emptyMap()) =
        EpgMatcher.matchDetailed(listOf(Channel(1, name, epgId)), guide.toList(), overrides)[1L]

    @Test fun fillerWordsAndNumberWordsStillMatch() {
        assertEquals(EpgMatcher.Tier.FUZZY, detailed("US| Fox News Channel HD", g("FoxNews.us", "Fox News"))?.tier)
        assertEquals("foxnews.us", detailed("US| Fox News Channel HD", g("FoxNews.us", "Fox News"))?.epgId)
        assertEquals("bbc1.uk", detailed("UK| BBC One HD", g("BBC1.uk", "BBC 1"))?.epgId)
        assertEquals("weather.us", detailed("US| The Weather Channel", g("Weather.us", "Weather Channel"))?.epgId)
    }

    @Test fun numbersAreNeverGuessed() {
        assertEquals(null, detailed("CA| TSN 1", g("TSN2.ca", "TSN 2")))
        assertEquals(null, detailed("CA| CTV", g("CTVPlus1.ca", "CTV +1")))
        assertEquals(null, detailed("CA| Sportsnet One", g("Sportsnet2.ca", "Sportsnet 2")))
        assertEquals("sportsnet1.ca", detailed("CA| Sportsnet One", g("Sportsnet1.ca", "Sportsnet 1"), g("Sportsnet2.ca", "Sportsnet 2"))?.epgId)
    }

    @Test fun oppositeFeedsAreNeverSwapped() {
        assertEquals(null, detailed("CA| CTV East", g("CTVWest.ca", "CTV West")))
        assertEquals("ctveast.ca", detailed("CA| CTV East HD", g("CTVEast.ca", "CTV East"), g("CTVWest.ca", "CTV West"))?.epgId)
    }

    @Test fun anUntaggedNameDoesNotPickBetweenFeeds() {
        assertEquals(null, detailed("CA| CTV Comedy", g("CTVComedyEast.ca", "CTV Comedy East"), g("CTVComedyWest.ca", "CTV Comedy West")))
    }

    @Test fun aLoneTaggedFeedMayAnswerAnUntaggedName() {
        val m = detailed("CA| Comedy Central", g("ComedyCentralEast.ca", "Comedy Central East"))
        assertEquals("comedycentraleast.ca", m?.epgId)
        assertEquals(EpgMatcher.Tier.FUZZY, m?.tier)
    }

    @Test fun lookAlikeNamesBelowTheBarAreRefused() {
        assertEquals(null, detailed("US| Sky Sports News", g("SkySportsMix.us", "Sky Sports Mix")))
        assertEquals(null, detailed("US| CNN", g("CNNInternational.us", "CNN International")))
        assertEquals(null, detailed("US| Food Network", g("FoodNetworkCanada.ca", "Food Network Canada")))
    }

    @Test fun fuzzyRefusesAnotherCountryAndAmbiguity() {
        assertEquals(null, detailed("UK| Fox News Channel", g("FoxNews.us", "Fox News")))
        assertEquals(null, detailed("Fox News Channel", g("FoxNews.us", "Fox News"), g("FoxNews.ca", "Fox News")))
        assertEquals("foxnews.us", detailed("US| Fox News Channel", g("FoxNews.us", "Fox News"), g("FoxNews.ca", "Fox News"))?.epgId)
    }

    @Test fun anOverrideBeatsEverythingButOnlyForRealGuideIds() {
        val guide = arrayOf(g("CNN.us", "CNN"), g("Other.us", "Other"))
        assertEquals(EpgMatcher.Tier.OVERRIDE, detailed("US| CNN", *guide, epgId = "cnn.us", overrides = mapOf(1L to "OTHER.us"))?.tier)
        assertEquals("other.us", detailed("US| CNN", *guide, epgId = "cnn.us", overrides = mapOf(1L to "OTHER.us"))?.epgId)
        assertEquals(null, detailed("US| CNN", *guide, epgId = "cnn.us", overrides = mapOf(1L to "missing.us")))
    }

    @Test fun matchReportsItsTier() {
        assertEquals(EpgMatcher.Tier.ID_STEM, detailed("CNN", g("cnn.us", "CNN"), epgId = "CNN.HD.us2")?.tier)
        assertEquals(EpgMatcher.Tier.EXACT_NAME, detailed("US| Discovery Channel HD", g("DiscoveryFeeds.us", "Discovery Channel"))?.tier)
    }

    @Test fun tokensNormaliseNames() {
        assertEquals(listOf("tsn", "1"), EpgMatcher.tokens("CA| TSN1 HD"))
        assertEquals(listOf("ctv", "plus1"), EpgMatcher.tokens("CTV +1"))
        assertEquals(listOf("a", "and", "e"), EpgMatcher.tokens("A&E"))
        assertEquals(listOf("ici", "tele"), EpgMatcher.tokens("ICI Télé HD"))
        assertEquals(listOf("bbc", "1"), EpgMatcher.tokens("BBC One"))
    }

    @Test fun aLargeGuideMatchesQuickly() {
        val guide = (0 until 8000).map { g("Channel$it.us", "Zephyr Network $it") } + g("FoxNews.us", "Fox News")
        val started = System.nanoTime()
        val out = EpgMatcher.match((1L..2000L).map { Channel(it, "US| Zephyr Net ${it % 50} HD", null) } + Channel(9999, "US| Fox News Channel", null), guide)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals("foxnews.us", out[9999])
        assertTrue("took $ms ms", ms < 8000)
    }

    @Test fun nothingToMatchIsEmpty() {
        assertTrue(EpgMatcher.match(emptyList(), listOf(g("a.us"))).isEmpty())
        assertTrue(EpgMatcher.match(listOf(Channel(1, "A", null)), emptyList()).isEmpty())
    }
}
