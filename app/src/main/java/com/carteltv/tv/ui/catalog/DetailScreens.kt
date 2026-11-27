package com.carteltv.tv.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.ui.components.AutoSizeText
import com.carteltv.tv.ui.components.CardArtwork
import com.carteltv.tv.ui.components.Chip
import com.carteltv.tv.ui.components.GridPoster
import com.carteltv.tv.ui.components.RailFocusLine
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.StagedScreen
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.components.railEdgeFade
import com.carteltv.tv.ui.components.railWrapItem
import com.carteltv.tv.ui.components.railWrapToStart
import com.carteltv.tv.ui.components.rememberRailWrapState
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.hero.readArt
import com.carteltv.tv.ui.nav.PageChipRow
import com.carteltv.tv.ui.nav.PagePadding
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.theme.BrassSheen
import com.carteltv.tv.ui.theme.BrassSolid
import com.carteltv.tv.ui.theme.LocalAmbientTint
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec

@Composable
fun RatingBadge(cert: com.carteltv.tv.data.discovery.Classifications.Rating) {
    Row(
        Modifier
            .clip(RoundedCornerShape(ThemeSpec.Card.pill))
            .border(1.dp, ThemeSemantics.accent.copy(alpha = 0.5f), RoundedCornerShape(ThemeSpec.Card.pill))
            .padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
    ) {
        Text(cert.code, fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic, maxLines = 1)
        Text(
            cert.board,
            fontSize = ThemeSpec.Type.label,
            color = ThemeSemantics.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The standard chip row pads itself to the page gutter. Inside the detail column that gutter is already paid by the
 * page, so the row borrows the same width back on the left and runs that much further past the column's right edge.
 */
private fun Modifier.cancelChipGutter(): Modifier =
    layout { measurable, constraints ->
        val gutter = ThemeSpec.Space.margin.roundToPx()
        val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + gutter))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter, 0) }
    }

/**
 * The movie detail page, in the shared detail read: the hero plate, the copy block beneath it, then the actions —
 * the same LazyColumn shape the series and add-on pages use, so all three detail pages line up.
 */
