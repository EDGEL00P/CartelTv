package com.carteltv.tv.data.preferences

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfPreferencesTest {

    private val store = MapSharedPreferences()
    private val shelves = ShelfPreferences(store, Json)

    @Test fun `update keeps one row and its place`() {
        val a = shelves.add(ShelfPreferences.Shelf(id = "", title = "A", media = "movie", genreId = null))
        shelves.add(ShelfPreferences.Shelf(id = "", title = "B", media = "series", genreId = null))
        shelves.update(a.copy(title = "A2", media = "series", genreId = 28))
        val rows = shelves.shelves()
        assertEquals(listOf("A2", "B"), rows.map { it.title })
        assertEquals(28, rows.first().genreId)
        assertEquals(a.id, rows.first().id)
    }

    @Test fun `remove drops only the named shelf`() {
        val a = shelves.add(ShelfPreferences.Shelf(id = "", title = "A", media = "movie", genreId = null))
        shelves.add(ShelfPreferences.Shelf(id = "", title = "B", media = "movie", genreId = null))
        shelves.remove(a.id)
        assertEquals(listOf("B"), shelves.shelves().map { it.title })
    }
}
