package com.carteltv.tv.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbRequestTest {
    @Test fun `v3 key stays a query parameter`() {
        val (url, headers) = DiscoveryFeeds.tmdbRequest(" abc123 ", "trending/movie/week")
        assertEquals("https://api.themoviedb.org/3/trending/movie/week?api_key=abc123", url)
        assertEquals(emptyMap<String, String>(), headers)
    }

    @Test fun `v4 read token goes in the Authorization header and never in the URL`() {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln"
        val (url, headers) = DiscoveryFeeds.tmdbRequest("Bearer $token", "movie/popular")
        assertEquals("https://api.themoviedb.org/3/movie/popular", url)
        assertEquals(mapOf("Authorization" to "Bearer $token"), headers)
    }
}
