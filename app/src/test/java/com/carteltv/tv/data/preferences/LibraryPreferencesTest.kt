package com.carteltv.tv.data.preferences

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library's contract: profile scoping, legacy favourites migration and the bare-id mirror. */
class LibraryPreferencesTest {

    private val store = MapSharedPreferences()
    private var profile = "p1"
    private val library = LibraryPreferences(store, Json) { profile }

    private fun entry(id: String) = LibraryPreferences.Entry(id = id, type = "movie", name = "Title $id")

    @Test fun `legacy favourites arrive once with a readable name`() {
        store.edit().putStringSet("favs", mutableSetOf("movie:12", "series:34")).apply()
        val entries = library.entries()
        assertEquals(setOf("movie:12", "series:34"), entries.map { it.id }.toSet())
        assertEquals("12", entries.first { it.id == "movie:12" }.name)
        assertEquals("series", entries.first { it.id == "series:34" }.type)
        // A second read does not duplicate them.
        assertEquals(2, library.entries().size)
    }

    @Test fun `entries are profile scoped`() {
        library.add(entry("movie:1"))
        profile = "p2"
        assertTrue(library.entries().isEmpty())
        library.add(entry("movie:2"))
        assertEquals(listOf("movie:2"), library.entries().map { it.id })
        profile = "p1"
        assertEquals(listOf("movie:1"), library.entries().map { it.id })
    }

    @Test fun `add mirrors favourites and remove clears it`() {
        library.add(entry("movie:1"))
        assertTrue(library.has("movie:1"))
        assertTrue("movie:1" in store.getStringSet("favs", mutableSetOf()).orEmpty())
        assertTrue(library.remove("movie:1"))
        assertFalse(library.has("movie:1"))
        assertFalse("movie:1" in store.getStringSet("favs", mutableSetOf()).orEmpty())
        assertFalse(library.remove("movie:1"))
    }

    @Test fun `toggle flips and returns the new state`() {
        val e = entry("tmdb:movie:9")
        assertTrue(library.toggle(e))
        assertFalse(library.toggle(e))
        assertFalse(library.has(e.id))
    }

    @Test fun `entries come newest first`() {
        library.add(entry("movie:1"))
        Thread.sleep(2)
        library.add(entry("movie:2"))
        assertEquals("movie:2", library.entries().first().id)
    }
}
