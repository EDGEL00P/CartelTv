package com.carteltv.tv.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.PosterTile
import com.carteltv.tv.ui.components.RailFocusLine
import com.carteltv.tv.ui.components.StagedScreen
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.home.resumeRecents
import com.carteltv.tv.ui.nav.PagePadding
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.theme.ThemeSpec

@Composable
fun LibraryScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val recents = remember(tick) { resumeRecents(AppPreferences.recentEntries(), Int.MAX_VALUE) }
    val favourites = remember(tick) { AppPreferences.favIds().toList() }
    val saved = remember(tick) { AppPreferences.libraryEntries() }
    var trakt by remember(tick) { mutableStateOf<List<com.carteltv.tv.data.trakt.TraktClient.WatchlistItem>>(emptyList()) }
    LaunchedEffect(tick) {
        if (com.carteltv.tv.data.trakt.TraktAuth.client != null) {
            trakt =
                com.carteltv.tv.core
                    .suspendRunCatching {
                        com.carteltv.tv.data.trakt.TraktAuth
                            .watchlist()
                    }.getOrDefault(emptyList())
        }
    }

    // The player writes resume points while it is away; refresh when the page comes back.
    LaunchedEffect(Unit) { tick++ }

    ScreenPage {
        StagedScreen(section = AppSection.MOVIES) {
            if (recents.isEmpty() && favourites.isEmpty() && saved.isEmpty() && trakt.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(PagePadding)) {
                    Spacer(Modifier.height(ThemeSpec.Space.lg))
                    EmptyState(
                        "Nothing in your library yet.",
                        hint = "Titles you start watching and films you favourite collect here.",
                    )
                }
            } else {
                // The billboard leads with what you are mid-way through, then the shelves follow — the same read as On Demand.
                val hero =
                    recents.firstOrNull()?.let { e ->
                        HeroPick(e.title, e.poster.ifBlank { null }, null, "Resume") {
                            AppLauncher.player(ctx, e.url, e.title, e.ref)
                        }
                    }
                RailFocusLine {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding =
                            PaddingValues(
                                start = ThemeSpec.Space.margin,
                                end = ThemeSpec.Safe.horizontal,
                                top = ThemeSpec.Space.md,
                                bottom = ThemeSpec.Space.margin,
                            ),
                        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                    ) {
                        if (hero != null) item(key = "hero") { CatalogHero(hero) }
                        if (saved.isNotEmpty()) {
                            item(key = "saved") {
                                PosterRail("Saved", count = saved.size) {
                                    items(saved, key = { it.id }) { entry ->
                                        PosterTile(
                                            imageUrl = entry.poster.ifBlank { null },
                                            title = entry.name,
                                            meta = entry.year.ifBlank { null },
                                            modifier =
                                                if (entry === saved.first() &&
                                                    recents.isEmpty()
                                                ) {
                                                    Modifier.entryFocus()
                                                } else {
                                                    Modifier
                                                },
                                            onLongClick = {
                                                com.carteltv.tv.ui.components.TvDialogs.confirm(
                                                    "Remove from library?",
                                                    entry.name,
                                                    destructive = true,
                                                ) {
                                                    AppPreferences.removeFromLibrary(entry.id)
                                                    tick++
                                                }
                                            },
                                            onClick = { routeFor(entry.type, entry.id, entry.name, entry.poster)?.let { Nav.push(it) } },
                                        )
                                    }
                                }
                            }
                        }
                        if (trakt.isNotEmpty()) {
                            item(key = "trakt") {
                                PosterRail("Trakt watchlist", count = trakt.size) {
                                    items(trakt, key = { "${it.imdb}:${it.title}" }) { item ->
                                        val type = if (item.series) "series" else "movie"
                                        val ref =
                                            item.imdb?.let { "stremio:$it" }
                                                ?: item.tmdb?.let { "tmdb:$type:$it" }
                                        PosterTile(
                                            imageUrl = null,
                                            title = item.title,
                                            meta = item.year?.toString(),
                                            badge = "TRAKT",
                                            onClick = {
                                                if (ref != null) Nav.push(Route.Meta(type, ref, item.title, null))
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (recents.isNotEmpty()) {
                            item(key = "cw") {
                                PosterRail("Continue watching", count = recents.size) {
                                    items(recents, key = { it.ref }) { entry ->
                                        PosterTile(
                                            imageUrl = entry.poster.takeIf { it.isNotBlank() },
                                            title = entry.title,
                                            progress = if (entry.durationMs > 0) (entry.positionMs.toFloat() / entry.durationMs) else 0f,
                                            modifier = if (entry === recents.first()) Modifier.entryFocus() else Modifier,
                                            onClick = { AppLauncher.player(ctx, entry.url, entry.title, entry.ref) },
                                        )
                                    }
                                }
                            }
                        }
                        if (favourites.isNotEmpty()) {
                            item(key = "fav") {
                                PosterRail("Favourites", count = favourites.size) {
                                    items(favourites, key = { it }) { raw ->
                                        val parts = raw.split('|')
                                        val ref = parts[0]
                                        val title = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: ref
                                        val url = parts.getOrNull(2).orEmpty()
                                        PosterTile(
                                            imageUrl = null,
                                            title = title,
                                            badge = if (ref.startsWith("movie:")) "MOVIE" else null,
                                            onClick = {
                                                val type = if (ref.startsWith("series:")) "series" else "movie"
                                                val target = routeFor(type, ref, title, null)
                                                if (target != null) {
                                                    Nav.push(target)
                                                } else if (url.isNotBlank()) {
                                                    AppLauncher.player(ctx, url, title, ref)
                                                }
                                            },
                                            onLongClick = {
                                                AppPreferences.toggleFav(ref)
                                                tick++
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A saved ref opens its title page: add-on and TMDB refs by id, provider refs by their own page. */
private fun routeFor(
    type: String,
    id: String,
    name: String,
    poster: String?,
): Route? =
    when {
        id.startsWith("stremio:") -> {
            Route.Meta(type, id.removePrefix("stremio:"), name, poster)
        }

        id.startsWith("tmdb:") -> {
            Route.Meta(type, id, name, poster)
        }

        id.startsWith("movie:") -> {
            id.removePrefix("movie:").toLongOrNull()?.let { Route.Movie(it, name, null, poster, null) }
        }

        id.startsWith("series:") -> {
            id
                .removePrefix("series:")
                .substringBefore('/')
                .toLongOrNull()
                ?.let { Route.Series(it, name, poster, null) }
        }

        else -> {
            null
        }
    }
