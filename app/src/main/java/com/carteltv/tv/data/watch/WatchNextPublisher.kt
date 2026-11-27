package com.carteltv.tv.data.watch

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import com.carteltv.tv.AppScope
import com.carteltv.tv.data.preferences.WatchHistoryPreferences.RecentEntry
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Publishes our own unfinished movies/episodes into Android TV's system
 * `content://android.media.tv/watch_next_program` provider so Google TV surfaces
 * them in its global Watch Next row.
 *
 * Rows carry the local content ref as `content_id` and a self deep link
 * (`carteltv://watch?ref=…`) as `intent_uri` — the incoming link is resolved
 * against local recents ([AppPreferences.recentEntries]), never against a
 * caller-supplied stream URL. Completed or dismissed items have their row
 * removed. All pure eligibility/URI/state decisions live above the Android
 * provider calls so they stay JVM-testable.
 */
object WatchNextPublisher {

    private const val TAG = "WatchNextPublisher"
    private const val PROVIDER = "content://android.media.tv/watch_next_program"

    // Raw TvContract.WatchNextPrograms values; kept as ints so the pure decisions stay JVM-testable.
    const val WATCH_NEXT_TYPE_CONTINUE = 0
    const val PROGRAM_TYPE_MOVIE = 3
    const val PROGRAM_TYPE_TV_EPISODE = 4

    /** Same completion threshold as WatchHistoryStore.FINISHED. */
    private const val COMPLETE_FRACTION = 0.95f

    /** Re-publishing on every progress tick would churn the provider; 30 s of drift is enough. */
    private const val MIN_DELTA_MS = 30_000L

    sealed interface Kind {
        data object Movie : Kind
        data object Episode : Kind
    }

    /** What to do with a recents entry, derived purely from its content. */
    sealed interface Plan {
        data class Publish(
            val ref: String,
            val title: String,
            val programType: Int,
            val positionMs: Long,
            val durationMs: Long,
            val intentUri: String,
            val posterUri: String?,
        ) : Plan

        data object Remove : Plan
        data object Ignore : Plan
    }

    /** Ref grammar → program type. Live streams, recordings, YouTube and unknown refs never publish. */
    fun watchKind(ref: String): Kind? = when {
        ref.startsWith("movie:") || ref.startsWith("tmdb:movie:") -> Kind.Movie
        ref.startsWith("series:") || ref.startsWith("tmdb:tv:") -> Kind.Episode
        else -> null
    }

