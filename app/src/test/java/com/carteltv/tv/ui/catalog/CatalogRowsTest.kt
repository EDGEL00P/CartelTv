package com.carteltv.tv.ui.catalog

import com.carteltv.tv.data.stremio.StremioAddons
import com.carteltv.tv.data.stremio.StremioClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** One rail per list across addons, and Cinemeta's stock lists only when nothing else catalogs the type. */
class CatalogRowsTest {

    private fun addon(url: String, id: String, vararg catalogs: StremioClient.Manifest.CatalogDecl) =
        StremioAddons.Loaded(url, StremioClient.Manifest(id = id, catalogs = catalogs.toList()), StremioClient(url))

    private fun decl(type: String, id: String) = StremioClient.Manifest.CatalogDecl(type = type, id = id)

    private fun metum(id: String) = StremioClient.Metum(id = id, name = id)

    @Test
    fun `cinemeta catalogs are the fallback only`() {
        val cinemeta = addon(StremioClient.CINEMETA, "com.linvo.cinemeta", decl("movie", "top"), decl("series", "top"))
        val aio = addon("https://aiometa/manifest.json", "aiometadata", decl("movie", "tmdb.top"))
        assertEquals(listOf("aiometadata"), catalogSources(listOf(cinemeta, aio), "movie").map { it.first.manifest.id })
        assertEquals(listOf("com.linvo.cinemeta"), catalogSources(listOf(cinemeta, aio), "series").map { it.first.manifest.id })
    }

    @Test
    fun `same-named rails merge, first source first, each title once`() {
        val merged = mergeCatalogRows(
            listOf(
                CatalogRow("Popular Movies", listOf(metum("tt1"), metum("tt2"))),
                CatalogRow("Trending", listOf(metum("tt9"))),
                CatalogRow("popular movies", listOf(metum("tt2"), metum("tt3"))),
                CatalogRow("Empty", emptyList()),
            ),
        )
        assertEquals(listOf("Popular Movies", "Trending"), merged.map { it.title })
        assertEquals(listOf("tt1", "tt2", "tt3"), merged.first().items.map { it.id })
    }
}
