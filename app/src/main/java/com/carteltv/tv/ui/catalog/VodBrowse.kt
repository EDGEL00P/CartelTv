package com.carteltv.tv.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.carteltv.tv.R
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.discovery.DiscoveryFeeds
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.stremio.StremioClient
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.nav.SectionNav
import com.carteltv.tv.recording.RecordingService
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.FocusMemoryStore
import com.carteltv.tv.ui.components.PosterTile
import com.carteltv.tv.ui.components.RailFocusLine
import com.carteltv.tv.ui.components.RailWrapState
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.StagedScreen
import com.carteltv.tv.ui.components.TvDialogs
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.components.keyFocusTarget
import com.carteltv.tv.ui.components.railEdgeFade
import com.carteltv.tv.ui.components.railWrapItem
import com.carteltv.tv.ui.components.railWrapToStart
import com.carteltv.tv.ui.components.rememberKeyFocusRegistry
import com.carteltv.tv.ui.components.rememberRailWrapState
import com.carteltv.tv.ui.components.restoreKeyFocus
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.shimmer
import com.carteltv.tv.ui.components.tidyRailTitle
import com.carteltv.tv.ui.home.resumeLine
import com.carteltv.tv.ui.home.splitTitle
import com.carteltv.tv.ui.live.GuideMetadata
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.theme.*
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec

