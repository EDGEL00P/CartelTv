package com.carteltv.tv.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.nav.HomeReset
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.nav.stateKey
import com.carteltv.tv.ui.catalog.MovieDetailScreen
import com.carteltv.tv.ui.catalog.MoviesContent
import com.carteltv.tv.ui.catalog.SeriesContent
import com.carteltv.tv.ui.catalog.SeriesDetailScreen
import com.carteltv.tv.ui.catalog.DiscoverScreen
import com.carteltv.tv.ui.catalog.LibraryScreen
import com.carteltv.tv.ui.catalog.MetaDetailScreen
import com.carteltv.tv.ui.catalog.ShelvesScreen

@Composable
fun NavHostLayer() {
    val ctx = LocalContext.current
    // Dock switches swap whole stacks (Nav.switchTab); this keyed holder keeps each route's
    // rememberSaveable state — grid scroll positions, text fields — intact across the switch.
    val stateHolder = rememberSaveableStateHolder()
    stateHolder.SaveableStateProvider(Nav.current.stateKey) {
        when (val r = Nav.current) {
        is Route.Movie -> MovieDetailScreen(r.streamId, r.title, r.ext, r.icon, r.rating)
        is Route.Series -> SeriesDetailScreen(r.seriesId, r.name, r.cover, r.plot)
        is Route.Player -> LaunchedEffect(r.refId) {
            // Pop before launching: the YouTube refId path pushes Route.YouTube inside
            // AppLauncher.player, so popping afterwards would remove that route instead.
            Nav.pop()
            AppLauncher.player(ctx, r.url, r.title, r.refId)
        }
        is Route.Login -> com.carteltv.tv.ui.settings.LoginScreen(
            onLoggedIn = { Nav.resetTo(Route.Home) },
        )
        // Every screen below brings its own ScreenPage; wrapping it here again would double the rail inset and the overscan.
        Route.Search -> com.carteltv.tv.ui.search.SearchScreen(apps = com.carteltv.tv.data.apps.InstalledAppsCache.apps)
        Route.Settings -> ScreenScaffold { com.carteltv.tv.ui.settings.SettingsScreen() }
        Route.LiveTv -> com.carteltv.tv.ui.live.GuideContent()
        Route.Movies -> MoviesContent()
        Route.SeriesBrowse -> SeriesContent()
        Route.Scores -> com.carteltv.tv.ui.sports.ScoresScreen()
        Route.Weather -> com.carteltv.tv.ui.weather.WeatherScreen()
        Route.YouTube -> com.carteltv.tv.ui.youtube.YouTubeScreen()
        Route.YouTubeMusic -> com.carteltv.tv.ui.youtube.YouTubeMusicScreen()
        Route.Recents -> com.carteltv.tv.ui.recents.RecentsOverlay(onClose = { Nav.pop() })
        Route.Apps -> com.carteltv.tv.ui.apps.AppsScreen()
        Route.Guide -> com.carteltv.tv.ui.live.GuideContent()
        Route.Discover -> DiscoverScreen()
        Route.Library -> LibraryScreen()
        Route.Shelves -> ShelvesScreen()
        is Route.Meta -> MetaDetailScreen(r.type, r.id, r.title, r.poster)
        // Home owns its overscan-safe content margins; its backdrop and shelves use the whole viewport.
        Route.Home -> ScreenScaffold(bleedTop = true, bleedStart = true) {
            com.carteltv.tv.ui.home.HomeScreen(
                homeResetTick = HomeReset.tick,
                onPlay = { url, title, refId -> AppLauncher.player(ctx, url, title, refId) },
            )
        }
        }
    }
}
