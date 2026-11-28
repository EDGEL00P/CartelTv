package com.carteltv.tv.data.provider

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.carteltv.tv.AppScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.carteltv.tv.data.apps.AppsRepository
import com.carteltv.tv.core.ProviderIntentSanitizer

/**
 * Android TV system App Channels reader.
 *
 * Reads `TYPE_PREVIEW` channels (`content://android.media.tv/channel`) and their
 * `preview_program` rows plus per-app `watch_next_program` rows, watches all three
 * URIs with a debounced [ContentObserver], and scopes package-less web intents back
 * to the originating streaming app so they open natively instead of in a browser.
 */
object ChannelRepository {

    private const val TAG = "ChannelRepository"
    private val CHANNELS_URI: Uri by lazy { Uri.parse("content://android.media.tv/channel") }
    private val PROGRAMS_URI: Uri by lazy { Uri.parse("content://android.media.tv/preview_program") }
    private val WATCH_NEXT_URI: Uri by lazy { Uri.parse("content://android.media.tv/watch_next_program") }
    const val PERMISSION = "android.permission.READ_TV_LISTINGS"
    const val WATCH_NEXT_TITLE = "Continue watching"

    data class Program(
        val id: Long,
        val title: String,
        val subtitle: String,
        val posterUri: String,
        val intentUri: String,
        val wide: Boolean,
        val progress: Float,
        val engagedAtMs: Long = 0L,
    )

    data class Channel(
        val id: Long,
        val packageName: String,
        val displayName: String,
        val programs: List<Program>,
    ) {
        val key: String get() = "$packageName|$displayName"
    }

    val channels = mutableStateOf<List<Channel>>(emptyList())

    @Volatile private var started = false
    @Volatile private var refreshPending = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun hasAccess(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Pure ordering helper: sorts programs by latest engagement time descending, then ID descending. */
    fun sortWatchNext(programs: List<Program>): List<Program> =
        programs.sortedWith(
            compareByDescending<Program> { it.engagedAtMs }
                .thenByDescending { it.id },
        )

    /** Pure aspect-ratio classifier matching Android TV `TvContract.PreviewPrograms.ASPECT_RATIO_*`. */
    fun isWideAspectRatio(aspectRatio: Int): Boolean = aspectRatio <= 2

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!hasAccess(app)) return
        if (!started) {
            runCatching {
                val obs = object : ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        refresh(app)
                    }
                }
                for (uri in listOf(CHANNELS_URI, PROGRAMS_URI, WATCH_NEXT_URI)) {
                    app.contentResolver.registerContentObserver(uri, true, obs)
                }
                started = true
            }.onFailure { Log.w(TAG, "ContentObserver registration failed", it) }
        }
        refresh(app)
    }

    fun refresh(ctx: Context) {
        if (refreshPending) return
        refreshPending = true
        val app = ctx.applicationContext
        AppScope.io.launch {
            delay(400L)
            refreshPending = false
            if (!hasAccess(app)) return@launch
            readBlocking(app)
                .onSuccess { list -> mainHandler.post { channels.value = list } }
                .onFailure { Log.d(TAG, "Channels unavailable: ${it.message}") }
        }
    }

    fun readBlocking(ctx: Context): Result<List<Channel>> = runCatching {
        val meta = ArrayList<Triple<Long, String, String>>()
        ctx.contentResolver.query(
            CHANNELS_URI,
            arrayOf("_id", "package_name", "display_name", "type"),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(3) != "TYPE_PREVIEW") continue
                meta += Triple(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
            }
        } ?: error("TV channel provider returned null cursor")

        val byChannel = queryPrograms(ctx, PROGRAMS_URI, keyColumn = "channel_id")
        val watchNextByPkg = runCatching {
            queryPrograms(ctx, WATCH_NEXT_URI, keyColumn = "package_name")
        }.getOrDefault(emptyMap())

        val combined = buildList {
            watchNextByPkg.entries.forEachIndexed { idx, (pkg, items) ->
                if (items.isNotEmpty()) {
                    add(Channel(-1L - idx, pkg, WATCH_NEXT_TITLE, sortWatchNext(items)))
                }
            }
            for ((id, pkg, name) in meta) {
                val items = byChannel[id.toString()]?.takeIf { it.isNotEmpty() } ?: continue
                add(Channel(id, pkg, name.ifBlank { "Recommended" }, items))
            }
        }
        combined.filterNot { it.packageName == ctx.packageName }
    }

    private fun queryPrograms(ctx: Context, uri: Uri, keyColumn: String): Map<String, List<Program>> {
        val out = LinkedHashMap<String, ArrayList<Program>>()
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val iId = c.getColumnIndex("_id")
            val iKey = c.getColumnIndex(keyColumn)
            val iTitle = c.getColumnIndex("title")
            val iEp = c.getColumnIndex("episode_title")
            val iPoster = c.getColumnIndex("poster_art_uri")
            val iThumb = c.getColumnIndex("thumbnail_uri")
            val iIntent = c.getColumnIndex("intent_uri")
            val iAr = c.getColumnIndex("poster_art_aspect_ratio")
            val iPos = c.getColumnIndex("last_playback_position_millis")
            val iDur = c.getColumnIndex("duration_millis")
            val iBrowsable = c.getColumnIndex("browsable")
            val iSeen = c.getColumnIndex("last_engagement_time_utc_millis")
            if (iKey < 0 || iIntent < 0) return out

            fun str(idx: Int): String = if (idx >= 0 && !c.isNull(idx)) c.getString(idx).orEmpty() else ""

            while (c.moveToNext()) {
                if (iBrowsable >= 0 && !c.isNull(iBrowsable) && c.getInt(iBrowsable) == 0) continue
                val poster = str(iPoster).ifBlank { str(iThumb) }
                val intentUri = str(iIntent)
                val title = str(iTitle)
                if (intentUri.isBlank() || (poster.isBlank() && title.isBlank())) continue
                val id = if (iId >= 0 && !c.isNull(iId)) c.getLong(iId) else 0L
                val pos = if (iPos >= 0 && !c.isNull(iPos)) c.getLong(iPos) else 0L
                val dur = if (iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) else 0L
                val ar = if (iAr >= 0 && !c.isNull(iAr)) c.getInt(iAr) else 0
                val engaged = if (iSeen >= 0 && !c.isNull(iSeen)) c.getLong(iSeen) else 0L
                val progress = if (dur > 0L && pos > 0L) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                out.getOrPut(str(iKey)) { ArrayList() } += Program(
                    id = id,
                    title = title,
                    subtitle = str(iEp),
                    posterUri = poster,
                    intentUri = intentUri,
                    wide = isWideAspectRatio(ar),
                    progress = progress,
                    engagedAtMs = engaged,
                )
            }
        }
        return out
    }

    /** Opens a program inside its originating app; falls back to package launch on failure. */
    fun open(ctx: Context, packageName: String, program: Program): Boolean {
        val launched = runCatching {
            val intent = ProviderIntentSanitizer.fromProvider(program.intentUri, packageName) ?: return@runCatching false
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
        if (launched) return true
        return AppsRepository.launchApp(ctx, packageName)
    }
}
