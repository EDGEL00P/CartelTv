package com.carteltv.tv.data.espn

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Disk-backed last-known scoreboards, so a cold start can paint games before the network answers.
 * One small JSON file per board key; a snapshot older than [maxAgeMs] is treated as absent.
 */
internal class ScoreboardSnapshotStore(
    private val dir: File,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val serializer = ListSerializer(EspnScoreboardClient.Score.serializer())

    fun write(key: String, board: List<EspnScoreboardClient.Score>) {
        runCatching {
            dir.mkdirs()
            fileFor(key).writeText(HttpSupport.lenientJson.encodeToString(serializer, board))
        }
    }

    fun read(key: String): List<EspnScoreboardClient.Score>? {
        val file = fileFor(key).takeIf { it.isFile } ?: return null
        if (clock() - file.lastModified() > maxAgeMs) return null
        return runCatching { HttpSupport.lenientJson.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    private fun fileFor(key: String) = File(dir, key.replace(UNSAFE_FILENAME_CHARS, "_") + ".json")

    private companion object {
        const val DEFAULT_MAX_AGE_MS = 12 * 60 * 60_000L
        val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
