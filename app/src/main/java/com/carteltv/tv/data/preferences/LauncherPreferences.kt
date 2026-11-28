package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.data.apps.LauncherApp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Launcher preferences: categories, custom rows, folders, row arrangement, app switcher,
 * icon pack, watch country, streaming-app integration.
 *
 * Extracted from [AppPreferences] — launcher UI state has its own responsibility.
 */
class LauncherPreferences(private val sp: SharedPreferences) {

    /* ═════════ CATEGORY SYSTEM (Monet-style app launcher) ═════════ */

    /** App category: a user-defined group of apps with a name and icon. */
    @Serializable
    data class AppCategory(
        val id: String,
        val name: String,
        val icon: String = "📁",
        val appPkgs: List<String> = emptyList(),
    )

    private val categoryJson get() = Json { ignoreUnknownKeys = true }

    fun categories(): List<AppCategory> {
        val raw = sp.getString("app_categories", null) ?: return emptyList()
        return runCatching {
            categoryJson.decodeFromString<List<AppCategory>>(raw)
        }.getOrDefault(emptyList())
    }

    fun setCategories(list: List<AppCategory>) {
        sp.edit().putString("app_categories", categoryJson.encodeToString(list)).apply()
    }

    fun addCategory(cat: AppCategory) {
        setCategories(categories() + cat)
    }

    fun removeCategory(id: String) {
        setCategories(categories().filter { it.id != id })
    }

    fun updateCategory(updated: AppCategory) {
        setCategories(categories().map { if (it.id == updated.id) updated else it })
    }

    fun addAppToCategory(catId: String, pkg: String) {
        val cur = categories().map {
            if (it.id == catId && pkg !in it.appPkgs) it.copy(appPkgs = it.appPkgs + pkg) else it
        }
        setCategories(cur)
    }

    fun removeAppFromCategory(catId: String, pkg: String) {
        val cur = categories().map {
            if (it.id == catId) it.copy(appPkgs = it.appPkgs - pkg) else it
        }
        setCategories(cur)
    }

    /** Whether the launcher shows categories (true) or the old flat rows (false). */
    var categoryMode: Boolean
        get() = sp.getBoolean("category_mode", false)
        set(v) = sp.edit().putBoolean("category_mode", v).apply()

    /** Generate a short unique ID for new categories. */
    fun nextCategoryId(): String = "cat_${System.currentTimeMillis().toString(36)}"

    /** Custom row types — mirrors Monet's row system. */
    enum class RowType {
        /** Auto-populated from app categories (games, video, music, etc.) */
        Category,
        /** User manually picks individual apps */
        Manual,
        /** Fed by a media source app (Watch Next, TV channels) */
        Media,
    }

    @Serializable
    data class CustomRow(
        val id: String,
        val name: String,
        val type: RowType = RowType.Manual,
        /** For Manual: explicit app list. For Category: apps matching the category tag. */
        val appPkgs: List<String> = emptyList(),
        /** For Category rows: which app category to auto-populate from. */
        val categoryTag: String = "",
        /** For Media rows: the source app package name. */
        val mediaSource: String = "",
        /** Max apps to show in this row (0 = unlimited). */
        val maxApps: Int = 0,
        /** Whether this row is visible on Home. */
        val visible: Boolean = true,
        /** Whether to show the row name header. */
        val showName: Boolean = true,
    )

    @Serializable
    data class AppFolder(
        val id: String,
        val name: String,
        val appPkgs: List<String> = emptyList(),
        /** "manual" | "az" | "most_used" */
        val sortMode: String = "manual",
    )

    private val folderJson get() = Json { ignoreUnknownKeys = true }
    private data class FolderCache(val raw: String?, val folders: List<AppFolder>)
    @Volatile private var folderCache = FolderCache(null, emptyList())

    fun folders(): List<AppFolder> {
        val raw = sp.getString("app_folders", null)
        val cur = folderCache
        if (cur.raw == raw) return cur.folders
        val parsed = raw?.let { runCatching { folderJson.decodeFromString<List<AppFolder>>(it) }.getOrDefault(emptyList()) }.orEmpty()
        folderCache = FolderCache(raw, parsed)
        return parsed
    }

    fun setFolders(list: List<AppFolder>) {
        sp.edit().putString("app_folders", folderJson.encodeToString(list)).apply()
        folderCache = FolderCache(null, emptyList())
    }

    fun addFolder(folder: AppFolder) {
        setFolders(folders() + folder)
    }

