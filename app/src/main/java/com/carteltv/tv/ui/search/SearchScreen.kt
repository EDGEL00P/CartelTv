package com.carteltv.tv.ui.search
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.ui.theme.ThemeSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carteltv.tv.data.apps.LauncherApp
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.GuideModel
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.carteltv.tv.ui.settings.PinGate
import com.carteltv.tv.ui.components.Chip
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.GridPoster
import com.carteltv.tv.ui.components.PageError
import com.carteltv.tv.ui.components.PageLoading
import com.carteltv.tv.ui.components.PosterTile
import com.carteltv.tv.ui.components.RailFocusLine
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.TvTextField
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.components.FocusMemoryStore
import com.carteltv.tv.ui.components.keyFocusTarget
import com.carteltv.tv.ui.components.rememberKeyFocusRegistry
import com.carteltv.tv.ui.components.restoreKeyFocus
import com.carteltv.tv.ui.components.railEdgeFade
import com.carteltv.tv.ui.components.railWrapItem
import com.carteltv.tv.ui.components.railWrapToStart
import com.carteltv.tv.ui.components.rememberRailWrapState
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.nav.PageChipRow
import com.carteltv.tv.ui.nav.PagePadding

private enum class SearchFilter(val label: String) {
    ALL("All"),
    LIVE("Live TV"),
    MOVIES("Movies"),
    SERIES("Series"),
    APPS("Apps"),
}

/** Results of filtering the already-loaded local catalog off the main thread. */
private data class LocalSearchResults(
    val channels: List<XtreamClient.LiveStream>,
    val movies: List<XtreamClient.VodStream>,
    val series: List<XtreamClient.Series>,
    val apps: List<LauncherApp>,
)