@Composable
fun NeedProviderNotice() {
    Column(
        Modifier.fillMaxSize().padding(ThemeSpec.Space.margin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            androidx.compose.ui.res
                .stringResource(com.carteltv.tv.R.string.state_off_air),
            fontSize = ThemeSpec.Type.title,
            color = ThemeSemantics.textEmphatic,
            fontWeight = FontWeight.Black,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Text(
            androidx.compose.ui.res
                .stringResource(com.carteltv.tv.R.string.state_off_air_hint),
            fontSize = ThemeSpec.Type.meta,
            color = ThemeSemantics.textSecondary,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(ThemeSpec.Space.lg))
        // An empty screen needs something to act on, and the remote needs somewhere to land.
        ActionButton("Open settings", filled = true, modifier = Modifier.entryFocus()) { SectionNav.go(AppSection.SETUP) }
    }
}

/** Rail tile width: the Netflix browse rail — 2:3 portrait posters, ~5 across the 960dp canvas and a peek of the next. */
private val RAIL_TILE_WIDTH = 160.dp

private val CatalogGridPadding =
    PaddingValues(
        start = ThemeSpec.Space.margin + ThemeSpec.Space.sm,
        end = ThemeSpec.Safe.horizontal + ThemeSpec.Space.sm,
        top = ThemeSpec.Space.md,
        bottom = ThemeSpec.Space.margin,
    )

internal fun catalogGridLoading(
    hasProviderItems: Boolean,
    hasStremioItems: Boolean,
    providerLoadComplete: Boolean,
    stremioLoaded: Boolean,
): Boolean =
    !hasProviderItems && !hasStremioItems &&
        (!providerLoadComplete || !stremioLoaded)

/**
 * The rail's entry tile: the first poster takes focus when the page opens. A zone that already remembers a tile is
 * left to the identity restore, so a section arriving late at the top of the page never pulls focus away.
 */
@Composable
private fun Modifier.entryTile(
    key: String,
    entryKey: String?,
    zone: String,
    requester: FocusRequester,
): Modifier =
    if (key != entryKey) {
        this
    } else {
        entryFocus(enabled = FocusMemoryStore.peek(zone) == null, requester = requester)
    }

/** The provider rail's tile: the house poster pinned to the rail's fixed width, so provider rails and Stremio rails read as one design. */
@Composable
private fun CellPoster(
    imageUrl: String?,
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    progress: Float = 0f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    PosterTile(
        imageUrl = imageUrl,
        title = title,
        meta = subtitle,
        badge = badge,
        width = RAIL_TILE_WIDTH,
        progress = progress,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}

/** The rails' own silhouette while catalogs load, so the page does not collapse into generic list rows. */
@Composable
private fun CatalogGridLoading() {
    LazyColumn(
        modifier = Modifier.shimmer(),
        userScrollEnabled = false,
        contentPadding = CatalogGridPadding,
        verticalArrangement = Arrangement.spacedBy(RailContentHeadroom),
    ) {
        items(3) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                Box(
                    Modifier
                        .fillMaxWidth(0.28f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                        .background(ThemeSemantics.surfaceRaised),
                )
                LazyRow(
                    userScrollEnabled = false,
                    contentPadding = PaddingValues(vertical = RailContentHeadroom),
                    horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                ) {
                    items(6) {
                        Column(Modifier.width(RAIL_TILE_WIDTH)) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                                    .background(ThemeSemantics.surfaceRaised),
                            )
                            // The focused tile's caption, ghosted where the looming poster will reveal it.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                                    .background(ThemeSemantics.surface2),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridHead(
    title: String,
    count: String?,
) {
    SectionTitle(
        tidyRailTitle(title),
        Modifier.padding(top = ThemeSpec.Space.xs, bottom = ThemeSpec.Space.xs),
        note = count,
    )
}

/** One horizontal shelf: its heading, its tiles' stable keys in order, and the titles behind them (the A–Z index). */
private data class CatalogRail(
    val key: String,
    val title: String,
    val count: Int,
    val keys: List<String>,
    val titles: List<String> = emptyList(),
)

/** One rail tile: fixed width so the shelf reads as a slider; carries the identity focus target and the page entry. */
@Composable
private fun RailTile(
    key: String,
    entryKey: String?,
    zone: String,
    first: FocusRequester,
    registry: MutableMap<String, FocusRequester>,
    wrapState: RailWrapState,
    wrapFirst: FocusRequester,
    index: Int,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .width(RAIL_TILE_WIDTH)
            .keyFocusTarget(registry, zone, key)
            .entryTile(key, entryKey, zone, first)
            .railWrapItem(wrapState, index)
            .then(if (index == 0) Modifier.focusRequester(wrapFirst) else Modifier),
    ) { content() }
}

/** The billboard's pick: a title, its art, a metadata line, the action's label and what OK does. */
internal data class HeroPick(
    val title: String,
    val art: String?,
    val meta: String?,
    val cta: String,
    val onOpen: () -> Unit,
)

/** The rail key the billboard rides on. It carries no item keys, so the flat focus-restore index is unshifted. */
private const val HERO_RAIL_KEY = "hero"

/** The billboard's height on the 540dp canvas: tall enough to read as a hero, short enough to leave the rails room. */
private val HERO_HEIGHT = 200.dp

/**
 * The featured billboard that leads the On Demand page, the way every real catalog opens on a title.
 * A Continue-watching card wins (it is what the viewer is mid-way through), then the provider's first
 * film/series, then the first add-on title that carries art. Null when nothing has art to show.
 */
private fun <T> buildHeroPick(
    cw: List<AppPreferences.RecentEntry>,
    cwArt: Map<String, String>,
    providerFilled: List<XtreamClient.Category>,
    rows: Map<String, List<T>>,
    providerTitle: (T) -> String,
    providerArt: (T) -> String?,
    providerOpen: (T) -> Unit,
    stremioFilled: List<CatalogRow>,
    stremioType: String,
): HeroPick? {
    cw.firstOrNull()?.let { e ->
        val parts = splitTitle(e.title)
        val art = e.poster.ifBlank { null } ?: cwArt[GuideMetadata.cwArtKey(e.title)]
        return HeroPick(parts.name, art, resumeLine(parts, e.positionMs, e.durationMs), "Resume") {
            if (e.url.isNotBlank()) Nav.push(Route.Player(e.url, e.title, e.ref))
        }
    }
    providerFilled.firstOrNull()?.let { c ->
        rows[c.category_id]?.firstOrNull()?.let { t ->
            return HeroPick(providerTitle(t), providerArt(t), null, "More info") { providerOpen(t) }
        }
    }
    stremioFilled.forEach { row ->
        row.items.firstOrNull { !it.background.isNullOrBlank() || !it.poster.isNullOrBlank() }?.let { m ->
            val meta =
                listOfNotNull(
                    m.releaseInfo?.take(4)?.takeIf { it.isNotBlank() },
                    m.imdbRating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
                ).joinToString(" · ").ifBlank { null }
            return HeroPick(m.name, m.background ?: m.poster, meta, "More info") {
                Nav.push(Route.Meta(m.type ?: stremioType, m.id, m.name, m.poster))
            }
        }
    }
    return null
}

/** The billboard itself: full-bleed key art under the house veil, title and meta at the foot, one filled action. */
@Composable
internal fun CatalogHero(pick: HeroPick) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT)
            .clip(RoundedCornerShape(ThemeSpec.Card.corner))
            .background(ThemeSemantics.surfaceRest),
    ) {
        HeroArt(pick.art, pick.title, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.BottomStart).padding(ThemeSpec.Space.lg)) {
            Text(
                pick.title,
                fontSize = ThemeSpec.Type.titleL,
                fontFamily = ThemeSpec.Font.display,
                fontWeight = FontWeight.Bold,
                color = ThemeSemantics.textEmphatic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            pick.meta?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontSize = ThemeSpec.Type.meta,
                    color = ThemeSemantics.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = ThemeSpec.Space.xs),
                )
            }
            Spacer(Modifier.height(ThemeSpec.Space.md))
            ActionButton(pick.cta, filled = true) { pick.onOpen() }
        }
    }
}

