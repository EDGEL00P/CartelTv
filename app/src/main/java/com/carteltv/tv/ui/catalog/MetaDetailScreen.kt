package com.carteltv.tv.ui.catalog

import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.discovery.MetaDetails
import com.carteltv.tv.data.discovery.MetaEpisode
import com.carteltv.tv.data.discovery.MetaRepository
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.ui.components.CardArtwork
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.FilterChip
import com.carteltv.tv.ui.components.PageLoading
import com.carteltv.tv.ui.components.PosterTile
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.StagedScreen
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.hero.readArt
import com.carteltv.tv.ui.nav.PageChipRow
import com.carteltv.tv.ui.nav.PagePadding
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.settings.StreamPickerHost
import com.carteltv.tv.ui.theme.LocalAmbientTint
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import java.util.Locale

/** The key-art plate's height, matching the series page so both detail pages read alike. */
private val META_ART_H = 224.dp

/** One episode tile: the catalog rail width, so a season reads like every other slider on the app. */
private val EPISODE_TILE_W = 200.dp

@Composable
fun MetaDetailScreen(type: String, id: String, title: String, poster: String?) {
    val refId = remember(type, id) { MetaRepository.refOf(type, id) }
    val scope = rememberCoroutineScope()
    var details by remember(refId) { mutableStateOf<MetaDetails?>(null) }
    var failed by remember(refId) { mutableStateOf(false) }
    var fav by remember(refId) { mutableStateOf(AppPreferences.isFav(refId)) }
    var saved by remember(refId) { mutableStateOf(AppPreferences.inLibrary(refId)) }
    var watchedTick by remember(refId) { mutableIntStateOf(0) }
    var season by remember(refId) { mutableIntStateOf(1) }
    var episodes by remember(refId, season) { mutableStateOf<List<MetaEpisode>>(emptyList()) }
    var episodesSettled by remember(refId) { mutableStateOf(false) }
    var parts by remember(refId) { mutableStateOf<List<com.carteltv.tv.data.discovery.MetaSummary>>(emptyList()) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // Watched marks come from the Trakt cache; refresh them when the account comes back to the page.
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) watchedTick++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Season and episode the picker opens on; the page's own season by default.
    var picker by remember { mutableStateOf<Pair<Int, Int?>?>(null) }

    LaunchedEffect(refId) {
        val result = suspendRunCatching { MetaRepository.load(type, id, title, poster) }
        details = result.getOrNull()
        failed = result.isFailure
    }

    // The page's key art holds the stage while nothing is focused: the backdrop the plate shows, else its poster.
    val ambient = LocalAmbientTint.current
    val ctx = LocalContext.current
    LaunchedEffect(details) {
        val art = details?.let { it.backdrop?.takeIf { b -> b.isNotBlank() } ?: it.poster } ?: poster
        ambient?.pageTint = art?.takeIf { it.isNotBlank() }?.let { readArt(ctx, it)?.tint }
    }
    LaunchedEffect(details) {
        val c = details?.collection ?: return@LaunchedEffect
        parts = suspendRunCatching { MetaRepository.collectionParts(c.id) }.getOrDefault(emptyList())
    }
    LaunchedEffect(details, season) {
        val shown = details ?: return@LaunchedEffect
        if (!shown.isSeries) return@LaunchedEffect
        episodesSettled = false
        episodes = suspendRunCatching { MetaRepository.season(shown, season) }.getOrDefault(emptyList())
        episodesSettled = true
    }

    ScreenPage(title = details?.name ?: title) {
        StagedScreen(section = if (type == "series" || type == "tv") AppSection.TV else AppSection.MOVIES) {
            val shown = details
            when {
                shown == null && !failed -> PageLoading()
                shown == null -> EmptyState("This title could not be loaded.", hint = "Check the connection and try again.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PagePadding,
                ) {
                    item(key = "art") { KeyArtPlate(shown, title) }
                    item(key = "copy") {
                        CopyBlock(
                            details = shown,
                            fav = fav,
                            saved = saved,
                            onPlay = { picker = season to episodes.firstOrNull()?.episode },
                            onFav = { AppPreferences.toggleFav(refId); fav = AppPreferences.isFav(refId) },
                            onSave = {
                                saved = AppPreferences.toggleLibrary(
                                    AppPreferences.LibraryEntry(
                                        id = refId, type = shown.type, name = shown.name,
                                        poster = shown.poster.orEmpty(), backdrop = shown.backdrop.orEmpty(),
                                        year = shown.year.orEmpty(),
                                        rating = shown.rating?.let { "%.1f".format(Locale.US, it) }.orEmpty(),
                                    ),
                                )
                            },
                            onWatched = {
                                scope.launch {
                                    markWatched(shown, episodes)
                                    watchedTick++
                                }
                            },
                        )
                    }
                    if (shown.isSeries && shown.seasons.isNotEmpty()) {
                        item(key = "seasons") {
                            PageChipRow {
                                items(shown.seasons, key = { it.number }) { s ->
                                    FilterChip(
                                        label = s.name?.takeIf { it.isNotBlank() } ?: "Season ${s.number}",
                                        selected = s.number == season,
                                    ) { season = s.number }
                                }
                            }
                        }
                        item(key = "episodes") {
                            EpisodeRail(
                                episodes = episodes,
                                settled = episodesSettled,
                                poster = shown.poster,
                                showName = shown.name,
                                playableId = shown.playableId,
                                watchedTick = watchedTick,
                                onPlay = { e -> picker = e.season to e.episode },
                            )
                        }
                    }
                    if (shown.cast.isNotEmpty()) {
                        item(key = "cast-title") { SectionTitle("Cast", Modifier.padding(top = ThemeSpec.Space.lg)) }
                        item(key = "cast-rail") {
                            LazyRow(Modifier.restoreRowFocus(), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md)) {
                                items(shown.cast.take(24), key = { it.name }) { person ->
                                    Column(Modifier.width(110.dp)) {
                                        AsyncImage(
                                            model = person.photo,
                                            contentDescription = person.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(ThemeSpec.Card.corner)),
                                        )
                                        Text(
                                            person.name, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textEmphatic,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                        person.character?.takeIf { it.isNotBlank() }?.let {
                                            Text(
                                                it, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (parts.isNotEmpty()) {
                        item(key = "collection-title") { SectionTitle(shown.collection?.name ?: "Collection", Modifier.padding(top = ThemeSpec.Space.lg)) }
                        item(key = "collection-rail") {
                            LazyRow(Modifier.restoreRowFocus(), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter)) {
                                items(parts, key = { it.refId }) { part ->
                                    PosterTile(
                                        imageUrl = part.poster, title = part.name, meta = part.year, width = EPISODE_TILE_W,
                                        onClick = { Nav.push(Route.Meta(part.type, part.refId, part.name, part.poster)) },
                                    )
                                }
                            }
                        }
                    }
                    if (shown.recommendations.isNotEmpty()) {
                        item(key = "recommendations") {
                            Text(
                                "More like this",
                                fontSize = ThemeSpec.Type.cardTitle,
                                fontWeight = FontWeight.SemiBold,
                                color = ThemeSemantics.textEmphatic,
                                modifier = Modifier.padding(top = ThemeSpec.Space.lg, bottom = ThemeSpec.Space.sm),
                            )
                        }
                        item(key = "recommendation-rail") {
                            LazyRow(Modifier.restoreRowFocus(), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter)) {
                                items(shown.recommendations, key = { it.refId }) { r ->
                                    PosterTile(
                                        imageUrl = r.poster,
                                        title = r.name,
                                        width = EPISODE_TILE_W,
                                        onClick = { Nav.push(Route.Meta(r.type, if (r.refId.startsWith("tmdb:")) r.refId else r.refId.removePrefix("stremio:"), r.name, r.poster)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val target = details
    val at = picker
    if (target != null && at != null) {
        StreamPickerHost(
            met = target.toMetum(),
            onDismiss = { picker = null },
            initialSeason = at.first,
            initialEpisode = at.second,
        )
    }
}

/** The 16:9 key-art plate: backdrop (or the poster), the house veil, and the logo or name across its foot. */
@Composable
private fun KeyArtPlate(details: MetaDetails, fallbackTitle: String) {
    Box(
        Modifier
            .fillMaxWidth(0.62f)
            .height(META_ART_H)
            .clip(RoundedCornerShape(ThemeSpec.Card.corner))
            .background(ThemeSemantics.surfaceRest),
    ) {
        val art = details.backdrop ?: details.poster
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = details.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CardArtwork(url = null, title = fallbackTitle, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to ThemeSemantics.scrimDeep.copy(alpha = 0.9f),
                ),
            ),
        )
        val logo = details.logo
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.BottomStart).padding(ThemeSpec.Space.md).widthIn(max = 320.dp).height(90.dp),
            )
        } else {
            Text(
                details.name,
                fontSize = ThemeSpec.Type.display,
                fontWeight = FontWeight.Bold,
                color = ThemeSemantics.textEmphatic,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(ThemeSpec.Space.md),
            )
        }
    }
}

@Composable
private fun CopyBlock(
    details: MetaDetails,
    fav: Boolean,
    saved: Boolean,
    onPlay: () -> Unit,
    onFav: () -> Unit,
    onSave: () -> Unit,
    onWatched: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(ThemeSpec.Space.md))
        Text(
            details.name,
            fontSize = ThemeSpec.Type.titleL,
            fontWeight = FontWeight.Bold,
            color = ThemeSemantics.textEmphatic,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        details.originalName?.takeIf { it != details.name }?.let {
            Text(it, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(ThemeSpec.Space.xs))
        val facts = listOfNotNull(
            details.yearLabel,
            if (details.isSeries && details.seasons.isNotEmpty()) "${details.seasons.size} seasons" else details.runtimeLabel,
            details.rating?.let { "★ ${"%.1f".format(Locale.US, it)}" },
            details.certification,
        ).joinToString("  ·  ")
        if (facts.isNotBlank()) {
            Text(facts, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        details.genres.take(3).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString("  ·  "), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        details.overview?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                fontSize = ThemeSpec.Type.meta,
                lineHeight = 21.sp,
                color = ThemeSemantics.textPrimary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(ThemeSpec.Space.sm))
        }
        listOfNotNull(
            details.directors.take(3).takeIf { it.isNotEmpty() }?.let { "Directed by ${it.joinToString(", ")}" },
            details.cast.take(4).takeIf { it.isNotEmpty() }?.let { "Cast · ${it.joinToString(", ") { c -> c.name }}" },
            details.companies.take(2).takeIf { it.isNotEmpty() }?.let { "Studio · ${it.joinToString(", ") { c -> c.name }}" },
        ).forEach {
            Text(it, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(ThemeSpec.Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
            ActionButton("Play", filled = true, modifier = Modifier.entryFocus()) { onPlay() }
            details.trailerKey?.takeIf { it.isNotBlank() }?.let { key ->
                ActionButton("Trailer", filled = false) {
                    com.carteltv.tv.ui.youtube.YouTubeDeepLink.post(
                        com.carteltv.tv.ui.youtube.YouTubeDeepLink.Request(key, details.name),
                    )
                    if (Nav.current != Route.YouTube) Nav.push(Route.YouTube)
                }
            }
            ActionButton(if (fav) "Remove from favourites" else "Add to favourites", filled = false) { onFav() }
            ActionButton(if (saved) "In library" else "Add to library", filled = false) { onSave() }
            ActionButton(
                if (!details.isSeries && com.carteltv.tv.data.trakt.TraktAuth.isMovieWatched(details.name)) "✓ Watched"
                else if (details.isSeries) "Mark next watched" else "Mark watched",
                filled = false,
            ) { onWatched() }
        }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        if (details.playableId.isNullOrBlank()) {
            Text(
                "No IMDb id for this title yet — add-on streams need one.",
                fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 2,
            )
        }
    }
}

@Composable
private fun EpisodeRail(
    episodes: List<MetaEpisode>,
    settled: Boolean,
    poster: String?,
    showName: String,
    playableId: String?,
    watchedTick: Int,
    onPlay: (MetaEpisode) -> Unit,
) {
    when {
        !settled -> Box(Modifier.fillMaxWidth().height(EPISODE_TILE_W * 1.5f + ThemeSpec.Space.margin)) { PageLoading() }
        episodes.isEmpty() -> EmptyState("No episodes for this season yet.")
        else -> LazyRow(Modifier.restoreRowFocus(), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter)) {
            items(episodes, key = { it.id }) { e ->
                val ref = playableId?.let { "series:$it/${e.season}:${e.episode}" }
                val watched = remember(watchedTick, e.id) {
                    com.carteltv.tv.data.trakt.TraktAuth.isEpisodeWatched(showName, e.season, e.episode)
                }
                val progress = remember(watchedTick, ref) {
                    ref?.let { r ->
                        AppPreferences.recentEntries().firstOrNull { it.ref == r }?.let { entry ->
                            if (entry.durationMs > 0) (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f) else 0f
                        }
                    } ?: 0f
                }
                Box {
                    PosterTile(
                        imageUrl = e.still ?: poster,
                        title = e.name?.takeIf { it.isNotBlank() } ?: "Episode ${e.episode}",
                        meta = e.runtimeMinutes?.let { "$it min" },
                        badge = if (watched) "WATCHED" else "S${e.season}E${e.episode}",
                        width = EPISODE_TILE_W,
                        progress = progress,
                        onClick = { onPlay(e) },
                    )
                }
            }
        }
    }
}

/** Marks the title, or the next unwatched episode of a show, watched on Trakt. */
private suspend fun markWatched(details: MetaDetails, episodes: List<MetaEpisode>) {
    if (!details.isSeries) {
        com.carteltv.tv.data.trakt.TraktAuth.scrobbleMovie(details.name, details.year?.toIntOrNull())
        return
    }
    val next = episodes.firstOrNull { !com.carteltv.tv.data.trakt.TraktAuth.isEpisodeWatched(details.name, it.season, it.episode) }
        ?: episodes.lastOrNull() ?: return
    com.carteltv.tv.data.trakt.TraktAuth.scrobbleEpisode(details.name, next.season, next.episode)
}