@Composable
fun SearchScreen(apps: List<LauncherApp>) {
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val provider by ProviderSession.currentFlow.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var filter by remember { mutableStateOf(SearchFilter.ALL) }
    var channels by remember { mutableStateOf(listOf<XtreamClient.LiveStream>()) }
    var movies by remember { mutableStateOf(listOf<XtreamClient.VodStream>()) }
    var series by remember { mutableStateOf(listOf<XtreamClient.Series>()) }
    var epgHits by remember { mutableStateOf(listOf<com.carteltv.tv.data.xtream.EpgIndexDatabase.ProgramHit>()) }
    var catalogLoaded by remember { mutableStateOf(false) }
    // A catalog request failed, so the TV side of a search is missing because it never loaded. That is said plainly;
    // it must never surface as a query with no results.
    var catalogFailed by remember { mutableStateOf(false) }
    var onlineResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var onlineBusy by remember { mutableStateOf(false) }
    var lastOnlineQuery by remember { mutableStateOf(0L) }
    var lockedApp by remember { mutableStateOf<LauncherApp?>(null) }
    var catalogRetryTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(com.carteltv.tv.voice.SearchPrefill.pending) {
        com.carteltv.tv.voice.SearchPrefill.pending?.let { query = TextFieldValue(it, TextRange(it.length)); com.carteltv.tv.voice.SearchPrefill.pending = null }
    }

    LaunchedEffect(provider, catalogRetryTick) {
        channels = emptyList()
        movies = emptyList()
        series = emptyList()
        catalogLoaded = false
        catalogFailed = false
        val c = provider
        if (c == null) {
            catalogLoaded = true
            return@LaunchedEffect
        }
        val (liveResult, movieResult, seriesResult) = withContext(Dispatchers.IO) {
            coroutineScope {
                val live = async { suspendRunCatching { com.carteltv.tv.data.xtream.LiveChannels.all(c) } }
                val movies = async { suspendRunCatching { c.vodStreams(null) } }
                val series = async { suspendRunCatching { c.series(null) } }
                Triple(live.await(), movies.await(), series.await())
            }
        }
        if (ProviderSession.current !== c) return@LaunchedEffect
        channels = liveResult.getOrDefault(emptyList())
        movies = movieResult.getOrDefault(emptyList())
        series = seriesResult.getOrDefault(emptyList())
        catalogFailed = listOf(liveResult, movieResult, seriesResult).any { it.isFailure }
        catalogLoaded = true
    }

    val q = query.text.trim().lowercase()
    LaunchedEffect(q, provider) {
        epgHits = if (q.length < 2) {
            emptyList()
        } else {
            kotlinx.coroutines.withContext(com.carteltv.tv.engine.stability.BackgroundWorkScheduler.reads) {
                runCatching { com.carteltv.tv.data.xtream.EpgIndexDatabase.get(appCtx).searchPrograms(q, 25) }
                    .getOrDefault(emptyList())
            }
        }
    }
    // Online lookups: <2 chars stays local-only; otherwise debounce ~300ms, then fetch
    // TMDB/YouTube/Stremio rows. A missing key degrades a source to empty, so search never
    // breaks when only some sources are configured. LaunchedEffect cancels the previous query
    // on each keystroke, which is the debounce.
    LaunchedEffect(q) {
        if (q.length < 2) {
            onlineResults = emptyList()
            onlineBusy = false
            return@LaunchedEffect
        }
        onlineBusy = true
        kotlinx.coroutines.delay(300)
        onlineResults = suspendRunCatching { DefaultOnlineSearchSource.search(q) }.getOrDefault(emptyList())
        onlineBusy = false
        lastOnlineQuery = System.currentTimeMillis()
        AppPreferences.lastQuery = lastOnlineQuery
    }
    // Local catalog filtering runs off the main thread and is debounced so a large provider's
    // channels/movies list can't make each keystroke block input.
    val localResults by produceState(
        initialValue = LocalSearchResults(channels.take(30), emptyList(), emptyList(), emptyList()),
        q, channels, movies, series, apps,
    ) {
        if (q.isBlank()) {
            value = LocalSearchResults(channels.take(30), emptyList(), emptyList(), emptyList())
            return@produceState
        }
        delay(100)
        value = withContext(Dispatchers.Default) {
            LocalSearchResults(
                channels = channels.filter { it.name.lowercase().contains(q) }.take(40),
                movies = movies.filter { it.name.lowercase().contains(q) }.take(40),
                series = series.filter { it.name.lowercase().contains(q) }.take(40),
                apps = apps.filter { it.label.lowercase().contains(q) }.take(24),
            )
        }
    }
    val fc = localResults.channels
    val fm = localResults.movies
    val fs = localResults.series
    val fa = localResults.apps

    Box(Modifier.fillMaxSize()) {
        ScreenPage {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(ThemeSpec.Space.md))

                val keyboard = LocalSoftwareKeyboardController.current
                val focus = LocalFocusManager.current
                TvTextField(
                    phoneLabel = null,
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.padding(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal)
                        .entryFocus(enabled = FocusMemoryStore.peek("search") == null),
                    hint = "Search movies, shows and apps",
                    // Passing through the field (bar to results) must not raise the keyboard; OK does.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, showKeyboardOnFocus = false),
                    // Results already follow the typing, so the keyboard's Done only hands the remote to them.
                    keyboardActions = KeyboardActions(onDone = {
                        keyboard?.hide()
                        focus.moveFocus(FocusDirection.Down)
                    }),
                )

                Spacer(Modifier.height(ThemeSpec.Space.lg))
                val selectedChip = remember { FocusRequester() }
                PageChipRow(entry = selectedChip) {
                    SearchFilter.entries.forEach { sf ->
                        item(key = sf.name) {
                            Chip(
                                sf.label,
                                selected = filter == sf,
                                modifier = if (filter == sf) Modifier.focusRequester(selectedChip) else Modifier,
                            ) { filter = sf }
                        }
                    }
                }

                // PageLoading brings its own gutter, so it sits beside the results column rather than inside its padding.
                if (!catalogLoaded) {
                    PageLoading()
                    return@Column
                }
                // The TV side of the index came up with nothing: either its catalog never loaded, or nothing matched.
                val tvEmpty = fc.isEmpty() && epgHits.isEmpty() && fm.isEmpty() && fs.isEmpty()
                // Every rail parks its focused card on the same viewport line, the way a streaming catalog keeps its
                // sliders aligned while you browse down the results.
                RailFocusLine {
                    LazyColumn(
                        modifier = Modifier.restoreRowFocus().focusGroup(),
                        contentPadding = PagePadding,
                        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                    ) {
                        // An apps-only search does not depend on the catalog, so a catalog failure is not announced there.
                        if (catalogFailed && tvEmpty && filter != SearchFilter.APPS) {
                            item(key = "empty:catalog") {
                                PageError(
                                    "Couldn't load the TV catalog",
                                    retryLabel = "Retry",
                                    onRetry = { catalogRetryTick++ },
                                )
                            }
                        } else if (q.isNotBlank() && tvEmpty && fa.isEmpty() && onlineResults.isEmpty() && !onlineBusy) {
                            item(key = "empty:results") {
                                EmptyState("No results for \"${query.text}\"", hint = "Try fewer words or check the spelling.")
                            }
                        }
                        if (filter == SearchFilter.ALL || filter == SearchFilter.LIVE) {
                            GroupSection("live", "Live TV", fc) { s ->
                                GridPoster(
                                    imageUrl = s.icon,
                                    title = GuideModel.displayName(s.name),
                                    badge = "LIVE",
                                    onClick = {
                                        val c = ProviderSession.current ?: return@GridPoster
                                        Nav.push(Route.Player(c.liveUrl(s.streamId), s.name, "live:${s.streamId}"))
                                    },
                                )
                            }
                            GroupSection("epg", "Programmes", epgHits) { hit ->
                                // Already ended → open the provider's archive window (catch-up) rather than the live edge,
                                // but only when the channel actually has one; otherwise the timeshift URL just stalls.
                                val nowMs = System.currentTimeMillis()
                                val ended = hit.stopMs > 0L && hit.stopMs <= nowMs
                                // The index keeps the channel even when its group is hidden; its logo fills the tile.
                                val ch = com.carteltv.tv.data.xtream.LiveChannels.byId(hit.streamId)
                                val canReplay = ended && (ch?.catchupDays ?: 0) > 0
                                GridPoster(
                                    imageUrl = ch?.icon,
                                    title = hit.title,
                                    subtitle = GuideModel.displayName(hit.channelName),
                                    badge = if (canReplay) "REPLAY" else "GUIDE",
                                    onClick = {
                                        val c = ProviderSession.current ?: return@GridPoster
                                        val url = if (canReplay) c.timeshiftUrl(hit.streamId, hit.startMs, hit.stopMs)
                                            else c.liveUrl(hit.streamId)
                                        Nav.push(Route.Player(url, hit.channelName, "live:${hit.streamId}"))
                                    },
                                )
                            }
                        }
                        // Mixed rows, the Netflix convention: Movies and Series art is a real poster, so
                        // those two rows loom as 2:3 tiles; rows whose tiles have no poster art of their
                        // own — live, guide, apps, online — keep the boxed landscape card.
                        if (filter == SearchFilter.ALL || filter == SearchFilter.MOVIES) GroupSection("movies", "Movies", fm, poster = true) { m ->
                            // A poster tile has a reveal caption of its own, so the rating moves there
                            // from the badge; the badge goes back to naming the row's kind.
                            PosterTile(
                                imageUrl = m.icon,
                                title = m.name,
                                meta = m.rating?.takeIf { it.isNotBlank() },
                                width = RAIL_POSTER_WIDTH,
                                badge = "MOVIE",
                                onClick = { Nav.push(Route.Movie(m.streamId, m.name, m.ext, m.icon, m.rating)) },
                            )
                        }
                        if (filter == SearchFilter.ALL || filter == SearchFilter.SERIES) GroupSection("series", "Series", fs, poster = true) { sv ->
                            PosterTile(
                                imageUrl = sv.cover,
                                title = sv.name,
                                meta = seriesMeta(sv.releaseDate, sv.rating),
                                width = RAIL_POSTER_WIDTH,
                                badge = "SERIES",
                                onClick = { Nav.push(Route.Series(sv.seriesId, sv.name, sv.cover, sv.plot)) },
                            )
                        }
                        if (filter == SearchFilter.ALL || filter == SearchFilter.APPS) GroupSection("apps", "Apps", fa) { a ->
                            // Launcher icons are decoded bitmaps, not URLs, so the monogram plate stands in for art.
                            GridPoster(
                                imageUrl = null,
                                title = a.label,
                                badge = if (a.packageName in AppPreferences.appPins()) "LOCKED" else "APP",
                                onClick = {
                                    if (a.packageName in AppPreferences.appPins()) {
                                        lockedApp = a
                                    } else {
                                        com.carteltv.tv.nav.AppLauncher.launchPackage(appCtx, a.packageName)
                                    }
                                },
                            )
                        }
                        // Online rows, split by their art's own shape once the query is long enough:
                        // TMDB and Stremio titles carry poster art, so they join the poster rails;
                        // YouTube results only have landscape video thumbs, so they run their own
                        // boxed landscape rail where those thumbs read uncut. The heading reads
                        // "Discover": what streaming apps call results from beyond the provider.
                        if (q.length >= 2 && onlineResults.isNotEmpty()) {
                            val webResults = onlineResults.filter { it.source != "youtube" }
                            val ytResults = onlineResults.filter { it.source == "youtube" }
                            GroupSection("online", "Discover", webResults, poster = true) { r ->
                                PosterTile(
                                    imageUrl = r.thumbnailUrl,
                                    title = r.title,
                                    meta = r.subtitle,
                                    onClick = { onOnlineResultClick(r) },
                                )
                            }
                            GroupSection("online:youtube", "YouTube", ytResults) { r ->
                                GridPoster(
                                    imageUrl = r.thumbnailUrl,
                                    title = r.title,
                                    subtitle = r.subtitle,
                                    badge = r.kind,
                                    onClick = { onOnlineResultClick(r) },
                                )
                            }
                        }
                    }
                }
            }
        }


        lockedApp?.let { app ->
            PinGate(
                title = app.label,
                onOk = { pin ->
                    if (AppPreferences.verifyAppPin(app.packageName, pin)) {
                        lockedApp = null
                        com.carteltv.tv.nav.AppLauncher.launchPackage(appCtx, app.packageName)
                        true
                    } else {
                        false
                    }
                },
                onCancel = { lockedApp = null },
            )
        }
    }
}