    fun isComplete(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs * COMPLETE_FRACTION

    /** Pure eligibility + row-content decision for one recents entry. */
    fun planFor(entry: RecentEntry): Plan {
        val kind = watchKind(entry.ref) ?: return Plan.Ignore
        if (entry.url.isBlank()) return Plan.Ignore
        if (isComplete(entry.positionMs, entry.durationMs)) return Plan.Remove
        if (entry.positionMs <= 0 || entry.durationMs <= 0) return Plan.Ignore
        return Plan.Publish(
            ref = entry.ref,
            title = entry.title,
            programType = if (kind == Kind.Movie) PROGRAM_TYPE_MOVIE else PROGRAM_TYPE_TV_EPISODE,
            positionMs = entry.positionMs,
            durationMs = entry.durationMs,
            intentUri = deepLink(entry.ref),
            posterUri = sanitizePoster(entry.poster),
        )
    }

    /** Self deep link carrying only the local ref — resolution happens against local recents. */
    fun deepLink(ref: String): String = "carteltv://watch?ref=" + URLEncoder.encode(ref, "UTF-8")

    /**
     * Pure parser for `carteltv://watch?ref=…`. Returns the ref, or null for any other
     * carteltv link (say/…), missing/blank ref, or undecodable input. Other query
     * parameters — including any caller-supplied `url` — are deliberately ignored.
     */
    fun refFromDeepLink(raw: String): String? {
        if (!raw.startsWith("carteltv://")) return null
        val rest = raw.removePrefix("carteltv://")
        if (rest.substringBefore('?') != "watch") return null
        val query = rest.substringAfter('?', "")
        for (pair in query.split('&')) {
            if (pair.substringBefore('=') != "ref") continue
            return runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
        return null
    }

    /** Only http(s) art renders reliably inside the system row. */
    fun sanitizePoster(poster: String): String? = poster.takeIf { it.startsWith("http") }

    /** Resolves a deep-link ref against local recents; only entries with a playable URL count. */
    fun resolve(entries: List<RecentEntry>, ref: String): RecentEntry? =
        entries.firstOrNull { it.ref == ref && it.url.isNotBlank() }

    /* ── Android provider side ──────────────────────────────────────────── */

    /** Last published position/duration per ref; throttles provider writes on progress ticks. */
    private val lock = Any()
    private val lastPublished = HashMap<String, Pair<Long, Long>>()

    /** Hook for [com.carteltv.tv.data.preferences.AppPreferences.notePlayback]: publish, remove or ignore. */
    fun notePlayback(ctx: Context, entry: RecentEntry) {
        val app = ctx.applicationContext
        when (val plan = planFor(entry)) {
            Plan.Ignore -> {}
            Plan.Remove -> {
                synchronized(lock) { lastPublished.remove(entry.ref) }
                AppScope.io.launch { removeRef(app, entry.ref) }
            }
            is Plan.Publish -> {
                synchronized(lock) {
                    val last = lastPublished[entry.ref]
                    if (last != null && last.second == plan.durationMs &&
                        kotlin.math.abs(plan.positionMs - last.first) < MIN_DELTA_MS
                    ) return
                    lastPublished[entry.ref] = plan.positionMs to plan.durationMs
                }
                AppScope.io.launch { upsert(app, plan) }
            }
        }
    }

    /** Hook for recent dismissal: the system row must not outlive the local card. */
    fun recentRemoved(ctx: Context, ref: String) {
        if (watchKind(ref) == null) return
        val app = ctx.applicationContext
        synchronized(lock) { lastPublished.remove(ref) }
        AppScope.io.launch { removeRef(app, ref) }
    }

    /** Startup/return reconcile: rows for every current recents entry, nothing else. */
    fun reconcile(ctx: Context, entries: List<RecentEntry>) {
        val app = ctx.applicationContext
        AppScope.io.launch {
            // One plan pass decides both the publish set and which stale rows to prune.
            val plans = entries.map(::planFor)
            val publish = plans.mapNotNull { (it as? Plan.Publish) }.associateBy { it.ref }
            // knownRows() may be unavailable (missing provider/grant): publishing still works insert-only.
            publish.values.forEach { upsert(app, it) }
            runCatching {
                knownRows(app).forEach { (ref, id) ->
                    if (ref !in publish) deleteRow(app, id)
                }
            }.onFailure { Log.d(TAG, "Watch Next reconcile prune skipped: ${it.message}") }
        }
    }

    /** Our own rows in the provider: content_id → row _id. Requires READ_TV_LISTINGS (manifest). */
    private fun knownRows(ctx: Context): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        runCatching {
            ctx.contentResolver.query(
                Uri.parse(PROVIDER),
                arrayOf("_id", "package_name", "content_id"),
                null, null, null,
            )?.use { c ->
                val iPkg = c.getColumnIndex("package_name")
                val iId = c.getColumnIndex("_id")
                val iRef = c.getColumnIndex("content_id")
                while (c.moveToNext()) {
                    if (iPkg < 0 || c.getString(iPkg) != ctx.packageName) continue
                    val ref = if (iRef >= 0 && !c.isNull(iRef)) c.getString(iRef) else continue
                    if (iId >= 0 && !c.isNull(iId)) out[ref] = c.getLong(iId)
                }
            }
        }.onFailure { Log.d(TAG, "Watch Next read unavailable: ${it.message}") }
        return out
    }

    private fun upsert(ctx: Context, plan: Plan.Publish) {
        runCatching {
            val values = ContentValues().apply {
                put("title", plan.title)
                put("content_id", plan.ref)
                put("type", plan.programType)
                put("watch_next_type", WATCH_NEXT_TYPE_CONTINUE)
                put("last_playback_position_millis", plan.positionMs)
                put("duration_millis", plan.durationMs)
                put("intent_uri", plan.intentUri)
                put("browsable", 1)
                put("last_engagement_time_utc_millis", System.currentTimeMillis())
                plan.posterUri?.let { put("poster_art_uri", it) }
            }
            val existing = knownRows(ctx)[plan.ref]
            if (existing != null) {
                ctx.contentResolver.update(Uri.parse("$PROVIDER/$existing"), values, null, null)
            } else {
                ctx.contentResolver.insert(Uri.parse(PROVIDER), values)
            }
        }.onFailure { Log.w(TAG, "Watch Next publish failed: ${it.message}") }
    }

    private fun removeRef(ctx: Context, ref: String) {
        val id = knownRows(ctx)[ref] ?: return
        runCatching { deleteRow(ctx, id) }
            .onFailure { Log.w(TAG, "Watch Next remove failed: ${it.message}") }
    }

    private fun deleteRow(ctx: Context, id: Long) {
        ctx.contentResolver.delete(Uri.parse("$PROVIDER/$id"), null, null)
    }
}
