package com.carteltv.tv.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.data.discovery.DiscoveryFeeds
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.ui.settings.Caption
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.StagedScreen
import com.carteltv.tv.ui.components.TvDialogs
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.components.glassRow
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec

/**
 * Shelves are On Demand's custom catalogs: a name, a media kind and a genre, rendered as a rail
 * between the add-on rows. Managing them is deliberately small — create, retarget, remove.
 */
@Composable
fun ShelvesScreen() {
    var tick by remember { mutableIntStateOf(0) }
    val shelves = remember(tick) { AppPreferences.shelves() }

    ScreenPage {
        StagedScreen(section = AppSection.MOVIES) {
            LazyColumn(contentPadding = PagePadding) {
                item(key = "head") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md)) {
                        ActionButton("New shelf", filled = true, modifier = Modifier.entryFocus()) {
                            TvDialogs.input(
                                "Shelf name",
                                hint = "Friday night",
                                message = "The rail appears in On Demand for the media kind you pick.",
                                onSubmit = { name ->
                                    if (name.isNotBlank()) {
                                        AppPreferences.addShelf(
                                            AppPreferences.Shelf(id = "", title = name.take(40), media = "movie", genreId = null),
                                        )
                                        ShelfRefresh.tick++
                                        tick++
                                    }
                                },
                            )
                        }
                    }
                    Caption("A shelf is one TMDB genre, or every genre, as a rail of its own.")
                }
                if (shelves.isEmpty()) {
                    item(key = "empty") {
                        Spacer(Modifier.height(ThemeSpec.Space.lg))
                        EmptyState("No shelves yet.", hint = "Create one to pin a custom row into On Demand.")
                    }
                }
                items(shelves, key = { it.id }) { shelf ->
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth()
                            .cardSurface(focused)
                            .glassRow(focused)
                            .clickableNoIndication {
                                privateMenu(
                                    shelf = shelf,
                                    onChanged = { tick++ },
                                ) { tick++ }
                            }
                            .padding(ThemeSpec.Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(shelf.title, fontSize = ThemeSpec.Type.body, fontWeight = FontWeight.SemiBold, color = ThemeSemantics.textEmphatic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${if (shelf.media == "movie") "Movies" else "Shows"} · ${DiscoveryFeeds.GENRES.firstOrNull { it.second == shelf.genreId }?.first ?: "Every genre"}",
                                fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1,
                            )
                        }
                        Text("Edit", fontSize = ThemeSpec.Type.label, color = ThemeSemantics.accent, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Bumped when a shelf is created, retargeted or removed, so an open On Demand page reloads its rows. */
internal object ShelfRefresh {
    var tick by mutableIntStateOf(0)
}

private val PagePadding = PaddingValues(
    start = ThemeSpec.Space.margin,
    end = ThemeSpec.Safe.horizontal,
    top = ThemeSpec.Space.md,
    bottom = ThemeSpec.Space.lg,
)

private fun privateMenu(
    shelf: AppPreferences.Shelf,
    onChanged: () -> Unit,
    onRemoved: () -> Unit,
) {
    TvDialogs.menu(
        shelf.title,
        listOf(
            if (shelf.media == "movie") "Show me shows instead" else "Show me movies instead",
            "Genre: ${DiscoveryFeeds.GENRES.firstOrNull { it.second == shelf.genreId }?.first ?: "Every genre"}",
            "Remove shelf",
        ),
        onPick = { i ->
            when (i) {
                0 -> {
                    AppPreferences.updateShelf(
                        AppPreferences.Shelf(id = shelf.id, title = shelf.title, media = if (shelf.media == "movie") "series" else "movie", genreId = shelf.genreId),
                    )
                    onChanged()
                    ShelfRefresh.tick++
                }
                1 -> TvDialogs.menu(
                    "Genre",
                    listOf("Every genre") + DiscoveryFeeds.GENRES.map { it.first },
                    onPick = { pick ->
                        val genreId = DiscoveryFeeds.GENRES.getOrNull(pick - 1)?.second
                        AppPreferences.updateShelf(
                            AppPreferences.Shelf(id = shelf.id, title = shelf.title, media = shelf.media, genreId = genreId),
                        )
                        onChanged()
                        ShelfRefresh.tick++
                    },
                )
                else -> TvDialogs.confirm("Remove shelf?", shelf.title, destructive = true) {
                    AppPreferences.removeShelf(shelf.id)
                    onRemoved()
                    ShelfRefresh.tick++
                }
            }
        },
    )
}