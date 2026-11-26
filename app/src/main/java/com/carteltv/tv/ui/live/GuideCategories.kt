package com.carteltv.tv.ui.live

import com.carteltv.tv.data.xtream.XtreamClient

/**
 * The few categories a viewer thinks in (News, Sports, Movies ...), read from the provider's own group names so
 * "US| NEWS HD" and "UK| SKY SPORTS" land in the right place without anyone sorting thousands of groups by hand.
 */
internal object GuideCategories {

    data class Bucket(val key: String, val label: String, private val words: String) {
        private val pattern = Regex("\\b($words)", RegexOption.IGNORE_CASE)
        fun matches(groupName: String): Boolean = pattern.containsMatchIn(groupName)
    }

    // Order matters: the first bucket that matches a group wins, so "Kids Movies" is Kids and "Sports News" is Sports.
    val all: List<Bucket> = listOf(
        Bucket("kids", "Kids", "kids?|child|cartoon|disney|nick|junior|toon"),
        Bucket("sports", "Sports", "sports?|nfl|nba|nhl|mlb|ufc|wwe|boxing|football|soccer|cricket|rugby|golf|tennis|f1|formula|racing|espn|motorsport|league|premier"),
        Bucket("news", "News", "news|current affairs|headline"),
        Bucket("movies", "Movies", "movies?|cinema|films?|cine"),
        Bucket("docs", "Documentaries", "documentar|DiscoveryFeeds|nature|wildlife|history|science|travel|geographic"),
        Bucket("music", "Music", "music|mtv|radio|concert"),
        Bucket("entertainment", "Entertainment", "entertainment|general|series|drama|comedy|lifestyle|reality|variety"),
    )

    fun bucketOf(groupName: String): Bucket? = all.firstOrNull { it.matches(groupName) }

    /** The buckets that have at least one group, in display order. */
    fun present(groups: List<XtreamClient.Category>): List<Bucket> {
        val used = groups.mapNotNull { bucketOf(it.category_name)?.key }.toSet()
        return all.filter { it.key in used }
    }

    /** Provider group ids that belong to [key], narrowed to one [region] when given. */
    fun idsFor(key: String, groups: List<XtreamClient.Category>, region: String? = null): Set<String> =
        groups.filter { bucketOf(it.category_name)?.key == key && (region == null || regionOf(it.category_name) == region) }
            .map { it.category_id }.toSet()

    private val regionPrefix = Regex("^\\W*([A-Za-z]{2,3})(?:\\]|\\s*[|:\\-])")

    /** The "US" in "US| NEWS HD" or "[UK] Sky Sports"; null when the provider gives no prefix. */
    fun regionOf(groupName: String): String? = regionPrefix.find(groupName)?.groupValues?.get(1)?.uppercase()

    /** Regions inside [key], sorted; empty unless there are at least two, so a lone region never needs a second row. */
    fun regionsFor(key: String, groups: List<XtreamClient.Category>): List<String> =
        groups.filter { bucketOf(it.category_name)?.key == key }.mapNotNull { regionOf(it.category_name) }
            .distinct().sorted().takeIf { it.size >= 2 }.orEmpty()

    /** A pick like "cat:news" or "cat:news:US" as bucket key and optional region; null for any other pick. */
    fun parsePick(pick: String): Pair<String, String?>? {
        if (!pick.startsWith("cat:")) return null
        val parts = pick.removePrefix("cat:").split(':', limit = 2)
        return parts[0] to parts.getOrNull(1)
    }
}