/**
 * One On Demand page of horizontal rails fed by every configured source: provider categories
 * (Xtream/M3U/Stalker) load one at a time, then Stremio add-on catalogs append below. No source is
 * hidden behind an either/or split, so whatever the user configured surfaces here automatically.
 * The toolbar above the scrolling catalog keeps search, the genre filter, the sort order and the
 * A–Z strip on screen for the whole browse (NN/g: horizontal-only catalogs fatigue), and the
 * player's history for this kind of title opens the page as a Continue watching rail.
 */
@Composable
private fun <T> CombinedGrid(
    provider: XtreamClient?,
    providerCats: List<XtreamClient.Category>?,
    loadProvider: suspend (String) -> List<T>,
    providerKey: (T) -> Any,
    providerSortKey: (T) -> CatalogSortKey,
    providerTitle: (T) -> String,
    providerCard: @Composable (T) -> Unit,
    providerArt: (T) -> String?,
    providerOpen: (T) -> Unit,
    stremioType: String,
    focusZone: String,
) {
    val continueWatching = rememberContinueWatching(movieCatalog = stremioType == "movie")
    val cwArt = rememberCwArt(continueWatching.entries)
    val cwHeading = stringResource(R.string.catalog_continue_watching)
    val cwRemoveLabel = stringResource(R.string.catalog_remove_continue)
    // The toolbar's picks: a genre (null = All), a sort order, and the A–Z strip's letter (null = All).
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(CatalogSort.FEATURED) }
    var letter by remember { mutableStateOf<Char?>(null) }

    val stremioRows = remember(stremioType) { mutableStateOf<List<CatalogRow>?>(null) }

    LaunchedEffect(stremioType) {
        stremioRows.value = null
        stremioRows.value = loadCatalogRows(stremioType)
    }

    // Custom shelves: the profile's own TMDB rows for this media kind.
    var shelfRows by remember(stremioType) { mutableStateOf<List<CatalogRow>>(emptyList()) }
    val shelfTick = ShelfRefresh.tick
    LaunchedEffect(stremioType, shelfTick) {
        val key = AppPreferences.tmdbKey
        val shelves = AppPreferences.shelves().filter { it.media == if (stremioType == "movie") "movie" else "series" }
        shelfRows =
            if (key.isBlank()) {
                emptyList()
            } else {
                shelves
                    .map { sh ->
                        val cards =
                            suspendRunCatching {
                                DiscoveryFeeds.discover(key, if (stremioType == "movie") "movie" else "tv", sh.genreId)
                            }.getOrDefault(emptyList())
                        CatalogRow(
                            sh.title,
                            cards.map { c ->
                                StremioClient.Metum(
                                    id = "tmdb:${if (stremioType == "movie") "movie" else "tv"}:${c.id}",
                                    type = stremioType,
                                    name = c.title,
                                    poster = DiscoveryFeeds.posterUrl(c.poster),
                                    releaseInfo = c.year,
                                    imdbRating = c.rating?.let { "%.1f".format(it) },
                                )
                            },
                        )
                    }.filter { it.items.isNotEmpty() }
            }
    }

    // Provider categories load one at a time: empty rows have no height, so per-row loading fired every request at once.
    val rows = remember(providerCats) { androidx.compose.runtime.mutableStateMapOf<String, List<T>>() }
    var providerLoadComplete by remember(providerCats) { mutableStateOf(false) }
    // Categories arrive before their rows; keep the skeleton until the sequential requests finish.
    LaunchedEffect(providerCats) {
        if (provider != null && providerCats == null) return@LaunchedEffect
        providerLoadComplete = false
        for (c in providerCats.orEmpty()) {
            rows[c.category_id] = suspendRunCatching { loadProvider(c.category_id) }.getOrDefault(emptyList()).take(30)
        }
        providerLoadComplete = true
    }

    val providerFilled = providerCats.orEmpty().filter { !rows[it.category_id].isNullOrEmpty() }
    // A genre pick keeps the provider rails it names. Stremio add-on catalogs are not genre shelves — their
    // row titles ("Top", "Popular") name catalogs, not genres — so they stay on All (documented choice).
    val visibleCats = remember(providerFilled, genre) { visibleCategoriesFor(providerFilled, genre) }
    val sortedProviderRows =
        remember(visibleCats, rows, sort) {
            visibleCats.associate { c -> c.category_id to sortedForBrowse(rows[c.category_id].orEmpty(), sort, providerSortKey) }
        }
    val stremioFilled = stremioRows.value.orEmpty().filter { it.items.isNotEmpty() }
    val stremio =
        remember(stremioFilled, genre, sort) {
            if (genre ==
                null
            ) {
                stremioFilled.map { row -> row.copy(items = sortedForBrowse(row.items, sort, ::metumSortKey)) }
            } else {
                emptyList()
            }
        }
    val cw = continueWatching.entries
    // The billboard leads the default browse (no genre pick): a real catalog opens on one title, not a wall of chips.
    val heroPick =
        remember(cw, providerFilled, stremioFilled, rows, genre, cwArt) {
            if (genre != null) {
                null
            } else {
                buildHeroPick(cw, cwArt, providerFilled, rows, providerTitle, providerArt, providerOpen, stremioFilled, stremioType)
            }
        }
    val loading =
        catalogGridLoading(
            hasProviderItems = providerFilled.isNotEmpty(),
            hasStremioItems = stremioFilled.isNotEmpty(),
            providerLoadComplete = providerLoadComplete,
            stremioLoaded = stremioRows.value != null,
        )

    // One rail per source section, in display order; the keys mirror the rail item keys exactly, so the
    // order the rows render in is the order this list was built in.
    val rails =
        remember(visibleCats, rows, stremio, cw, genre, sort, shelfRows, heroPick) {
            buildList {
                if (heroPick != null) add(CatalogRail(HERO_RAIL_KEY, "", 0, emptyList()))
                if (genre == null && cw.isNotEmpty()) {
                    add(CatalogRail(CW_RAIL_KEY, cwHeading, cw.size, cw.map { "cw:${it.ref}" }, cw.map { splitTitle(it.title).name }))
                }
                visibleCats.forEach { c ->
                    val items = sortedProviderRows[c.category_id].orEmpty()
                    add(
                        CatalogRail(
                            "cat:${c.category_id}",
                            c.category_name,
                            items.size,
                            items.map { "${c.category_id}:${providerKey(it)}" },
                            items.map(providerTitle),
                        ),
                    )
                }
                stremio.forEachIndexed { r, row ->
                    add(
                        CatalogRail(
                            "stremio:$r",
                            row.title,
                            row.items.size,
                            row.items.mapIndexed { i, m -> "poster_stremio_$r:$i:${m.id}" },
                            row.items.map { it.name },
                        ),
                    )
                }
                if (genre == null) {
                    shelfRows.forEachIndexed { r, row ->
                        add(
                            CatalogRail(
                                "shelf:$r",
                                row.title,
                                row.items.size,
                                row.items.mapIndexed { i, m -> "poster_shelf_$r:$i:${m.id}" },
                                row.items.map { it.name },
                            ),
                        )
                    }
                }
            }
        }
    val itemKeys = remember(rails) { rails.flatMap { it.keys } }
    // The A–Z strip offers exactly the letters some visible title files under.
    val letters = remember(rails) { azIndex(rails.flatMap { it.titles }) }

    // Identity-based focus restore: see FocusMemoryStore. The flat index maps back to its rail and tile.
    val columnState = rememberLazyListState()
    // Row states outlive data arrivals, so a late category never resets a rail the user already scrolled.
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val focusRegistry = rememberKeyFocusRegistry()
    // The page's restorer falls back to the first poster, which is also the entry node.
    val first = remember { FocusRequester() }
    val entryKey = itemKeys.firstOrNull()
    // A filter or a sort is changed from the toolbar, so the remote is on a chip when the rails reshape.
    // The identity restore therefore runs only while nothing on this page holds focus (a first visit, or
    // a return from another screen) — it must never yank focus off the control the user just pressed.
    var pageHoldsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(itemKeys) {
        if (!pageHoldsFocus) {
            restoreKeyFocus(focusZone, itemKeys, focusRegistry) { flat ->
                var offset = 0
                for ((railIndex, rail) in rails.withIndex()) {
                    val local = flat - offset
                    if (local < rail.keys.size) {
                        columnState.scrollToItem(railIndex)
                        withFrameNanos { } // let the rail's row attach before it scrolls
                        rowStates[rail.key]?.scrollToItem(local)
                        break
                    }
                    offset += rail.keys.size
                }
            }
        }
    }

    // A letter pick jumps: the column to the first rail with a title filing under the letter, then that
    // rail to the title itself. "All" clears the pick and returns to the top. The tick, not the letter,
    // keys the jump, so pressing the same letter twice jumps twice.
    var jumpTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(jumpTick) {
        if (jumpTick == 0) return@LaunchedEffect
        val target = letter?.let { azJump(it, rails.map { rail -> rail.titles }) }
        if (target == null) {
            columnState.scrollToItem(0)
        } else {
            columnState.scrollToItem(target.first)
            withFrameNanos { } // let the rail's row attach before it scrolls
            rowStates[rails[target.first].key]?.scrollToItem(target.second)
        }
    }
    // A genre pick can shrink the page past the chosen letter; the strip then lets the letter go.
    LaunchedEffect(letters) { if (letter != null && letter !in letters) letter = null }

    Box(
        Modifier.fillMaxSize().onFocusChanged { pageHoldsFocus = it.hasFocus },
    ) {
        Column(Modifier.fillMaxSize()) {
            BrowseToolbar(
                genres = remember(providerCats) { genreChips(providerCats.orEmpty()) },
                genre = genre,
                onGenre = { g -> if (g != genre) genre = g },
                sort = sort,
                onSort = { s -> if (s != sort) sort = s },
                letters = letters,
                letter = letter,
                onLetter = { l ->
                    letter = l
                    jumpTick++
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> {
                        CatalogGridLoading()
                    }

                    rails.isEmpty() -> {
                        if (provider == null) {
                            NeedProviderNotice()
                        } else {
                            // A delegated property can't smart-cast, so the pick is read once into a local.
                            val pickedGenre = genre
                            if (pickedGenre != null) {
                                EmptyState(pickedGenre, hint = stringResource(R.string.catalog_genre_empty))
                            } else {
                                EmptyState(stringResource(R.string.vod_empty), hint = stringResource(R.string.vod_empty_hint))
                            }
                        }
                    }

                    else -> {
                        // Every rail parks its focused card on the same line, the way a streaming catalog keeps its
                        // sliders aligned while you browse down the page.
                        RailFocusLine {
                            LazyColumn(
                                state = columnState,
                                modifier = Modifier.focusGroup().focusRestorer(first),
                                contentPadding = CatalogGridPadding,
                                verticalArrangement = Arrangement.spacedBy(RailContentHeadroom),
                            ) {
                                items(rails, key = { it.key }) { rail ->
                                    if (rail.key == HERO_RAIL_KEY) {
                                        heroPick?.let { CatalogHero(it) }
                                        return@items
                                    }
                                    val rowState = rowStates.getOrPut(rail.key) { LazyListState() }
                                    val wrapState = rememberRailWrapState()
                                    val wrapFirst = remember { FocusRequester() }
                                    Column(Modifier.fillMaxWidth()) {
                                        GridHead(rail.title, stringResource(R.string.catalog_titles_count, rail.count))
                                        LazyRow(
                                            state = rowState,
                                            modifier =
                                                Modifier
                                                    .restoreRowFocus()
                                                    .railEdgeFade(rowState)
                                                    .railWrapToStart(wrapState, rowState, rail.count, wrapFirst),
                                            // Focus headroom: the looming poster stays inside the row's own clip bounds.
                                            contentPadding = PaddingValues(vertical = RailContentHeadroom),
                                            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                                        ) {
                                            when {
                                                rail.key == CW_RAIL_KEY -> {
                                                    itemsIndexed(cw, key = { _, e -> "cw:${e.ref}" }) { index, e ->
                                                        val parts = splitTitle(e.title)
                                                        RailTile(
                                                            "cw:${e.ref}",
                                                            entryKey,
                                                            focusZone,
                                                            first,
                                                            focusRegistry,
                                                            wrapState,
                                                            wrapFirst,
                                                            index,
                                                        ) {
                                                            CellPoster(
                                                                // The player saves history without art; the lookup Home's
                                                                // row uses fills the poster — the tile's monogram stands in until then.
                                                                imageUrl =
                                                                    e.poster.ifBlank { null } ?: cwArt[GuideMetadata.cwArtKey(e.title)],
                                                                title = parts.name,
                                                                subtitle = resumeLine(parts, e.positionMs, e.durationMs),
                                                                progress =
                                                                    if (e.durationMs >
                                                                        0
                                                                    ) {
                                                                        (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f)
                                                                    } else {
                                                                        0f
                                                                    },
                                                                onLongClick = {
                                                                    TvDialogs.menu(parts.name, listOf(cwRemoveLabel), onPick = { i ->
                                                                        if (i == 0) continueWatching.onRemoved(e.ref)
                                                                    })
                                                                },
                                                                onClick = {
                                                                    if (e.url.isNotBlank()) Nav.push(Route.Player(e.url, e.title, e.ref))
                                                                },
                                                            )
                                                        }
                                                    }
                                                }

                                                rail.key.startsWith("cat:") -> {
                                                    val catId = rail.key.removePrefix("cat:")
                                                    itemsIndexed(sortedProviderRows[catId].orEmpty(), key = {
                                                        _,
                                                        t,
                                                        ->
                                                        "$catId:${providerKey(t)}"
                                                    }) { index, t ->
                                                        RailTile(
                                                            "$catId:${providerKey(t)}",
                                                            entryKey,
                                                            focusZone,
                                                            first,
                                                            focusRegistry,
                                                            wrapState,
                                                            wrapFirst,
                                                            index,
                                                        ) { providerCard(t) }
                                                    }
                                                }

                                                rail.key.startsWith("shelf:") -> {
                                                    val r = rail.key.removePrefix("shelf:").toInt()
                                                    val row = shelfRows[r]
                                                    itemsIndexed(row.items, key = { i, m -> "poster_shelf_$r:$i:${m.id}" }) { i, m ->
                                                        RailTile(
                                                            "poster_shelf_$r:$i:${m.id}",
                                                            entryKey,
                                                            focusZone,
                                                            first,
                                                            focusRegistry,
                                                            wrapState,
                                                            wrapFirst,
                                                            i,
                                                        ) {
                                                            LabeledPoster(
                                                                m,
                                                                modifier = Modifier,
                                                                onLongClick = {
                                                                    AppPreferences.addToLibrary(
                                                                        AppPreferences.LibraryEntry(
                                                                            id = m.id,
                                                                            type = stremioType,
                                                                            name = m.name,
                                                                            poster = m.poster.orEmpty(),
                                                                            year = m.releaseInfo.orEmpty(),
                                                                            rating = m.imdbRating.orEmpty(),
                                                                        ),
                                                                    )
                                                                },
                                                                onClick = { Nav.push(Route.Meta(stremioType, m.id, m.name, m.poster)) },
                                                            )
                                                        }
                                                    }
                                                }

                                                else -> {
                                                    val r = rail.key.removePrefix("stremio:").toInt()
                                                    val row = stremio[r]
                                                    itemsIndexed(row.items, key = { i, m -> "poster_stremio_$r:$i:${m.id}" }) { i, m ->
                                                        RailTile(
                                                            "poster_stremio_$r:$i:${m.id}",
                                                            entryKey,
                                                            focusZone,
                                                            first,
                                                            focusRegistry,
                                                            wrapState,
                                                            wrapFirst,
                                                            i,
                                                        ) {
                                                            LabeledPoster(m, modifier = Modifier, onLongClick = {
                                                                val ref = "stremio:${m.id}"
                                                                val type = (m.type ?: stremioType).let { if (it == "tv") "series" else it }
                                                                TvDialogs.menu(
                                                                    m.name,
                                                                    listOf(
                                                                        if (AppPreferences.inLibrary(
                                                                                ref,
                                                                            )
                                                                        ) {
                                                                            "Remove from library"
                                                                        } else {
                                                                            "Add to library"
                                                                        },
                                                                    ),
                                                                ) {
                                                                    if (AppPreferences.inLibrary(ref)) {
                                                                        AppPreferences.removeFromLibrary(ref)
                                                                    } else {
                                                                        AppPreferences.addToLibrary(
                                                                            AppPreferences.LibraryEntry(
                                                                                id = ref,
                                                                                type = type,
                                                                                name = m.name,
                                                                                poster = (m.poster ?: m.background).orEmpty(),
                                                                                backdrop = m.background.orEmpty(),
                                                                                year = m.releaseInfo?.take(4).orEmpty(),
                                                                                rating = m.imdbRating.orEmpty(),
                                                                            ),
                                                                        )
                                                                    }
                                                                }
                                                            }, onClick = {
                                                                Nav.push(Route.Meta(m.type ?: stremioType, m.id, m.name, m.poster))
                                                            })
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
        }
    }
}

/** The rail Continue watching rides on, keyed apart from every catalog rail. */
private const val CW_RAIL_KEY = "cw"

@Composable
internal fun MoviesContent() {
    val client = ProviderSession.current
    var cats by remember { mutableStateOf<List<XtreamClient.Category>?>(null) }
    LaunchedEffect(Unit) { cats = client?.let { c -> suspendRunCatching { c.movieCategories() }.getOrDefault(emptyList()) } }
    ScreenPage("Movies", header = false) {
        // The frame owns the backdrop; the staged section only tints it, so Movies and TV Shows read apart.
        StagedScreen(section = com.carteltv.tv.nav.AppSection.MOVIES) {
            CombinedGrid(
                provider = client,
                providerCats = cats,
                loadProvider = { client?.vodStreams(it) ?: emptyList() },
                providerKey = { it.streamId },
                providerSortKey = { CatalogSortKey(it.name, ratingValue(it.rating)) },
                providerTitle = { it.name },
                providerCard = { m ->
                    val ref = "movie:${m.streamId}"
                    CellPoster(
                        imageUrl = m.icon,
                        title = m.name,
                        // IPTV film names carry the year in brackets ("The Matrix (1999)") — the caption's only year source.
                        subtitle = posterMeta(titleYear(m.name), ratingLabel(m.rating)),
                        badge = ratingLabel(m.rating),
                        onLongClick = {
                            TvDialogs.menu(
                                m.name,
                                listOf(if (AppPreferences.inLibrary(ref)) "Remove from library" else "Add to library"),
                            ) {
                                if (AppPreferences.inLibrary(ref)) {
                                    AppPreferences.removeFromLibrary(ref)
                                } else {
                                    AppPreferences.addToLibrary(
                                        AppPreferences.LibraryEntry(
                                            id = ref,
                                            type = "movie",
                                            name = m.name,
                                            poster = m.icon.orEmpty(),
                                            year = titleYear(m.name).orEmpty(),
                                            rating = ratingLabel(m.rating).orEmpty(),
                                        ),
                                    )
                                }
                            }
                        },
                        onClick = {
                            Nav.push(Route.Movie(m.streamId, m.name, m.ext, m.icon, m.rating))
                        },
                    )
                },
                providerArt = { it.icon },
                providerOpen = { m -> Nav.push(Route.Movie(m.streamId, m.name, m.ext, m.icon, m.rating)) },
                stremioType = "movie",
                focusZone = "movies",
            )
        }
    }
}

@Composable
internal fun SeriesContent() {
    val client = ProviderSession.current
    var cats by remember { mutableStateOf<List<XtreamClient.Category>?>(null) }
    LaunchedEffect(Unit) { cats = client?.let { c -> suspendRunCatching { c.seriesCategories() }.getOrDefault(emptyList()) } }
    ScreenPage("Series", header = false) {
        StagedScreen(section = com.carteltv.tv.nav.AppSection.TV) {
            CombinedGrid(
                provider = client,
                providerCats = cats,
                loadProvider = { client?.series(it) ?: emptyList() },
                providerKey = { it.seriesId },
                providerSortKey = { CatalogSortKey(it.name, ratingValue(it.rating)) },
                providerTitle = { it.name },
                providerCard = { sv ->
                    val ref = "series:${sv.seriesId}"
                    CellPoster(
                        // Poster first: a landscape backdrop crops badly inside 2:3 art.
                        imageUrl = sv.cover ?: sv.backdrop?.firstOrNull(),
                        title = sv.name,
                        subtitle = posterMeta(sv.releaseDate?.take(4)?.takeIf { it.isNotBlank() }, ratingLabel(sv.rating)),
                        badge = ratingLabel(sv.rating),
                        onLongClick = {
                            TvDialogs.menu(
                                sv.name,
                                listOf(if (AppPreferences.inLibrary(ref)) "Remove from library" else "Add to library"),
                            ) {
                                if (AppPreferences.inLibrary(ref)) {
                                    AppPreferences.removeFromLibrary(ref)
                                } else {
                                    AppPreferences.addToLibrary(
                                        AppPreferences.LibraryEntry(
                                            id = ref,
                                            type = "series",
                                            name = sv.name,
                                            poster = (sv.cover ?: sv.backdrop?.firstOrNull()).orEmpty(),
                                            year = sv.releaseDate?.take(4).orEmpty(),
                                            rating = ratingLabel(sv.rating).orEmpty(),
                                        ),
                                    )
                                }
                            }
                        },
                        onClick = {
                            Nav.push(Route.Series(sv.seriesId, sv.name, sv.cover, sv.plot))
                        },
                    )
                },
                providerArt = { sv -> sv.cover ?: sv.backdrop?.firstOrNull() },
                providerOpen = { sv -> Nav.push(Route.Series(sv.seriesId, sv.name, sv.cover, sv.plot)) },
                stremioType = "series",
                focusZone = "series",
            )
        }
    }
}
