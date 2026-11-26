package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import kotlinx.serialization.Serializable

/**
 * Playback preferences: title memory (speed/resume/subtitle scale), buffer preset, dialogue boost,
 * seek thumbs, pass-out hours, continue-watching columns, MAT TrueHD, ambience.
 *
 * Extracted from [AppPreferences] — playback behavior has its own responsibility.
 */
class PlaybackPreferences(private val sp: SharedPreferences) {

    /* ── Per-title playback memory: speed + resume + subtitle styling keyed by stable ref ── */

    private val memoryLock = Any()
    @Volatile private var memoryRaw: String? = null
    @Volatile private var memoryMap: Map<String, String> = emptyMap()

    private fun titleMemoryRaw(): Map<String, String> {
        val raw = sp.getString("title_memory", null)
        if (raw == memoryRaw) return memoryMap
        synchronized(memoryLock) {
            if (raw == memoryRaw) return memoryMap
            val out = linkedMapOf<String, String>()
            raw?.split("\n")?.forEach { row ->
                val i = row.indexOf('=')
                if (i > 0) out[row.substring(0, i)] = row.substring(i + 1)
            }
            memoryMap = out
            memoryRaw = raw
            return out
        }
    }

    private fun putTitleMemory(map: Map<String, String>) {
        // LRU beyond 200 entries: drop oldest rows first (insertion order).
        val trimmed = map.entries.drop(maxOf(0, map.size - 200)).joinToString("\n") {
            "${it.key}=${it.value}"
        }
        sp.edit().putString("title_memory", trimmed).apply()
    }

    private fun titleMemoryGet(ref: String, key: String): String? =
        titleMemoryRaw()["$ref|$key"]

    private fun titleMemoryPut(ref: String, key: String, value: String) {
        val m = titleMemoryRaw().toMutableMap()
        m.remove("$ref|$key"); m["$ref|$key"] = value   // move-to-end = LRU touch
        putTitleMemory(m)
        memoryRaw = null   // next read rebuilds once from the new preference text
    }

    /** 0.25..4.0, 1f when unset (per-title speed). */
    fun titleSpeed(ref: String): Float =
        titleMemoryGet(ref, "speed")?.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f

    fun setTitleSpeed(ref: String, speed: Float) =
        titleMemoryPut(ref, "speed", speed.toString())

    /** Resume position override for this ref; 0 = follow the global RecentEntry. */
    fun titleResumeMs(ref: String): Long =
        titleMemoryGet(ref, "resume")?.toLongOrNull()?.coerceAtLeast(0) ?: 0

    fun setTitleResumeMs(ref: String, posMs: Long) =
        titleMemoryPut(ref, "resume", posMs.coerceAtLeast(0).toString())

    /** Per-title subtitle text scale (percent, 100 = default); 100 when unset. */
    fun titleSubtitleScale(ref: String): Int =
        titleMemoryGet(ref, "subscale")?.toIntOrNull()?.coerceIn(50, 300) ?: 100

    fun setTitleSubtitleScale(ref: String, pct: Int) =
        titleMemoryPut(ref, "subscale", pct.coerceIn(50, 300).toString())

    var cwHideTitles: Boolean
        get() = sp.getBoolean("cw_hide_titles", false)
        set(v) = sp.edit().putBoolean("cw_hide_titles", v).apply()

    /* ── Dialogue Boost: 0 OFF .. 3 STRONG; applies on next playback ── */
    /** Send Dolby TrueHD (incl. Atmos) to the receiver as a MAT bitstream when the box has no native TrueHD passthrough. */
    var matTrueHd: Boolean
        get() = sp.getBoolean("mat_truehd", true)
        set(v) = sp.edit().putBoolean("mat_truehd", v).apply()

    /** Show preview frames on the seek bar. Opens a second connection to the stream, so it is off by default. */
    var seekThumbs: Boolean
        get() = sp.getBoolean("seek_thumbs", false)
        set(v) = sp.edit().putBoolean("seek_thumbs", v).apply()

    /** Hours of playback with no remote input before asking "Still watching?"; 0 is off. */
    var passOutHours: Int
        get() = sp.getInt("pass_out_hours", 0)
        set(v) = sp.edit().putInt("pass_out_hours", v.coerceIn(0, 12)).apply()

    var dialogueBoost: Int
        get() = sp.getInt("dialogue_boost", 0)
        set(v) = sp.edit().putInt("dialogue_boost", v.coerceIn(0, 3)).apply()

    /** resume-card columns; continueWatchingCardColumns */
    var cwColumns: Int
        get() = sp.getInt("cw_columns", 4)
        set(v) = sp.edit().putInt("cw_columns", v.coerceIn(3, 6)).apply()

    enum class BufferPreset(val minMs: Int, val maxMs: Int, val label: String) {
        LOW(20_000, 60_000, "Low Latency"),
        STD(60_000, 240_000, "Standard"),
        XL(120_000, 480_000, "Extended"),
    }
    var bufferPreset: BufferPreset
        get() = BufferPreset.entries.firstOrNull { it.name == sp.getString("buf", null) } ?: BufferPreset.STD
        set(v) = sp.edit().putString("buf", v.name).apply()

    var ambienceEnabled: Boolean
        get() = sp.getBoolean("amb", true)
        set(v) = sp.edit().putBoolean("amb", v).apply()

    /** "Hold ▸ to resume" reminder pill on Home. */
    var resumePill: Boolean
        get() = sp.getBoolean("resume_pill", false)
        set(v) = sp.edit().putBoolean("resume_pill", v).apply()

    /** Up-next behavior: "off" | "early" | "on". */
    var upNextMode: String
        get() = sp.getString("up_next_mode", "on") ?: "on"
        set(v) = sp.edit().putString("up_next_mode", v).apply()
}
