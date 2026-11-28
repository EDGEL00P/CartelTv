package com.carteltv.tv.data.xtream

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The provider's channel list saved to disk, so a cold start shows channels at once and refreshes behind them
 * instead of making the viewer wait for a full download. A snapshot only counts for the provider and language
 * choice it was saved under, and only while it is young enough.
 */
object ChannelListCache {
    const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
    private const val FILE = "live_channels.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private class Snapshot(val key: String, val savedAt: Long, val streams: List<XtreamClient.LiveStream>)

    fun serialize(key: String, savedAt: Long, streams: List<XtreamClient.LiveStream>): String =
        json.encodeToString(Snapshot.serializer(), Snapshot(key, savedAt, streams))

    /** The saved list, or null when it is for another key, empty, too old, dated in the future, or unreadable. */
    fun parse(text: String, key: String, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): List<XtreamClient.LiveStream>? {
        val snap = runCatching { json.decodeFromString(Snapshot.serializer(), text) }.getOrNull() ?: return null
        if (snap.key != key || snap.streams.isEmpty()) return null
        if (nowMs - snap.savedAt !in 0..maxAgeMs) return null
        return snap.streams
    }

    fun read(dir: File, key: String, nowMs: Long): List<XtreamClient.LiveStream>? =
        runCatching { File(dir, FILE).takeIf { it.isFile }?.readText()?.let { parse(it, key, nowMs) } }.getOrNull()

    /** Writes through a temporary file so a crash mid-write never leaves a half-written snapshot behind. */
    fun write(dir: File, key: String, savedAt: Long, streams: List<XtreamClient.LiveStream>) {
        runCatching {
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(serialize(key, savedAt, streams))
            if (!tmp.renameTo(File(dir, FILE))) tmp.delete()
        }
    }
}
