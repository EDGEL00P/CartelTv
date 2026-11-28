package com.carteltv.tv.ui.home

import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.watch.WatchHistoryStore

/** A title reduced to what makes two spellings the same. */
internal fun titleKey(raw: String): String = raw.lowercase().filter { it.isLetterOrDigit() }

/** The player's own resume points that stand on Up Next: no live channels, one card per show, the episode played last first. */
internal fun resumeRecents(recents: List<AppPreferences.RecentEntry>, limit: Int): List<AppPreferences.RecentEntry> =
    recents
        .filterNot { it.ref.startsWith("live:") }
        .filter { WatchHistoryStore.presentable(splitTitle(it.title).name) }
        .distinctBy { titleKey(splitTitle(it.title).name) }
        .take(limit)

/** [candidates] without any whose title is already among [shown], however it is spelled there. Order is kept. */
internal fun <T> unseenTitles(candidates: List<T>, shown: Iterable<String>, title: (T) -> String): List<T> {
    val names = shown.map(::titleKey).toSet()
    return candidates.filter { titleKey(title(it)) !in names }
}

/** The mark of the league an event belongs to, when one of the leagues on show is that league, either way cased. */
internal fun leagueMark(marks: Map<String, String>, sport: String): String? =
    marks.entries.firstOrNull { (league, _) -> league.equals(sport, ignoreCase = true) }?.value
