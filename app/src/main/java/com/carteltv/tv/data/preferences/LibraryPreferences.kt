package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The household library: one profile-scoped JSON list of titles a person saved for later.
 * Bare `favs` sets from older builds are folded in on first read, so a saved title never
 * disappears on upgrade, and every write mirrors back into `favs` for the screens that still
 * read the old set.
 *
 * Entry ids are refs: `stremio:tt…`, `tmdb:movie:…`, `movie:…` or `series:…`.
 */
class LibraryPreferences(
    private val sp: SharedPreferences,
    private val json: Json,
    private val profileId: () -> String,
) {

    @Serializable
    data class Entry(
        val id: String,
        val type: String,
        val name: String,
        val poster: String = "",
        val backdrop: String = "",
        val year: String = "",
        val rating: String = "",
        val addedAt: Long = 0L,
    )

    private fun key(pid: String = profileId()) = "library_json_$pid"

    private fun read(): List<Entry> {
        val raw = sp.getString(key(), null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Entry>>(raw) }.getOrDefault(emptyList())
    }

    private fun write(entries: List<Entry>) {
        sp.edit().putString(key(), json.encodeToString(entries)).apply()
    }

    /** The library, newest first. Folds the legacy favourites set in once, into the profile active at that moment. */
    fun entries(): List<Entry> {
        if (!sp.getBoolean(MIGRATED, false)) {
            // Ids already in any profile's list are not legacy-only, so a new profile does not inherit them.
            val known = sp.all.keys
                .filter { it.startsWith(LIBRARY_KEY) }
                .flatMap { k -> runCatching { json.decodeFromString<List<Entry>>(sp.getString(k, null).orEmpty()) }.getOrDefault(emptyList()) }
                .mapTo(HashSet()) { it.id }
            val legacy = (sp.getStringSet("favs", emptySet()) ?: emptySet())
                .filterNot { it in known }
                .mapIndexed { index, id -> legacyEntry(id, System.currentTimeMillis() - index) }
            if (legacy.isNotEmpty()) write((legacy + read()).distinctBy { it.id })
            sp.edit().putBoolean(MIGRATED, true).apply()
        }
        return read().sortedByDescending { it.addedAt }
    }

    fun has(id: String): Boolean = read().any { it.id == id }

    fun isType(type: String): Boolean = read().any { it.type == type }

    fun add(entry: Entry): Boolean {
        val cur = read()
        if (cur.any { it.id == entry.id }) return false
        write((listOf(entry.copy(addedAt = System.currentTimeMillis())) + cur).take(MAX_ENTRIES))
        mirrorFav(entry.id, add = true)
        return true
    }

    fun remove(id: String): Boolean {
        val cur = read()
        if (cur.none { it.id == id }) return false
        write(cur.filterNot { it.id == id })
        mirrorFav(id, add = false)
        return true
    }

    /** Adds when absent, removes when saved; returns the new saved state. */
    fun toggle(entry: Entry): Boolean =
        if (has(entry.id)) { remove(entry.id); false } else { add(entry); true }

    fun clear() {
        read().forEach { mirrorFav(it.id, add = false) }
        write(emptyList())
    }

    private fun mirrorFav(id: String, add: Boolean) {
        val cur = (sp.getStringSet("favs", emptySet()) ?: emptySet()).toMutableSet()
        if ((if (add) cur.add(id) else cur.remove(id))) {
            sp.edit().putStringSet("favs", cur).apply()
        }
    }

    /** A bare id from the old favourites set, enough for a tile until metadata is fetched. */
    private fun legacyEntry(id: String, addedAt: Long): Entry {
        val type = when {
            id.startsWith("series:", true) || id.startsWith("tv:", true) -> "series"
            else -> "movie"
        }
        val name = id.substringAfter(':').substringAfter(':').ifBlank { id }
        return Entry(id = id, type = type, name = name, addedAt = addedAt)
    }

    private companion object {
        const val MAX_ENTRIES = 500
        const val MIGRATED = "library_favs_migrated"
        const val LIBRARY_KEY = "library_json_"
    }
}
