package com.carteltv.tv.data.chapters

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import com.carteltv.tv.data.watch.WatchHistoryStore

/**
 * Per-item chapter watch state: which chapters were watched to completion, how many times,
 * and how much of the current chapter has accumulated this or a previous ProviderSession. Partial
 * playtime is kept per chapter so a ProviderSession split across restarts still reaches the
 * completion threshold (owned by ChapterTracker).
 *
 * Storage mirrors [WatchHistoryStore]: one JSON blob in SharedPreferences. All mutating work
 * happens in an in-memory ProviderSession and is flushed on chapter change, completion or dispose,
 * never on every tick.
 */
object ChapterScrobbleStore {

    data class ChapterState(
        val index: Int,
        val completions: Int = 0,
        val lastCompletedAtMs: Long = 0,
        val partialMs: Long = 0,
    )

    data class ItemChapters(
        val refId: String,
        val durationMs: Long = 0,
        val chapterCount: Int = 0,
        val chapters: Map<Int, ChapterState> = emptyMap(),
        val lastChapterIndex: Int = -1,
        val lastWatchedMs: Long = 0,
    )

    private const val PREFS = "carteltv_chapter_scrobbles"
    private const val KEY = "items"
    private const val ORPHAN_MAX_AGE_MS = 30L * 24 * 3600 * 1000

    private val lock = Any()
    private var cache: MutableMap<String, ItemChapters>? = null

    /* ── ProviderSession API (called from the player tick loop) ── */

    /** Starts or resumes a playback ProviderSession; drops stale chapter state when the file's chapter list changed. */
    fun beginSession(context: Context, refId: String, durationMs: Long, chapterCount: Int): ItemChapters = synchronized(lock) {
        val items = load(context).toMutableMap()
        val old = items[refId]
        val kept = if (old != null && old.chapterCount != chapterCount && chapterCount > 0) {
            old.copy(chapters = emptyMap(), durationMs = durationMs, chapterCount = chapterCount)
        } else {
            (old ?: ItemChapters(refId)).copy(durationMs = durationMs.coerceAtLeast(0), chapterCount = chapterCount.coerceAtLeast(0))
        }
        items[refId] = kept
        store(context, items)
        kept
    }

    /** Credits playtime to [index] without a completion; merged into partialMs on flush. */
    fun accumulate(context: Context, refId: String, index: Int, playtimeDeltaMs: Long, atMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (playtimeDeltaMs <= 0) return@synchronized
        val items = load(context).toMutableMap()
        val item = items[refId] ?: return@synchronized
        val st = item.chapters[index] ?: ChapterState(index)
        items[refId] = item.copy(
            chapters = item.chapters + (index to st.copy(partialMs = st.partialMs + playtimeDeltaMs)),
            lastChapterIndex = index,
            lastWatchedMs = atMs,
        )
        store(context, items)
    }

    /** Records a tracker-confirmed completion of [index]; partial playtime folds into the completed count. */
    fun complete(context: Context, refId: String, index: Int, atMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val items = load(context).toMutableMap()
        val item = items[refId] ?: return@synchronized
        val st = item.chapters[index] ?: ChapterState(index)
        items[refId] = item.copy(
            chapters = item.chapters + (index to st.copy(
                completions = st.completions + 1,
                lastCompletedAtMs = atMs,
                partialMs = 0,
            )),
            lastChapterIndex = index,
            lastWatchedMs = atMs,
        )
        store(context, items)
    }

    fun clear(context: Context, refId: String) = synchronized(lock) {
        val items = load(context).toMutableMap()
        items.remove(refId) ?: return@synchronized
        store(context, items)
    }

    fun get(context: Context, refId: String): ItemChapters? = synchronized(lock) { load(context)[refId] }

    fun completions(context: Context, refId: String): Map<Int, Int> =
        get(context, refId)?.chapters?.mapValues { it.value.completions }?.filterValues { it > 0 } ?: emptyMap()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): MutableMap<String, ItemChapters> {
        cache?.let { return it }
        val raw = prefs(context).getString(KEY, null)
        val items = raw?.let { ChapterScrobbleLog.parse(it) } ?: emptyMap()
        val pruned = prune(context, items).toMutableMap()
        cache = pruned
        return pruned
    }

    private fun store(context: Context, items: Map<String, ItemChapters>) {
        val kept = items.toMutableMap()
        prefs(context).edit().putString(KEY, ChapterScrobbleLog.serialize(kept)).apply()
        cache = kept
    }

    /** Drops records whose item left WatchHistoryStore and whose last watch is older than the orphan window. */
    internal fun prune(historyIds: Set<String>, items: Map<String, ItemChapters>, nowMs: Long): Map<String, ItemChapters> =
        items.filter { (_, v) -> v.refId in historyIds || nowMs - v.lastWatchedMs < ORPHAN_MAX_AGE_MS }

    private fun prune(context: Context, items: Map<String, ItemChapters>): Map<String, ItemChapters> =
        prune(WatchHistoryStore.all(context).mapTo(mutableSetOf()) { it.id }, items, System.currentTimeMillis())
}

/** JSON serialization for [ChapterScrobbleStore]; kotlinx.serialization so JVM tests run it without Android stubs. */
object ChapterScrobbleLog {

    fun serialize(items: Map<String, ChapterScrobbleStore.ItemChapters>): String = buildJsonArray {
        for (item in items.values.sortedBy { it.lastWatchedMs }) {
            add(buildJsonObject {
                put("ref", item.refId)
                put("dur", item.durationMs)
                put("n", item.chapterCount)
                put("last", item.lastChapterIndex)
                put("seen", item.lastWatchedMs)
                put("ch", buildJsonArray {
                    for (st in item.chapters.values.sortedBy { it.index }) {
                        add(buildJsonObject {
                            put("i", st.index)
                            put("c", st.completions)
                            put("at", st.lastCompletedAtMs)
                            put("p", st.partialMs)
                        })
                    }
                })
            })
        }
    }.toString()

    fun parse(raw: String): Map<String, ChapterScrobbleStore.ItemChapters> {
        val arr = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, ChapterScrobbleStore.ItemChapters>()
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val ref = (o["ref"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: continue
            val chapters = mutableMapOf<Int, ChapterScrobbleStore.ChapterState>()
            for (c in (o["ch"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }) {
                val index = c.intOr("i", -1)
                if (index < 0) continue
                chapters[index] = ChapterScrobbleStore.ChapterState(
                    index = index,
                    completions = c.intOr("c", 0).coerceAtLeast(0),
                    lastCompletedAtMs = c.longOr("at", 0),
                    partialMs = c.longOr("p", 0).coerceAtLeast(0),
                )
            }
            out[ref] = ChapterScrobbleStore.ItemChapters(
                refId = ref,
                durationMs = o.longOr("dur", 0).coerceAtLeast(0),
                chapterCount = o.intOr("n", 0).coerceAtLeast(0),
                chapters = chapters,
                lastChapterIndex = o.intOr("last", -1),
                lastWatchedMs = o.longOr("seen", 0),
            )
        }
        return out
    }

    private fun JsonObject.intOr(key: String, default: Int): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: default
    private fun JsonObject.longOr(key: String, default: Long): Long = (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: default
}
