package com.carteltv.tv.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.carteltv.tv.ui.nav.ShellChrome

/** Type-safe route stack with real Back handling. */
sealed interface Route {
    data object Home : Route
    data object Apps : Route
    data object Login : Route
    data object Search : Route
    data object Settings : Route
    data object LiveTv : Route
    data object Movies : Route
    data object SeriesBrowse : Route
    data object Scores : Route
    data object Weather : Route
    data object Guide : Route
    data object YouTube : Route
    data object YouTubeMusic : Route
    data object Recents : Route
    data object Library : Route
    data object Discover : Route
    data object Shelves : Route
    data class Meta(
        val type: String, val id: String, val title: String, val poster: String?,
    ) : Route
    data class Movie(
        val streamId: Long, val title: String, val ext: String?,
        val icon: String?, val rating: String?,
    ) : Route
    data class Series(val seriesId: Long, val name: String, val cover: String?, val plot: String?) : Route
    data class Player(val url: String, val title: String, val refId: String) : Route
}

object Nav {
    val stack: SnapshotStateList<Route> = mutableStateListOf(Route.Home)

    /** Each dock section keeps its own stack while another section is on screen. */
    private val savedStacks = mutableMapOf<AppSection, List<Route>>()

    fun push(r: Route) { stack.add(r) }
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        // The disposed route's requester must not win over identity restoration in the restored route.
        ShellChrome.lastContentFocus = null
        return true
    }
    fun resetTo(r: Route) {
        savedStacks.clear()
        stack.clear()
        stack.add(r)
        ShellChrome.lastContentFocus = null
    }
    val current: Route get() = stack.last()

    /** The dock section that owns the on-screen stack: the last full-section route in it. */
    private fun owningTab(): AppSection? = stack.lastOrNull { SectionNav.tabOf(it) != null }?.let { SectionNav.tabOf(it) }

    /**
     * Dock switching: preserve the active section's stack under its own tab and restore
     * the target tab's stack (rooting it at the section route when never visited), so
     * returning to a section resumes where the user left it.
     */
    fun switchTab(tab: AppSection) {
        val target = tab.route
        if (current == target && savedStacks[tab] == null) return
        ShellChrome.lastContentFocus = null
        owningTab()?.let { savedStacks[it] = stack.toList() }
        val restored = savedStacks.remove(tab)
        stack.clear()
        if (restored != null) {
            stack.addAll(restored)
        } else {
            stack.add(Route.Home)
            if (target != Route.Home) stack.add(target)
        }
    }
}

/** Stable per-route identity used to key saved composition state across tab switches. */
val Route.stateKey: String
    get() = when (this) {
        Route.Home -> "home"
        Route.Apps -> "apps"
        Route.Login -> "login"
        Route.Search -> "search"
        Route.Settings -> "settings"
        Route.LiveTv -> "liveTv"
        Route.Movies -> "movies"
        Route.Library -> "library"
        Route.Discover -> "discover"
        Route.Shelves -> "shelves"
        Route.SeriesBrowse -> "seriesBrowse"
        Route.Scores -> "scores"
        Route.Weather -> "weather"
        Route.Guide -> "guide"
        Route.YouTube -> "youTube"
        Route.YouTubeMusic -> "youTubeMusic"
        Route.Recents -> "recents"
        is Route.Meta -> "meta_${id}"
        is Route.Movie -> "movie_$streamId"
        is Route.Series -> "series_$seriesId"
        is Route.Player -> "player_$refId"
    }
