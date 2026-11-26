package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Custom shelves: user-named TMDB rows (a genre, a media kind, an order) pinned into On Demand.
 * The local shape of a Nuvio collection folder, without the add-on dependency.
 */
class ShelfPreferences(
    private val sp: SharedPreferences,
    private val json: Json,
) {

    @Serializable
    data class Shelf(
        val id: String,
        val title: String,
        val media: String,        // "movie" | "series"
        val genreId: Int?,        // null = every genre
        val sort: String = "popularity.desc",
        val createdAt: Long = 0L,
    )

    fun shelves(): List<Shelf> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Shelf>>(raw) }.getOrDefault(emptyList())
            .sortedBy { it.createdAt }
    }

    fun add(shelf: Shelf): Shelf {
        val row = shelf.copy(
            id = shelf.id.ifBlank { "shelf_${java.util.UUID.randomUUID()}" },
            createdAt = (shelves().maxOfOrNull { it.createdAt } ?: 0L) + 1,
        )
        sp.edit().putString(KEY, json.encodeToString(shelves() + row)).apply()
        return row
    }

    fun remove(id: String) {
        sp.edit().putString(KEY, json.encodeToString(shelves().filterNot { it.id == id })).apply()
    }

    /** Replaces a shelf in place, keeping its creation order. */
    fun update(shelf: Shelf) {
        val cur = shelves()
        val createdAt = cur.firstOrNull { it.id == shelf.id }?.createdAt ?: System.currentTimeMillis()
        sp.edit().putString(KEY, json.encodeToString(cur.filterNot { it.id == shelf.id } + shelf.copy(createdAt = createdAt))).apply()
    }

    private companion object {
        const val KEY = "on_demand_shelves"
    }
}
