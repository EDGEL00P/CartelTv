package com.carteltv.tv.ui.catalog
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.ui.live.GuideContent
import com.carteltv.tv.core.suspendRunCatching
import androidx.compose.foundation.layout.widthIn

import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.ThemeSemantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.nav.stateKey
import com.carteltv.tv.ui.theme.*
import com.carteltv.tv.ui.components.Chip
import com.carteltv.tv.ui.components.CardArtwork
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.AutoSizeText
import com.carteltv.tv.ui.components.GridPoster
import com.carteltv.tv.ui.components.RailFocusLine
import com.carteltv.tv.ui.components.railEdgeFade
import com.carteltv.tv.ui.components.railWrapItem
import com.carteltv.tv.ui.components.railWrapToStart
import com.carteltv.tv.ui.components.rememberRailWrapState
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.StagedScreen
import com.carteltv.tv.ui.hero.readArt
import com.carteltv.tv.ui.nav.ScreenScaffold
import com.carteltv.tv.nav.HomeReset
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.nav.PageChipRow
import com.carteltv.tv.ui.apps.AppsScreen
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.recents.RecentsOverlay
import com.carteltv.tv.ui.search.SearchScreen
import com.carteltv.tv.ui.settings.LoginScreen
import com.carteltv.tv.ui.settings.SettingsScreen
import com.carteltv.tv.ui.sports.ScoresScreen
import com.carteltv.tv.ui.youtube.YouTubeScreen
import com.carteltv.tv.ui.nav.cardSurface

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
        Text(cert.board, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The standard chip row pads itself to the page gutter. Inside the detail column that gutter is already paid by the
 * page, so the row borrows the same width back on the left and runs that much further past the column's right edge.
 */
private fun Modifier.cancelChipGutter(): Modifier = layout { measurable, constraints ->
    val gutter = ThemeSpec.Space.margin.roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + gutter))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter, 0) }
}

/**
 * The scrim every detail header lays over its key art, in the hero's read: clear at the top so the art
 * carries, deepening toward the foot where the copy sits, so every line reads on a bright frame.
 */
private fun Modifier.detailVeil(): Modifier = background(
    Brush.verticalGradient(
        0f to Color.Transparent,
        0.5f to ThemeSemantics.scrimDeep.copy(alpha = 0.35f),
        1f to ThemeSemantics.scrimDeep.copy(alpha = 0.88f),
    ),
)

/** The series hero plate's height: the budget the page proves — the season chips and the episode rail still
 *  fit beneath it on the 540dp canvas. Strict 16:9 turns that height into the plate's 398dp leading width. */
private val SERIES_ART_H = 224.dp

/** One episode tile: the catalog rail width, so a season reads like every other slider on the app. */
private val EPISODE_TILE_W = 200.dp

