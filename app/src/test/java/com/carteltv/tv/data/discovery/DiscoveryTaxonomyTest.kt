package com.carteltv.tv.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discovery laws: taxonomy + poster joins + weave cadence. */
class DiscoveryTaxonomyTest {

    @Test
    fun feeds_taxonomy_complete() {
        assertEquals(4, DiscoveryFeeds.FEEDS_ORDER.size)
        assertEquals(
            listOf("Trending Now", "Popular", "Top Rated", "Coming Soon"),
            DiscoveryFeeds.FEEDS_ORDER.map { it.label },
        )
    }

    @Test
    fun tv_feeds_absent_for_upcoming_only() {
        assertTrue(DiscoveryFeeds.FEEDS_ORDER.all { it.tvPath != null || it == DiscoveryFeeds.Feed.UPCOMING })
    }

    @Test
    fun poster_url_joins_w342() {
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", DiscoveryFeeds.posterUrl("/abc.jpg"))
        assertEquals(null, DiscoveryFeeds.posterUrl(null))
    }
}
