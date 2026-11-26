package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.data.provider.StreamCredentials
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Continue-watching / recents preferences.
 *
 * Extracted from [AppPreferences] — playback history and the recents list have their own responsibility.
 * Entry grammar: ref|title|url[|poster|positionMs|durationMs]. v1 entries (3 fields) stay legal.
 */
class WatchHistoryPreferences(
    private val sp: SharedPreferences,
    private val json: Json,
    private val providerPreferences: ProviderPreferences,
    private val profileId: () -> String,
) {

    @Serializable
    data class RecentEntry(
        val ref: String, val title: String, val url: String,
        val poster: String = "", val positionMs: Long = 0, val durationMs: Long = 0,
    )

    /** Recents are per profile: the history of the person watching is not the history of the house. */
    private fun recentsKey(): String = "recents_${profileId()}"

    fun recents(): List<String> {
        val key = recentsKey()
        if (!sp.contains(key)) {
            // First read after the update: the one global history becomes this profile's starting point.
            val legacy = sp.getString("recents", null)
            if (!legacy.isNullOrBlank()) sp.edit().putString(key, legacy).apply()
        }
        return sp.getString(key, null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
    }

    fun replaceRecents(entries: List<String>) {
        sp.edit().putString(recentsKey(), entries.joinToString("\n")).apply()
    }

    fun removeRecent(entry: String) {
        replaceRecents(recents() - redactStreamCredentials(entry))
    }

    /** Drops a continue-watching card by ref, whatever entry grammar stored it. */
    fun removeRecentByRef(ref: String) {
        replaceRecents(recents().filter { parseRecent(it).ref != ref })
    }

    /** Continue-watching hides: entries the user dismissed from Home.
        Keys are `pkg:title` for system Watch Next rows. Bounded to 200. ── */
    fun hiddenWatchNext(): Set<String> =
        sp.getString("cw_hidden_wn_${profileId()}", null)?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun hideWatchNext(key: String) {
        val cur = ((hiddenWatchNext() + key).toList().takeLast(200)).toSet()
        sp.edit().putString("cw_hidden_wn_${profileId()}", cur.joinToString("\n")).apply()
    }

    fun addRecent(entry: String) {
        val cur = (listOf(redactStreamCredentials(entry)) + recents()).distinct().take(30)
        replaceRecents(cur)
    }

    fun recentEntries(): List<RecentEntry> = recents().map(::parseRecent)

    fun parseRecent(raw: String): RecentEntry {
        val p = raw.split("|")
        return RecentEntry(
            ref = p.getOrElse(0) { "" },
            title = p.getOrElse(1) { "?" },
            url = restoreStreamCredentials(p.getOrElse(2) { "" }),
            poster = p.getOrNull(3) ?: "",
            positionMs = p.getOrNull(4)?.toLongOrNull() ?: 0,
            durationMs = p.getOrNull(5)?.toLongOrNull() ?: 0,
        )
    }

    fun encodeRecent(e: RecentEntry): String =
        listOf(e.ref, e.title.replace("|", " "), redactStreamCredentials(e.url), e.poster,
            e.positionMs.toString(), e.durationMs.toString()).joinToString("|")

    /** Upsert by ref (any entry grammar), carrying forward poster when the writer omits it. */
    fun notePlayback(ref: String, title: String, url: String, poster: String,
                     positionMs: Long, durationMs: Long) {
        val prior = recentEntries().firstOrNull { it.ref == ref }
        val merged = RecentEntry(
            ref = ref, title = title, url = url,
            poster = poster.ifBlank { prior?.poster.orEmpty() },
            positionMs = positionMs.coerceAtLeast(0),
            durationMs = durationMs.coerceAtLeast(0),
        )
        val rest = recents().filter { parseRecent(it).ref != ref }
        replaceRecents((listOf(encodeRecent(merged)) + rest).take(30))
    }

    /** countdown widget target (epoch ms); 0 = unset */
    var countdownTargetMs: Long
        get() = sp.getLong("countdown_ts", 0)
        set(v) = sp.edit().putLong("countdown_ts", v).apply()

    private fun redactStreamCredentials(text: String): String = StreamCredentials.redact(text)

    private fun restoreStreamCredentials(url: String): String {
        if (StreamCredentials.USER !in url) return url
        val c = providerPreferences.credentials ?: return ""
        return StreamCredentials.restore(url, c.user, c.pass)
    }
}
