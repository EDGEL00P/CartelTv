package com.carteltv.tv.data.preferences

import android.content.SharedPreferences

/**
 * Home screen preferences: visible rows, hidden apps, launch counts, section visits, onboarding.
 *
 * Extracted from [AppPreferences] — home UI state has its own responsibility.
 */
class HomePreferences(private val sp: SharedPreferences) {

    /** Canonical row ids in their default presentation order. */
    val homeRowDefaults = listOf("continue", "apps", "live", "movies", "foryou", "discovery", "youtube")

    /** Visible rows, in the user's chosen order. Unknown ids are dropped. */
    fun homeRows(): List<String> {
        val stored = sp.getString("home_rows", null)
            ?: return homeRowDefaults
        val parsed = stored.split(",").map { it.trim() }.filter { it in homeRowDefaults }
        return (parsed + homeRowDefaults.filterNot { it in parsed }).distinct()
    }

    fun setHomeRows(order: List<String>) {
        sp.edit().putString("home_rows", order.joinToString(",")).apply()
    }

    fun hideHomeRow(id: String) = setHomeRows(homeRows() - id)
    fun showHomeRowAt(id: String, index: Int = Int.MAX_VALUE) {
        val cur = homeRows() - id
        val at = index.coerceIn(0, cur.size)
        setHomeRows(cur.subList(0, at) + id + cur.drop(at))
    }

    fun hiddenApps(): MutableSet<String> =
        sp.getStringSet("hidden_apps", null)?.toMutableSet() ?: mutableSetOf()

    fun setHiddenApps(pkgs: Set<String>) {
        sp.edit().putStringSet("hidden_apps", pkgs).apply()
    }

    fun bumpLaunchCount(pkg: String) {
        val cur = launchCounts().toMutableMap()
        cur[pkg] = (cur[pkg] ?: 0) + 1
        sp.edit().putString("app_launch_counts",
            cur.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
    }

    fun launchCounts(): Map<String, Int> =
        sp.getString("app_launch_counts", null)?.split(",")?.mapNotNull {
            val p = it.split(":")
            if (p.size == 2) p[0] to (p[1].toIntOrNull() ?: 0) else null
        }?.toMap() ?: emptyMap()

    fun bumpSectionVisit(section: String) {
        val cur = sectionVisits().toMutableMap()
        cur[section] = (cur[section] ?: 0) + 1
        sp.edit().putString("section_visits", cur.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
    }

    fun sectionVisits(): Map<String, Int> =
        sp.getString("section_visits", null)?.split(",")?.mapNotNull {
            val p = it.split(":")
            if (p.size == 2) p[0] to (p[1].toIntOrNull() ?: 0) else null
        }?.toMap() ?: emptyMap()

    fun onboardingDone(): Boolean = sp.getBoolean("onboarded", false)
    fun setOnboardingDone() { sp.edit().putBoolean("onboarded", true).apply() }
}
