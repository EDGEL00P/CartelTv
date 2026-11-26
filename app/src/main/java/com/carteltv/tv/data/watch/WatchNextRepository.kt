package com.carteltv.tv.data.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.carteltv.tv.data.apps.AppsRepository
import com.carteltv.tv.core.ProviderIntentSanitizer

/** Program surfaced by Android TV's system Watch Next provider. */
data class WatchNextItem(
    val title: String,
    val description: String?,
    val packageName: String,
    val posterUri: String?,
    val intentUri: String?,
    val positionMs: Long,
    val durationMs: Long,
    val watchNextType: Int,
    val lastEngagementMs: Long,
) {
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Queries Android TV's `content://android.media.tv/watch_next_program` provider with an
 * in-memory cache that [MainActivity.onResume] invalidates whenever the user returns from an app.
 */
object WatchNextRepository {

    private const val TAG = "WatchNextRepository"
    private const val WATCH_NEXT_URI = "content://android.media.tv/watch_next_program"
    private const val MAX_SCAN = 150
    private const val MAX_RETURN = 30

    private val PROJECTION = arrayOf(
        "title",
        "short_description",
        "package_name",
        "poster_art_uri",
        "intent_uri",
        "last_playback_position_millis",
        "duration_millis",
        "watch_next_type",
        "last_engagement_time_utc_millis",
    )

    @Volatile
    private var cached: List<WatchNextItem>? = null

    fun invalidate() {
        cached = null
    }

    /** Pure ranking: Watch Next type ascending (CONTINUE=0, NEXT=1, NEW=2, WATCHLIST=3), then most recent engagement first. */
    fun rankPrograms(items: List<WatchNextItem>, limit: Int = MAX_RETURN): List<WatchNextItem> =
        items
            .sortedWith(
                compareBy<WatchNextItem> { it.watchNextType }
                    .thenByDescending { it.lastEngagementMs },
            )
            .distinctBy { "${it.packageName}|${it.title}|${it.intentUri.orEmpty()}" }
            .take(limit)

    suspend fun load(ctx: Context): List<WatchNextItem> = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }

        val raw = mutableListOf<WatchNextItem>()
        runCatching {
            ctx.contentResolver.query(
                Uri.parse(WATCH_NEXT_URI),
                PROJECTION,
                null,
                null,
                "last_engagement_time_utc_millis DESC",
            )?.use { cursor ->
                val iTitle = cursor.getColumnIndex("title")
                val iDesc = cursor.getColumnIndex("short_description")
                val iPkg = cursor.getColumnIndex("package_name")
                val iPoster = cursor.getColumnIndex("poster_art_uri")
                val iIntent = cursor.getColumnIndex("intent_uri")
                val iPos = cursor.getColumnIndex("last_playback_position_millis")
                val iDur = cursor.getColumnIndex("duration_millis")
                val iType = cursor.getColumnIndex("watch_next_type")
                val iEng = cursor.getColumnIndex("last_engagement_time_utc_millis")

                while (cursor.moveToNext() && raw.size < MAX_SCAN) {
                    val pkg = if (iPkg >= 0) cursor.getString(iPkg) else null
                    if (pkg.isNullOrBlank() || pkg == ctx.packageName) continue
                    val title = (if (iTitle >= 0) cursor.getString(iTitle) else null)
                        ?.takeIf { it.isNotBlank() } ?: continue
                    raw.add(
                        WatchNextItem(
                            title = title,
                            description = if (iDesc >= 0) cursor.getString(iDesc) else null,
                            packageName = pkg,
                            posterUri = if (iPoster >= 0) cursor.getString(iPoster) else null,
                            intentUri = if (iIntent >= 0) cursor.getString(iIntent) else null,
                            positionMs = if (iPos >= 0) cursor.getLong(iPos).coerceAtLeast(0L) else 0L,
                            durationMs = if (iDur >= 0) cursor.getLong(iDur).coerceAtLeast(0L) else 0L,
                            watchNextType = if (iType >= 0) cursor.getInt(iType) else Int.MAX_VALUE,
                            lastEngagementMs = if (iEng >= 0) cursor.getLong(iEng) else 0L,
                        ),
                    )
                }
            }
        }.onFailure { e ->
            // Expected on devices where READ_TV_LISTINGS/READ_CHANNELS has not been granted.
            Log.d(TAG, "Watch Next unavailable: ${e.message}")
        }

        val ranked = rankPrograms(raw)
        cached = ranked
        ranked
    }

    fun launch(ctx: Context, item: WatchNextItem): Boolean {
        val rawUri = item.intentUri
        if (!rawUri.isNullOrBlank()) {
            val launched = runCatching {
                val intent = ProviderIntentSanitizer.fromProvider(rawUri, item.packageName) ?: return@runCatching false
                ctx.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return true
        }
        return AppsRepository.launchApp(ctx, item.packageName)
    }
}
