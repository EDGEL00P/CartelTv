package com.carteltv.tv.data.setup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Favourites and continue-watching as a file, for reinstalls and moving to another box.
 * Recents already carry redacted stream URLs, so no credentials travel.
 */
object LibraryBackup {

    private const val VERSION = 1
    private const val MAX_RECENTS = 30
    private const val MAX_FAVS = 5000

    @Serializable
    data class Snapshot(
        val version: Int = VERSION,
        val favs: List<String> = emptyList(),
        val recents: List<String> = emptyList(),
    )

    data class Merged(val favs: Set<String>, val recents: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(favs: Set<String>, recents: List<String>): String =
        json.encodeToString(Snapshot(favs = favs.sorted(), recents = recents))

    /** Throws [IllegalArgumentException] for anything that is not a readable, supported backup. */
    fun decode(text: String): Snapshot {
        val snap = try {
            json.decodeFromString<Snapshot>(text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IllegalArgumentException("Not a CartelTV library backup", e)
        }
        require(snap.version in 1..VERSION) { "Unsupported backup version ${snap.version}" }
        return snap
    }

    /**
     * Adds the backup to what is already on the device: favourites are unioned, recents keep the
     * device's entries first and append backup entries whose [refOf] is not already present.
     */
    fun merge(
        currentFavs: Set<String>,
        currentRecents: List<String>,
        backup: Snapshot,
        refOf: (String) -> String,
    ): Merged {
        val favs = (currentFavs + backup.favs.filter { it.isNotBlank() }).take(MAX_FAVS).toSet()
        val seen = currentRecents.map(refOf).toMutableSet()
        val recents = currentRecents + backup.recents.filter { it.isNotBlank() && seen.add(refOf(it)) }
        return Merged(favs, recents.take(MAX_RECENTS))
    }
}