@Composable
fun MovieDetailScreen(streamId: Long, title: String, ext: String?, icon: String?, rating: String?) {
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
        cert = com.carteltv.tv.data.discovery.Classifications.forTitle("movie", title, info?.releasedate?.take(4))
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
        StagedScreen(section = com.carteltv.tv.nav.AppSection.MOVIES) {
            // The frame dissolves its top edge under the masthead, so the page starts below that fade.
            Box(Modifier.fillMaxSize().padding(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal, top = ThemeSpec.Space.xl)) {
                // The hero plate: a strict 16:9 box rather than the full-bleed band the conversion left.
                // The content area's height binds (the masthead and the bar claim the rest of the 540dp
                // canvas), so the ratio caps the plate's width into the leading keyart plate of a
                // Netflix-style movie page.
                Box(Modifier.aspectRatio(16f / 9f)) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(ThemeSpec.Card.corner)).background(ThemeSemantics.surfaceRest),
                    ) {
                        if (icon != null) AsyncImage(model = icon, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else CardArtwork(url = null, title = title, modifier = Modifier.fillMaxSize())
                    }
                    // The copy rides the house veil: clear for the art up top, deep toward the foot where every line sits.
                    Box(Modifier.matchParentSize().detailVeil())
                    // The copy stays ON the plate's foot instead of beside it: title, six plot lines, the
                    // credit block and both buttons run taller than the room any beside-plate could leave for
                    // them on this canvas, so the plate's foot is the one arrangement that keeps every line
                    // and both actions inside the frame. The 860dp reading measure the old beside-poster
                    // column kept remains the column's cap.
                    Column(
                        Modifier.align(Alignment.BottomStart).widthIn(max = 860.dp).fillMaxWidth().padding(bottom = ThemeSpec.Safe.vertical),
                    ) {
                        Text(title, fontSize = ThemeSpec.Type.display, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(ThemeSpec.Space.sm))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            rating?.let {
                                Text(it, fontSize = ThemeSpec.Type.body, color = ThemeSemantics.accentBright, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Spacer(Modifier.width(ThemeSpec.Space.md))
                            }
                            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.detail_movie_kind), fontSize = ThemeSpec.Type.label, fontFamily = ThemeSpec.Font.broadcast, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            cert?.let { Spacer(Modifier.width(ThemeSpec.Space.md)); RatingBadge(it) }
                        }
                        Spacer(Modifier.height(ThemeSpec.Space.md))
                        if (com.carteltv.tv.data.trakt.TraktAuth.isMovieWatched(title)) {
                            Text(
                                "WATCHED",
                                fontSize = ThemeSpec.Type.label, color = ThemeSemantics.success, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = ThemeSpec.Space.cozy), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            // A description that failed to load says so; only a request still in flight reads as loading.
                            info?.plot ?: when (infoFailed) {
                                null -> "Loading description…"
                                true -> "Couldn't load the description."
                                else -> ""
                            },
                            fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textPrimary, lineHeight = 21.sp,
                            maxLines = 6, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(ThemeSpec.Space.cozy))
                        listOfNotNull(
                            info?.genre?.let { "Genre · $it" },
                            info?.cast?.let { "Cast · ${it.take(120)}" },
                            info?.director?.let { "Director · $it" },
                            info?.duration?.let { "Duration · $it" },
                        ).forEach {
                            Text(it, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = ThemeSpec.Space.xs))
                        }
                        Spacer(Modifier.height(ThemeSpec.Space.lg))
                        Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                            ActionButton("Play", filled = true, modifier = Modifier.entryFocus()) {
                                val c = ProviderSession.current ?: return@ActionButton
                                AppPreferences.addRecent("$favKey|$title|${c.movieUrl(streamId, ext)}")
                                Nav.push(Route.Player(c.movieUrl(streamId, ext), title, favKey))
                            }
                            ActionButton(if (fav) "Remove from favourites" else "Add to favourites", filled = false) {
                                AppPreferences.toggleFav(favKey); fav = AppPreferences.isFav(favKey)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(seriesId: Long, name: String, cover: String?, plot: String?) {
    val client = ProviderSession.current
    var seasons by remember { mutableStateOf<Map<Int, List<XtreamClient.Episode>>>(emptyMap()) }
    // Null while the episode list loads, then whether its request failed: a failure must not read as a series with no seasons.
    var seasonsFailed by remember { mutableStateOf<Boolean?>(null) }
    var selSeason by remember { mutableStateOf(1) }
    var tvCert by remember(name) {
        mutableStateOf<com.carteltv.tv.data.discovery.Classifications.Rating?>(null)
    }
    LaunchedEffect(name) {
        tvCert = com.carteltv.tv.data.discovery.Classifications.forTitle("tv", name)
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
        StagedScreen(section = com.carteltv.tv.nav.AppSection.TV) {
            // The page's rows — the season chips and the episode rail — park their focused card on the one line.
            RailFocusLine {
                // The frame dissolves its top edge under the masthead, so the page starts below that fade.
                Column(Modifier.fillMaxSize().padding(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal, top = ThemeSpec.Space.xl)) {
                    // The series hero is the plate-and-info read of a Prime-style detail page: the payload
                    // carries no backdrop of its own, so the cover stands in on a strict 16:9 plate, cropped
                    // to the landscape read. The episode rail's budget pins that plate 224dp tall (398dp
                    // wide at 16:9) at the leading edge — too narrow to carry the copy's reading measure
                    // inside it, so the info block sits beside the plate at full measure instead of
                    // clipping into it: every line stays visible on the 540dp canvas, and the chips and
                    // the episode rail keep their proven space beneath.
                    Row(verticalAlignment = Alignment.Bottom) {
                        Box(
                            Modifier.height(SERIES_ART_H).aspectRatio(16f / 9f).clip(RoundedCornerShape(ThemeSpec.Card.corner)).background(ThemeSemantics.surfaceRest),
                        ) {
                            if (cover != null) AsyncImage(model = cover, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else CardArtwork(url = null, title = name, modifier = Modifier.fillMaxSize())
                            // The house veil stays on the plate — clear for the art up top, deep toward the
                            // foot — so the still reads like the movie hero even with its copy beside it.
                            Box(Modifier.matchParentSize().detailVeil())
                        }
                        Spacer(Modifier.width(ThemeSpec.Space.xl))
                        // The reading measure the old overlaid column kept, now beside the plate.
                        Column(
                            Modifier.weight(1f).padding(bottom = ThemeSpec.Space.md),
                        ) {
                            Text(name, fontSize = ThemeSpec.Type.display, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(ThemeSpec.Space.sm))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                                // A list that failed to load says so; only a request still in flight reads as loading.
                                Text(
                                    when (seasonsFailed) {
                                        null -> androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.addon_loading_episodes)
                                        true -> "Couldn't load the episodes."
                                        else -> "TV series, ${seasons.size} ${if (seasons.size == 1) "season" else "seasons"}"
                                    },
                                    fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1,
                                )
                                tvCert?.let { RatingBadge(it) }
                            }
                            Spacer(Modifier.height(ThemeSpec.Space.cozy))
                            Text(plot ?: "", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textPrimary, lineHeight = 21.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    PageChipRow(Modifier.cancelChipGutter()) {
                        seasons.keys.sorted().forEach { snum ->
                            item(key = "season_$snum") { Chip("S$snum", selSeason == snum) { selSeason = snum } }
                        }
                    }
                    SectionTitle("Episodes", Modifier.padding(top = ThemeSpec.Space.sm, bottom = ThemeSpec.Space.sm))
                    val episodes = seasons[selSeason].orEmpty()
                    val rowState = rememberLazyListState()
                    val wrapState = rememberRailWrapState()
                    val wrapFirst = remember { FocusRequester() }
                    LazyRow(
                        state = rowState,
                        modifier = Modifier
                            .restoreRowFocus()
                            .railEdgeFade(rowState)
                            .railWrapToStart(wrapState, rowState, episodes.size, wrapFirst),
                        // Vertical room so a lifted tile is not clipped by the row.
                        // Headroom for the Netflix-scale focus lift: a looming tile is never clipped by its rail.
                        contentPadding = PaddingValues(vertical = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                    ) {
                        items(episodes.size, key = { episodes[it].id }) { idx ->
                            val ep = episodes[idx]
                            val epKey = "series:$seriesId/${ep.id}"
                            Box(
                                Modifier.width(EPISODE_TILE_W)
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
                                    // The episode's own still when the panel sends one (get_series_info's
                                    // info.movie_image, mirrored at top level by some panels); the series cover
                                    // stands in when it doesn't — Stalker and M3U sources always land here —
                                    // and CardArtwork's monogram plate takes over when even that is missing.
                                    imageUrl = ep.art ?: cover,
                                    title = ep.title,
                                    subtitle = "E${ep.episodeNum}",
                                    badge = if (com.carteltv.tv.data.trakt.TraktAuth.isEpisodeWatched(name, selSeason, ep.episodeNum)) "WATCHED" else null,
                                    onClick = {
                                        val c = ProviderSession.current
                                        if (c != null) {
                                            val url = c.episodeUrl(ep.id, ep.ext)
                                            AppPreferences.addRecent("series:${seriesId}|${"$name S${selSeason}E${ep.episodeNum}: ${ep.title}"}|$url")
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

@Composable
fun ActionButton(label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    AutoSizeText(
        label, fontSize = ThemeSpec.Type.meta, fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.SemiBold,
        color = if (filled) ThemeSemantics.textInverse else if (focused) ThemeSemantics.textEmphatic else ThemeSemantics.textPrimary,
        minScale = 0.75f,
        modifier = modifier
            .widthIn(max = 320.dp)
            .cardSurface(focused)
            .background(
                when {
                    filled && focused -> BrassSheen
                    filled -> BrassSolid
                    else -> androidx.compose.ui.graphics.SolidColor(Color.Transparent)
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .clickableNoIndication { onClick() }
            .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.cozy),
    )
}