/** Search rail slot width for the boxed landscape cards: a peek of the next, matching the On Demand rails. */
private val RAIL_CARD_WIDTH = 200.dp

/**
 * Slot width the poster rails share with [PosterTile] itself, so the row's gutters and its peek of
 * the next tile hug the art instead of stranding slack inside every slot.
 */
private val RAIL_POSTER_WIDTH = 160.dp

/**
 * Vertical headroom a poster rail reserves: focus scales the 2:3 tile by [ThemeSpec.Focus.liftPoster]
 * from its middle, which lifts a 240 dp portrait by ~34 dp per side before the focus ring's stroke —
 * 36 dp keeps the whole loom inside the row instead of clipping the gesture the tile exists for.
 */
private val POSTER_RAIL_LOOM_ROOM = 36.dp

/**
 * One results section: its heading, then its rail. Empty sections stay out of the page entirely,
 * so no heading ever stands over nothing. `poster` marks a rail of looming 2:3 poster tiles; the
 * boxed landscape card stays the default for the rows whose tiles have no poster art of their own.
 */
private fun <T> LazyListScope.GroupSection(
    key: String,
    title: String,
    items: List<T>,
    poster: Boolean = false,
    card: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = key) {
        Column(Modifier.fillMaxWidth()) {
            SectionTitle(title, Modifier.fillMaxWidth().padding(top = ThemeSpec.Space.md), note = items.size.toString())
            ResultRail(key, items, poster, card)
        }
    }
}