    fun removeFolder(id: String) {
        setFolders(folders().filter { it.id != id })
    }

    fun updateFolder(updated: AppFolder) {
        setFolders(folders().map { if (it.id == updated.id) updated else it })
    }

    fun nextFolderId(): String = "fld_${System.currentTimeMillis().toString(36)}"

    /** Which folder (if any) an app belongs to. */
    fun folderForApp(pkg: String): AppFolder? = folders().firstOrNull { pkg in it.appPkgs }

    /** Apps not in any folder. */
    fun unfiledApps(allApps: List<LauncherApp>): List<LauncherApp> {
        val filed = folders().flatMap { it.appPkgs }.toSet()
        return allApps.filter { it.packageName !in filed }
    }

    /** Row arrangement: "horizontal" (default) | "grid" */
    var rowArrangement: String
        get() = sp.getString("row_arrangement", "horizontal")?.takeIf { it in listOf("horizontal", "grid") } ?: "horizontal"
        set(v) = sp.edit().putString("row_arrangement", v).apply()

    /** Row alignment: "left" (default) | "center" | "right" */
    var rowAlignment: String
        get() = sp.getString("row_alignment", "left")?.takeIf { it in listOf("left", "center", "right") } ?: "left"
        set(v) = sp.edit().putString("row_alignment", v).apply()

    /** Show row name headers globally. */
    var showAllRowNames: Boolean
        get() = sp.getBoolean("show_all_row_names", true)
        set(v) = sp.edit().putBoolean("show_all_row_names", v).apply()

    /** Show card titles globally. */
    var showCardTitles: Boolean
        get() = sp.getBoolean("show_card_titles", true)
        set(v) = sp.edit().putBoolean("show_card_titles", v).apply()

    /** Centered app icons in rows. */
    var centeredIcons: Boolean
        get() = sp.getBoolean("centered_icons", false)
        set(v) = sp.edit().putBoolean("centered_icons", v).apply()

    /** Single row mode — only one row expanded at a time. */
    var singleRowMode: Boolean
        get() = sp.getBoolean("single_row_mode", false)
        set(v) = sp.edit().putBoolean("single_row_mode", v).apply()

    /** Carousel mode — rows wrap around. */
    var carouselMode: Boolean
        get() = sp.getBoolean("carousel_mode", false)
        set(v) = sp.edit().putBoolean("carousel_mode", v).apply()

    /** Max entries in the app switcher (recents). */
    var switcherMaxEntries: Int
        get() = sp.getInt("switcher_max", 8).coerceIn(1, 20)
        set(v) = sp.edit().putInt("switcher_max", v.coerceIn(1, 20)).apply()

    /** Don't kill apps when switching away. */
    var switcherNoKill: Boolean
        get() = sp.getBoolean("switcher_no_kill", false)
        set(v) = sp.edit().putBoolean("switcher_no_kill", v).apply()

    /** Solid glass surfaces for viewers who need steadier contrast. */
    var reduceTransparency: Boolean
        get() = sp.getBoolean("glass_reduce_transparency", false)
        set(v) = sp.edit().putBoolean("glass_reduce_transparency", v).apply()

    /** Active ADW/Nova/Go icon pack package name; empty = stock app icons. */
    var iconPack: String
        get() = sp.getString("icon_pack", "") ?: ""
        set(v) = sp.edit().putString("icon_pack", v.trim()).apply()

    /** Show Android TV system app preview channels on Home when READ_TV_LISTINGS is granted. */
    var showAppChannels: Boolean
        get() = sp.getBoolean("show_app_channels", true)
        set(v) = sp.edit().putBoolean("show_app_channels", v).apply()

    /** Two-letter ISO country code for streaming-app deep links & TMDB watch providers; empty = system locale. */
    var watchCountry: String
        get() = sp.getString("watch_country", "") ?: ""
        set(v) = sp.edit().putString("watch_country", v.trim().uppercase()).apply()

    /** When enabled, clicking a discovery/platform title opens its page directly in Netflix/Apple TV/Prime/Disney+. */
    var playInStreamingApp: Boolean
        get() = sp.getBoolean("play_in_streaming_app", false)
        set(v) = sp.edit().putBoolean("play_in_streaming_app", v).apply()

    /** Allow JustWatch GraphQL offer resolution when Wikidata lacks a streaming deep link. */
    var platformLinksJustWatch: Boolean
        get() = sp.getBoolean("platform_links_jw", true)
        set(v) = sp.edit().putBoolean("platform_links_jw", v).apply()
}