@Composable
fun MovieDetailScreen(
    streamId: Long,
    title: String,
    ext: String?,
    icon: String?,
    rating: String?,
) {
    val client = ProviderSession.current
    var info by remember { mutableStateOf<XtreamClient.VodInfo?>(null) }
    // Null while the description loads, then whether its request failed: a failure must not read as still loading.
    var infoFailed by remember { mutableStateOf<Boolean?>(null) }
    val favKey = "movie:$streamId"
    var fav by remember(favKey) { mutableStateOf(AppPreferences.isFav(favKey)) }
    var cert by remember(title) {
        mutableStateOf<com.carteltv.tv.data.discovery.Classifications.Rating?>(null)
    }
    LaunchedEffect(title) {
        cert =
            com.carteltv.tv.data.discovery.Classifications
                .forTitle("movie", title, info?.releasedate?.take(4))
    }

    LaunchedEffect(streamId) {
        val result = suspendRunCatching { client?.vodInfo(streamId) }
        info = result.getOrNull()
        infoFailed = result.isFailure
    }

    // The plate's key art holds the stage while nothing is focused, so the page reads as one canvas with its art.
    val ambient = LocalAmbientTint.current
    val ctx = LocalContext.current
    LaunchedEffect(icon) {
        ambient?.pageTint = icon?.takeIf { it.isNotBlank() }?.let { readArt(ctx, it)?.tint }
    }

    ScreenPage(title = title, header = false) {
        StagedScreen(section = AppSection.MOVIES) {
            RailFocusLine {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PagePadding,
                    verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                ) {
                    item(key = "art") { DetailHeroPlate(art = icon, title = title) }
                    item(key = "copy") {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                title,
                                fontSize = ThemeSpec.Type.display,
                                fontWeight = FontWeight.Bold,
                                color = ThemeSemantics.textEmphatic,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(ThemeSpec.Space.sm))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                rating?.let {
                                    Text(
                                        it,
                                        fontSize = ThemeSpec.Type.body,
                                        color = ThemeSemantics.accentBright,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.width(ThemeSpec.Space.md))
                                }
                                Text(
                                    androidx.compose.ui.res
                                        .stringResource(com.carteltv.tv.R.string.detail_movie_kind),
                                    fontSize = ThemeSpec.Type.label,
                                    fontFamily = ThemeSpec.Font.broadcast,
                                    color = ThemeSemantics.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                cert?.let {
                                    Spacer(Modifier.width(ThemeSpec.Space.md))
                                    RatingBadge(it)
                                }
                            }
                            Spacer(Modifier.height(ThemeSpec.Space.md))
                            if (com.carteltv.tv.data.trakt.TraktAuth
                                    .isMovieWatched(title)
                            ) {
                                Text(
                                    "WATCHED",
                                    fontSize = ThemeSpec.Type.label,
                                    color = ThemeSemantics.success,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = ThemeSpec.Space.cozy),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                // A description that failed to load says so; only a request still in flight reads as loading.
                                info?.plot ?: when (infoFailed) {
                                    null -> "Loading description…"
                                    true -> "Couldn't load the description."
                                    else -> ""
                                },
                                fontSize = ThemeSpec.Type.meta,
                                color = ThemeSemantics.textPrimary,
                                lineHeight = 21.sp,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(ThemeSpec.Space.cozy))
                            listOfNotNull(
                                info?.genre?.let { "Genre · $it" },
                                info?.cast?.let { "Cast · ${it.take(120)}" },
                                info?.director?.let { "Director · $it" },
                                info?.duration?.let { "Duration · $it" },
                            ).forEach {
                                Text(
                                    it,
                                    fontSize = ThemeSpec.Type.meta,
                                    color = ThemeSemantics.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(bottom = ThemeSpec.Space.xs),
                                )
                            }
                            Spacer(Modifier.height(ThemeSpec.Space.lg))
                            Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                                ActionButton("Play", filled = true, modifier = Modifier.entryFocus()) {
                                    val c = ProviderSession.current ?: return@ActionButton
                                    AppPreferences.addRecent("$favKey|$title|${c.movieUrl(streamId, ext)}")
                                    Nav.push(Route.Player(c.movieUrl(streamId, ext), title, favKey))
                                }
                                ActionButton(if (fav) "Remove from favourites" else "Add to favourites", filled = false) {
                                    AppPreferences.toggleFav(favKey)
                                    fav = AppPreferences.isFav(favKey)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The series detail page, in the same shared detail read as the movie and add-on pages: hero plate, copy, the
 * season chips and the episode rail — one LazyColumn so every detail page scrolls and spaces itself alike.
 */
@Composable
fun SeriesDetailScreen(
    seriesId: Long,
    name: String,
    cover: String?,
    plot: String?,
) {
    val client = ProviderSession.current
    var seasons by remember { mutableStateOf<Map<Int, List<XtreamClient.Episode>>>(emptyMap()) }
    // Null while the episode list loads, then whether its request failed: a failure must not read as a series with no seasons.
    var seasonsFailed by remember { mutableStateOf<Boolean?>(null) }
    var selSeason by remember { mutableStateOf(1) }
    var tvCert by remember(name) {
        mutableStateOf<com.carteltv.tv.data.discovery.Classifications.Rating?>(null)
    }
    LaunchedEffect(name) {
        tvCert =
            com.carteltv.tv.data.discovery.Classifications
                .forTitle("tv", name)
    }

    LaunchedEffect(seriesId) {
        val result = suspendRunCatching { client?.seriesEpisodes(seriesId) }
        seasons = result.getOrNull() ?: emptyMap()
        seasonsFailed = result.isFailure
        selSeason = seasons.keys.minOrNull() ?: 1
    }

    // The cover stands in for the page's key art, and it holds the stage while nothing is focused.
    val ambient = LocalAmbientTint.current
    val ctx = LocalContext.current
    LaunchedEffect(cover) {
        ambient?.pageTint = cover?.takeIf { it.isNotBlank() }?.let { readArt(ctx, it)?.tint }
    }

    ScreenPage(title = name, header = false) {
        StagedScreen(section = AppSection.TV) {
            RailFocusLine {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PagePadding,
                    verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                ) {
                    item(key = "art") { DetailHeroPlate(art = cover, title = name) }
                    item(key = "copy") {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                name,
                                fontSize = ThemeSpec.Type.display,
                                fontWeight = FontWeight.Bold,
                                color = ThemeSemantics.textEmphatic,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(ThemeSpec.Space.sm))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
                            ) {
                                // A list that failed to load says so; only a request still in flight reads as loading.
                                Text(
                                    when (seasonsFailed) {
                                        null -> {
                                            androidx.compose.ui.res
                                                .stringResource(com.carteltv.tv.R.string.addon_loading_episodes)
                                        }

                                        true -> {
                                            "Couldn't load the episodes."
                                        }

                                        else -> {
                                            "TV series, ${seasons.size} ${if (seasons.size == 1) "season" else "seasons"}"
                                        }
                                    },
                                    fontSize = ThemeSpec.Type.label,
                                    color = ThemeSemantics.textSecondary,
                                    maxLines = 1,
                                )
                                tvCert?.let { RatingBadge(it) }
                            }
                            Spacer(Modifier.height(ThemeSpec.Space.cozy))
                            Text(
                                plot ?: "",
                                fontSize = ThemeSpec.Type.meta,
                                color = ThemeSemantics.textPrimary,
                                lineHeight = 21.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (seasons.isNotEmpty()) {
                        item(key = "seasons") {
                            PageChipRow(Modifier.cancelChipGutter()) {
                                seasons.keys.sorted().forEach { snum ->
                                    item(key = "season_$snum") { Chip("S$snum", selSeason == snum) { selSeason = snum } }
                                }
                            }
                        }
                        item(key = "episodes") {
                            val episodes = seasons[selSeason].orEmpty()
                            val rowState = rememberLazyListState()
                            val wrapState = rememberRailWrapState()
                            val wrapFirst = remember { FocusRequester() }
                            Column(Modifier.fillMaxWidth()) {
                                SectionTitle("Episodes", Modifier.padding(top = ThemeSpec.Space.sm, bottom = ThemeSpec.Space.sm))
                                LazyRow(
                                    state = rowState,
                                    modifier =
                                        Modifier
                                            .restoreRowFocus()
                                            .railEdgeFade(rowState)
                                            .railWrapToStart(wrapState, rowState, episodes.size, wrapFirst),
                                    // Headroom for the Netflix-scale focus lift: a looming tile is never clipped by its rail.
                                    contentPadding = PaddingValues(vertical = RailContentHeadroom),
                                    horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                                ) {
                                    items(episodes.size, key = { episodes[it].id }) { idx ->
                                        val ep = episodes[idx]
                                        val epKey = "series:$seriesId/${ep.id}"
                                        Box(
                                            Modifier
                                                .width(EpisodeTileWidth)
                                                .railWrapItem(wrapState, idx)
                                                .then(
                                                    if (idx == 0) {
                                                        Modifier.entryFocus(key = selSeason, requester = wrapFirst)
                                                    } else {
                                                        Modifier
                                                    },
                                                ),
                                        ) {
                                            GridPoster(
                                                // The episode's own still when the panel sends one; the series cover stands in
                                                // when it doesn't, and CardArtwork's monogram plate takes over when even that is missing.
                                                imageUrl = ep.art ?: cover,
                                                title = ep.title,
                                                subtitle = "E${ep.episodeNum}",
                                                badge =
                                                    if (com.carteltv.tv.data.trakt.TraktAuth.isEpisodeWatched(
                                                            name,
                                                            selSeason,
                                                            ep.episodeNum,
                                                        )
                                                    ) {
                                                        "WATCHED"
                                                    } else {
                                                        null
                                                    },
                                                onClick = {
                                                    val c = ProviderSession.current
                                                    if (c != null) {
                                                        val url = c.episodeUrl(ep.id, ep.ext)
                                                        AppPreferences.addRecent(
                                                            "series:$seriesId|${"$name S${selSeason}E${ep.episodeNum}: ${ep.title}"}|$url",
                                                        )
                                                        Nav.push(Route.Player(url, "$name S$selSeason E${ep.episodeNum}", epKey))
                                                    }
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

@Composable
fun ActionButton(
    label: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    AutoSizeText(
        label,
        fontSize = ThemeSpec.Type.meta,
        fontFamily = ThemeSpec.Font.broadcast,
        fontWeight = FontWeight.SemiBold,
        color =
            if (filled) {
                ThemeSemantics.textInverse
            } else if (focused) {
                ThemeSemantics.textEmphatic
            } else {
                ThemeSemantics.textPrimary
            },
        minScale = 0.75f,
        modifier =
            modifier
                .widthIn(max = 320.dp)
                .cardSurface(focused)
                .background(
                    when {
                        filled && focused -> {
                            BrassSheen
                        }

                        filled -> {
                            BrassSolid
                        }

                        else -> {
                            androidx.compose.ui.graphics
                                .SolidColor(Color.Transparent)
                        }
                    },
                ).onFocusChanged { focused = it.isFocused }
                .clickableNoIndication { onClick() }
                .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.cozy),
    )
}
