package com.carteltv.tv.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.carteltv.tv.R
import com.carteltv.tv.data.discovery.DiscoveryFeeds
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.FilterChip
import com.carteltv.tv.ui.components.PosterTile
import com.carteltv.tv.ui.components.RailFocusLine
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.StagedScreen
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.core.suspendRunCatching

private enum class DiscoverMedia { MIXED, MOVIES, SHOWS }

private val GENRES = DiscoveryFeeds.GENRES

private data class DiscoverRow(val key: String, val title: String, val cards: List<DiscoveryFeeds.Card>)

/**
 * TMDB discovery rows: what is trending, popular, top rated, in cinemas and coming, with a
 * movie/show switch and a genre filter. Every tile opens the shared title page.
 */
@Composable
fun DiscoverScreen() {
    val key = AppPreferences.tmdbKey
    var media by remember { mutableStateOf(DiscoverMedia.MIXED) }
    var genre by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var rows by remember { mutableStateOf<List<DiscoverRow>?>(null) }

    LaunchedEffect(key, media, genre) {
        rows = null
        if (key.isBlank()) {
            rows = emptyList()
            return@LaunchedEffect
        }
        rows = if (genre != null) {            val kinds = when (media) {
                DiscoverMedia.MOVIES -> listOf("movie" to "Movies")
                DiscoverMedia.SHOWS -> listOf("tv" to "Shows")
                DiscoverMedia.MIXED -> listOf("movie" to "Movies", "tv" to "Shows")
            }
            kinds.mapNotNull { (g, label) ->
                val cards = suspendRunCatching { DiscoveryFeeds.discover(key, g, genre?.second) }.getOrDefault(emptyList())
                DiscoverRow("genre:$g", "${genre?.first} · $label", cards).takeIf { cards.isNotEmpty() }
            }
        } else {
            DiscoveryFeeds.FEEDS_ORDER.mapNotNull { feed ->
                val cards = suspendRunCatching {
                    when {
                        media == DiscoverMedia.MIXED -> DiscoveryFeeds.row(key, feed, mixTv = feed.tvPath != null)
                        media == DiscoverMedia.SHOWS -> feed.tvPath
                            ?.let { tv -> DiscoveryFeeds.row(key, feed, path = tv) }
                            ?: DiscoveryFeeds.discover(key, "tv", null)
                        else -> DiscoveryFeeds.row(key, feed)
                    }
                }.getOrDefault(emptyList()).distinctBy { it.id }
                DiscoverRow("${feed.name}:${media.name}", feed.label, cards).takeIf { cards.isNotEmpty() }
            }
        }
    }

    ScreenPage(stringResource(R.string.nav_discover), icon = R.drawable.ph_magnifying_glass) {
        StagedScreen(section = AppSection.MOVIES) {
            Column(Modifier.fillMaxSize()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal, top = ThemeSpec.Space.md).restoreRowFocus(),
                    horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
                ) {
                    item("mixed") { FilterChip("All", media == DiscoverMedia.MIXED) { media = DiscoverMedia.MIXED } }
                    item("movies") { FilterChip("Movies", media == DiscoverMedia.MOVIES) { media = DiscoverMedia.MOVIES } }
                    item("shows") { FilterChip("Shows", media == DiscoverMedia.SHOWS) { media = DiscoverMedia.SHOWS } }
                }
                LazyRow(
                    Modifier.fillMaxWidth().padding(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal).restoreRowFocus(),
                    horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
                ) {
                    item("all-genres") { FilterChip("Every genre", genre == null) { genre = null } }
                    items(GENRES, key = { "genre:${it.second}" }) { (name, id) ->
                        FilterChip(name, genre?.second == id) { genre = if (genre?.second == id) null else name to id }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    val shown = rows
                    when {
                        key.isBlank() -> EmptyState(
                            "Discover needs a TMDB key.",
                            hint = "Add a free TMDB key in Settings › Accounts to fill these rows.",
                            modifier = Modifier.fillMaxSize(),
                        )
                        shown == null -> Box(Modifier.fillMaxSize())
                        shown.isEmpty() -> EmptyState("Nothing to discover.", modifier = Modifier.fillMaxSize())
                        else -> RailFocusLine {
                            LazyColumn(
                                contentPadding = PaddingValues(
                                    start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal,
                                    top = ThemeSpec.Space.md, bottom = ThemeSpec.Space.lg,
                                ),
                                verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                            ) {
                                items(shown, key = { it.key }) { row ->
                                    Column(Modifier.fillMaxWidth()) {
                                        SectionTitle(row.title, note = row.cards.size.toString())
                                        LazyRow(
                                            Modifier.restoreRowFocus(),
                                            contentPadding = PaddingValues(vertical = 24.dp),
                                            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                                        ) {
                                            items(row.cards, key = { it.id }) { card ->
                                                PosterTile(
                                                    imageUrl = DiscoveryFeeds.posterUrl(card.poster),
                                                    title = card.title,
                                                    meta = listOfNotNull(card.year, card.rating?.let { "★ %.1f".format(it) }).joinToString(" · "),
                                                    onClick = {
                                                        val type = if (card.media == "tv") "series" else "movie"
                                                        Nav.push(
                                                            Route.Meta(
                                                                type, "tmdb:$type:${card.id}", card.title,
                                                                DiscoveryFeeds.posterUrl(card.poster),
                                                            ),
                                                        )
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
    }
}
