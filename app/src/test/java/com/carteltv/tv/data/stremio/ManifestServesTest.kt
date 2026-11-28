package com.carteltv.tv.data.stremio

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol request filtering: which installed addon a meta/stream request is sent to. */
class ManifestServesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun manifest(raw: String) = json.decodeFromString<StremioClient.Manifest>(raw)

    private fun loaded(url: String, raw: String) = StremioAddons.Loaded(url, manifest(raw), StremioClient(url))

    @Test
    fun `string resource uses manifest types and prefixes`() {
        val m = manifest("""{"types":["movie","series"],"idPrefixes":["tt"],"resources":["catalog","meta"]}""")
        assertTrue(m.serves("meta", "movie", "tt0111161"))
        assertFalse(m.serves("meta", "movie", "kitsu:1"))
        assertFalse(m.serves("meta", "channel", "tt0111161"))
        assertFalse(m.serves("stream", "movie", "tt0111161"))
    }

    @Test
    fun `object resource overrides manifest types and prefixes`() {
        val m = manifest(
            """{"types":["movie"],"idPrefixes":["tt"],
               "resources":[{"name":"meta","types":["series","anime"],"idPrefixes":["kitsu:","mal:"]}]}""",
        )
        assertTrue(m.serves("meta", "anime", "kitsu:42"))
        assertFalse(m.serves("meta", "movie", "tt0111161"))
        assertFalse(m.serves("meta", "series", "tt0944947"))
    }

    @Test
    fun `no prefixes at all matches every id`() {
        val m = manifest("""{"types":["movie"],"resources":[{"name":"stream","types":["movie"]}]}""")
        assertTrue(m.serves("stream", "movie", "tt0111161"))
        assertTrue(m.serves("stream", "movie", "anything:1"))
    }

    @Test
    fun `meta goes to declaring addons in the user's order, cinemeta last`() {
        val streams = loaded("https://aio/manifest.json", """{"types":["movie"],"resources":["stream"]}""")
        val aioMeta = loaded("https://aiometa/manifest.json", """{"types":["movie","series"],"idPrefixes":["tt","tmdb:"],"resources":["catalog","meta"]}""")
        val tmdb = loaded("https://tmdb/manifest.json", """{"types":["movie"],"resources":[{"name":"meta","types":["movie"]}]}""")
        val cinemeta = loaded(StremioClient.CINEMETA, """{"types":["movie","series"],"idPrefixes":["tt"],"resources":["catalog","meta"]}""")
        val installed = listOf(cinemeta, streams, aioMeta, tmdb)
        val order = StremioAddons.serving("meta", "movie", "tt0111161", installed).map { it.url }
        assertEquals(listOf("https://aiometa/manifest.json", "https://tmdb/manifest.json", StremioClient.CINEMETA), order)
        assertEquals(listOf("https://aio/manifest.json"), StremioAddons.serving("stream", "movie", "tt0111161", installed).map { it.url })
    }
}