/**
 * A section's horizontal run of cards: focus comes back to the card that had it, and the row's
 * edges feather into the stage instead of slicing the next card's peek. Poster rails run at the
 * tile's own width with loom headroom; boxed rails keep the quiet [ThemeSpec.Space.sm].
 */
@Composable
private fun <T> ResultRail(zone: String, items: List<T>, poster: Boolean = false, card: @Composable (T) -> Unit) {
    val rowState = rememberLazyListState()
    val wrapState = rememberRailWrapState()
    val wrapFirst = remember { FocusRequester() }
    val focusRegistry = rememberKeyFocusRegistry()
    val itemKeys = items.map { "$zone:${searchItemKey(it)}" }
    LaunchedEffect(zone, itemKeys) {
        restoreKeyFocus("search", itemKeys, focusRegistry) { rowState.scrollToItem(it) }
    }
    LazyRow(
        state = rowState,
        modifier = Modifier
            .fillMaxWidth()
            .restoreRowFocus()
            .railEdgeFade(rowState)
            .railWrapToStart(wrapState, rowState, items.size, wrapFirst),
        contentPadding = PaddingValues(vertical = if (poster) POSTER_RAIL_LOOM_ROOM else ThemeSpec.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
    ) {
        itemsIndexed(items) { index, entry ->
            Box(
                Modifier
                    .width(if (poster) RAIL_POSTER_WIDTH else RAIL_CARD_WIDTH)
                    .keyFocusTarget(focusRegistry, "search", itemKeys[index])
                    .railWrapItem(wrapState, index)
                    .then(if (index == 0) Modifier.focusRequester(wrapFirst) else Modifier),
            ) { card(entry) }
        }
    }
}

private fun searchItemKey(item: Any?): String = when (item) {
    is XtreamClient.LiveStream -> "live:${item.streamId}"
    is com.carteltv.tv.data.xtream.EpgIndexDatabase.ProgramHit -> "epg:${item.streamId}:${item.startMs}"
    is XtreamClient.VodStream -> "movie:${item.streamId}"
    is XtreamClient.Series -> "series:${item.seriesId}"
    is LauncherApp -> "app:${item.packageName}"
    is SearchResult -> "online:${item.source}:${item.url}"
    else -> item.toString()
}

/**
 * The focused-poster caption for a series: "year · rating" out of the listing fields the panel
 * actually fills. Panels disagree — release dates arrive in no single format and often as the
 * "0000-00-00" placeholder, and many listings carry no rating at all — so each part stands alone
 * and whatever the panel omits simply drops out of the line.
 */
private fun seriesMeta(releaseDate: String?, rating: String?): String? =
    listOfNotNull(
        releaseDate?.trim()?.take(4)?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } && it != "0000" },
        rating?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifEmpty { null }

/** Navigates from an online result card. YouTube opens playback; TMDB and Stremio open the title page. */
private fun onOnlineResultClick(result: SearchResult) {
    when {
        result.source == "youtube" && result.url.isNotBlank() -> {
            com.carteltv.tv.ui.youtube.YouTubeDeepLink.post(
                com.carteltv.tv.ui.youtube.YouTubeDeepLink.Request(result.url, result.title)
            )
            if (Nav.current != Route.YouTube) Nav.push(Route.YouTube)
        }
        result.source == "stremio" -> result.url.removePrefix("stremio:").split(':', limit = 2).takeIf { it.size == 2 }?.let { (type, id) ->
            Nav.push(Route.Meta(type, id, result.title, result.thumbnailUrl))
        }
        result.source == "tmdb" && result.url.startsWith("tmdb:") -> result.url.split(':').takeIf { it.size == 3 }?.let { (_, type, id) ->
            Nav.push(Route.Meta(type, "tmdb:$type:$id", result.title, result.thumbnailUrl))
        }
    }
}
