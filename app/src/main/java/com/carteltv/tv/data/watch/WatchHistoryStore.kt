package com.carteltv.tv.data.watch

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

object WatchHistoryStore {
    data class Match(
        val isMovie: Boolean,
        val id: Int,
        val name: String,
        val from: String,
        val backdrop: String?,
        val thumb: String?,
        val logo: String?,
        val poster: String?,
    )

    data class Entry(
        val id: String,
        val pkg: String,
        val title: String,
        val subtitle: String?,
        val positionMs: Long,
        val durationMs: Long,
        val lastWatched: Long,
        val art: String?,
        val checked: Boolean = false,
        val match: Match? = null,
    )

    private const val PREFS = "carteltv_history"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 40
    private const val MAX_AGE_MS = 60L * 24 * 3600 * 1000
    const val MAX_ITEMS = 12
    const val FINISHED = 0.95f

    private val NO_LOOKUP = setOf(
        "com.google.android.youtube.tv", "com.google.android.youtube",
        "com.google.android.youtube.tvkids", "io.gh.reisxd.tizentube.cobalt",
        "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta",
        "tv.twitch.android.app", "tv.twitch.android.viewer",
    )

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() { main.post { listeners.forEach { it() } } }

    fun all(context: Context): List<Entry> = synchronized(lock) { load(context) }

    fun record(
        context: Context, id: String, pkg: String, title: String, subtitle: String?,
        positionMs: Long, durationMs: Long, art: Bitmap?, artUrl: String?,
    ) = synchronized(lock) {
        val list = load(context).toMutableList()
        val old = list.firstOrNull { it.id == id }
        val artRef = old?.art ?: art?.let { saveArt(context, id, it) } ?: artUrl
        list.removeAll { it.id == id }
        list.add(0, Entry(id, pkg, title, subtitle, positionMs, durationMs, System.currentTimeMillis(), artRef,
            checked = old?.checked ?: false, match = old?.match))
        store(context, list)
        changed()
    }

    fun remove(context: Context, id: String) = synchronized(lock) {
        val list = load(context)
        val gone = list.filter { it.id == id }
        if (gone.isEmpty()) return@synchronized
        gone.forEach { deleteArt(it) }
        store(context, list - gone.toSet())
        changed()
    }

    fun clear(context: Context) = synchronized(lock) {
        load(context).forEach { deleteArt(it) }
        store(context, emptyList())
        changed()
    }

    fun items(context: Context): List<WatchProgress> {
        val pm = context.packageManager
        return all(context).asSequence()
            .filter { !(it.durationMs > 0 && it.positionMs >= it.durationMs * FINISHED) }
            .mapNotNull { e ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(e.pkg, 0)).toString() }
                    .getOrElse { if (it is PackageManager.NameNotFoundException) return@mapNotNull null else e.pkg }
                toItem(e, label)
            }
            .distinctBy { it.pkg + "|" + norm(it.title) }
            .take(MAX_ITEMS)
            .toList()
    }

    private fun toItem(e: Entry, appName: String): WatchProgress {
        val m = e.match
        val title = m?.name ?: e.title
        val sub = when {
            m == null -> e.subtitle
            e.title == m.from -> e.subtitle
            e.title.startsWith(m.from) -> e.title.substring(m.from.length).trim(*SEPARATORS).ifEmpty { null } ?: e.subtitle
            else -> e.title
        }?.takeIf { norm(it) != norm(title) }
        val progress = if (e.durationMs > 0) (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f) else 0f
        return WatchProgress(
            isMovie = m?.isMovie ?: false, title = title, traktId = null, imdb = null, tmdb = m?.id,
            progress = progress, runtime = if (e.durationMs >= 60_000) (e.durationMs / 60_000).toInt() else null,
            sortTime = e.lastWatched, backdrop = m?.backdrop ?: e.art, thumb = m?.thumb ?: e.art,
            logo = m?.logo, poster = m?.poster, historyId = e.id, pkg = e.pkg, appName = appName, subtitle = sub,
        )
    }

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private val SEPARATORS = charArrayOf(' ', ':', '-', '\u2013', '\u2014', '|', '\u00b7', ',')


    private fun prefs(context: Context) = context.getSharedPreferences("carteltv_history", Context.MODE_PRIVATE)

    private fun load(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
    }

    private fun store(context: Context, list: List<Entry>) {
        val now = System.currentTimeMillis()
        val (keep, drop) = list.withIndex().partition { (i, e) -> i < MAX_ENTRIES && now - e.lastWatched < MAX_AGE_MS }
        drop.forEach { deleteArt(it.value) }
        val arr = JSONArray()
        keep.forEach { arr.put(toJson(it.value)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun artDir(context: Context) = File(context.filesDir, "history").apply { mkdirs() }

    private fun saveArt(context: Context, id: String, bmp: Bitmap): String? = runCatching {
        val w = minOf(960, bmp.width)
        val h = (bmp.height.toLong() * w / bmp.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val scaled = if (w == bmp.width) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
        val f = File(artDir(context), hash(id) + ".jpg")
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        "file://" + f.absolutePath
    }.getOrNull()

    private fun deleteArt(e: Entry) {
        val a = e.art ?: return
        if (a.startsWith("file://")) runCatching { File(a.removePrefix("file://")).delete() }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    private fun toJson(e: Entry) = JSONObject().apply {
        put("id", e.id); put("pkg", e.pkg); put("title", e.title); e.subtitle?.let { put("sub", it) }
        put("pos", e.positionMs); put("dur", e.durationMs); put("at", e.lastWatched)
        e.art?.let { put("art", it) }
        put("checked", e.checked)
        e.match?.let { m ->
            put("m", JSONObject().apply {
                put("movie", m.isMovie); put("id", m.id); put("name", m.name); put("from", m.from)
                m.backdrop?.let { put("backdrop", it) }; m.thumb?.let { put("thumb", it) }
                m.logo?.let { put("logo", it) }; m.poster?.let { put("poster", it) }
            })
        }
    }

    private fun fromJson(j: JSONObject): Entry? {
        val id = j.str("id") ?: return null
        val m = j.obj("m")?.let { o ->
            Match(
                isMovie = o.optBoolean("movie"), id = o.optInt("id"),
                name = o.str("name") ?: return@let null, from = o.str("from") ?: return@let null,
                backdrop = o.str("backdrop"), thumb = o.str("thumb"), logo = o.str("logo"), poster = o.str("poster"),
            )
        }
        return Entry(
            id = id, pkg = j.str("pkg") ?: return null, title = j.str("title") ?: return null,
            subtitle = j.str("sub"), positionMs = j.optLong("pos"), durationMs = j.optLong("dur"),
            lastWatched = j.optLong("at"), art = j.str("art"), checked = j.optBoolean("checked"), match = m,
        )
    }

    private fun JSONObject.str(k: String): String? =
        if (has(k) && !isNull(k)) getString(k).takeIf { it.isNotBlank() } else null
    private fun JSONObject.obj(k: String): JSONObject? =
        if (has(k) && !isNull(k)) optJSONObject(k) else null
}
