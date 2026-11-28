package com.carteltv.tv.nav
import androidx.annotation.StringRes
import com.carteltv.tv.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * The dock's registry and shell state. These live in the root nav package, not ui.nav, because
 * data-layer code (AppPreferences, KeyRemapService) persists and dispatches by dock section; ui.nav draws from them.
 */

/** The dock's single registry: every section is one entry naming its label and its shell route. */
enum class AppSection(@StringRes val label: Int, val route: Route) {
    HOME(R.string.nav_home, Route.Home),
    APPS(R.string.nav_apps, Route.Apps),
    LIVE(R.string.nav_live, Route.LiveTv),
    GUIDE(R.string.nav_guide, Route.Guide),
    SPORTS(R.string.nav_sports, Route.Scores),
    WEATHER(R.string.nav_weather, Route.Weather),
    MOVIES(R.string.nav_on_demand, Route.Movies),
    TV(R.string.nav_tv_shows, Route.SeriesBrowse),
    LIBRARY(R.string.nav_library, Route.Library),
    DISCOVER(R.string.nav_discover, Route.Discover),
    SHELVES(R.string.nav_shelves, Route.Shelves),
    YOUTUBE(R.string.nav_youtube, Route.YouTube),
    YOUTUBEMUSIC(R.string.nav_music, Route.YouTubeMusic),
    RECENTS(R.string.nav_recents, Route.Recents),
    SEARCH(R.string.nav_search, Route.Search),
    SETUP(R.string.nav_settings, Route.Settings);
}

/** Single-shell route dispatcher: opens a section's full screen and remembers its place in the dock. */
object SectionNav {
    /** The dock section a route belongs to, or null for full-page routes (details, login, player) that carry no rail. */
    fun tabOf(route: Route): AppSection? =
        AppSection.entries.firstOrNull { it.route == route }

    fun go(tab: AppSection) {
        if (tabOf(Nav.current) == tab) return
        Nav.switchTab(tab)
    }
}

/** Bumped whenever Home must reset to its top (Back lock, remote HOME press); HomeScreen rebuilds its shelves. */
object HomeReset {
    var tick by androidx.compose.runtime.mutableIntStateOf(0)
}
