@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.carteltv.tv.ui.home
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.carteltv.tv.data.apps.AppsRepository
import com.carteltv.tv.data.apps.LauncherApp
import com.carteltv.tv.data.espn.EspnScoreboardClient
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.stremio.StremioClient
import com.carteltv.tv.data.watch.WatchNextItem
import com.carteltv.tv.data.watch.WatchNextRepository
import com.carteltv.tv.data.xtream.GuideModel
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.data.youtube.YouTubeApiClient
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.nav.SectionNav
import com.carteltv.tv.ui.apps.AppTile
import com.carteltv.tv.ui.catalog.CatalogRow
import com.carteltv.tv.ui.components.CardArtwork
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.AutoSizeText
import com.carteltv.tv.ui.components.LocalTitleFocus
import com.carteltv.tv.ui.components.PosterTile
import com.carteltv.tv.ui.components.PrefetchAhead
import com.carteltv.tv.ui.components.rememberAppIcon
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.TABULAR
import com.carteltv.tv.ui.components.TitleFocus
import com.carteltv.tv.ui.components.TvDialogs
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.components.keyFocusTarget
import com.carteltv.tv.ui.components.rememberKeyFocusRegistry
import com.carteltv.tv.ui.components.restoreKeyFocus
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.shimmer
import com.carteltv.tv.ui.hero.BigEvent
import com.carteltv.tv.ui.live.ChannelLogo
import com.carteltv.tv.ui.live.GuideMetadata
import com.carteltv.tv.ui.nav.HomeMenuSpec
import com.carteltv.tv.ui.nav.HomePinSpec
import com.carteltv.tv.ui.nav.MenuKeyHub
import com.carteltv.tv.ui.nav.NetworkBadge
import com.carteltv.tv.ui.nav.OverscanV
import com.carteltv.tv.ui.nav.ShellChrome
import com.carteltv.tv.ui.nav.appMenuSpec
import com.carteltv.tv.ui.nav.bleedUnderShell
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.nav.streamingApps
import com.carteltv.tv.ui.sports.PlateColors
import com.carteltv.tv.ui.sports.clockOf
import com.carteltv.tv.ui.sports.plateColors
import com.carteltv.tv.ui.sports.stateLabel
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.theme.glassSurface
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.combinedClickableNoIndication
import com.carteltv.tv.ui.components.focusZLift
import com.carteltv.tv.ui.components.railEdgeFade
import com.carteltv.tv.ui.components.RailFocusLine
import kotlinx.coroutines.launch

/** What a channel is airing right now; [progress] is the elapsed share of the programme (0 when unknown). */
internal data class NowOn(val title: String, val progress: Float)

/** What a game card shows inside itself: both sides, the time (or live score), and whether it is on now. */
internal data class ShelfGame(
    val abbrB: String, val abbrA: String, val logoB: String?, val logoA: String?, val time: String,
    val colorB: String? = null, val altColorB: String? = null, val colorA: String? = null, val altColorA: String? = null,
    val live: Boolean = false, val status: String? = null, val network: String? = null,
)

/** One card on a Home shelf. The class says what the card is; [Shelf.face] says how its row lays it out. */
internal sealed interface ShelfEntry {
    /** Stable key for the lazy row. */
    val key: String
    /** What the card is called. */
    val title: String
    /** MENU or hold-OK opens these options; null when the card has none. */
    val menu: (() -> Unit)? get() = null
    val onClick: () -> Unit

    /** A title or programme: artwork, a caption for the hero, and how far in it is. */
    data class Card(
        override val key: String, override val title: String, override val onClick: () -> Unit,
        val art: String? = null, val subtitle: String? = null, val progress: Float = 0f,
        override val menu: (() -> Unit)? = null,
    ) : ShelfEntry

    /** A game, live or upcoming; the card reads everything else off [game]. */
    data class Game(
        override val key: String, override val title: String, override val onClick: () -> Unit,
        val game: ShelfGame, val note: String? = null,
    ) : ShelfEntry

    /** A live channel and what it is airing now, when the listings say. */
    data class Channel(
        override val key: String, override val title: String, override val onClick: () -> Unit,
        val stream: XtreamClient.LiveStream, val onNow: NowOn? = null,
    ) : ShelfEntry

    /** An installed app; hold-OK opens its options. */
    data class App(
        override val key: String, override val title: String, override val onClick: () -> Unit,
        val app: LauncherApp, val locked: Boolean, override val menu: () -> Unit,
    ) : ShelfEntry

    /** A small information card: a label, a value and supporting lines, for live data the home can show at a glance.
     *  [accent] tints the value, giving each card's live data its own semantic colour. */
    data class Info(
        override val key: String, override val title: String, override val onClick: () -> Unit,
        val value: String, val lines: List<String> = emptyList(), val accent: Color? = null,
    ) : ShelfEntry

    /** What the hero shows while this card has the floor; null for the cards it does not mirror. */
    fun focusTitle(): TitleFocus? = when (this) {
        is Card -> TitleFocus(title, art, subtitle)
        is Game -> TitleFocus(title, null, note, gameKey = key)
        is Channel, is App, is Info -> null
    }
}

/** How a row lays its cards out. */
internal enum class ShelfFace { CARD, CATALOG, LIVE_GAME, UPCOMING, CHANNEL, APP, SERVICE, INFO }

/** One row of Home. A [subheading] row is the second part of the shelf above it (Live Sports' Upcoming). */
internal data class Shelf(
    val key: String,
    val title: String,
    val face: ShelfFace,
    val entries: List<ShelfEntry>,
    val note: String? = null,
    val subheading: Boolean = false,
)

/* Card sizes from the concept (px ÷ 2). The banner and the Up Next tiles take theirs from the reference picture. */
/** The banner, measured from the top of the screen: a content-forward share that leaves the shelves room to
 *  breathe, less the overscan. It runs under the sidebar. */
/** Four cards share the safe viewport width, with the tight gaps and 16:9 art of a Netflix rail. */
private const val HomeVisibleCards = 4
private val HomeCardGap = ThemeSpec.Space.md
private val MinHomeCardW = 160.dp
private val CardShape = RoundedCornerShape(ThemeSpec.Card.corner)
/** A catalogue tile: portrait 2:3 at the width every poster rail in the app shares, so Home's rows read as the browse pages'. */
private val CatalogTileW = 160.dp
/** Vertical headroom a catalogue rail reserves inside its own clip: focus scales the 2:3 tile to
 *  [ThemeSpec.Focus.liftPoster], and 240dp of art looming at 1.28 grows ~34dp each way — 36 covers it. */
private val PosterRailLoomRoom = 16.dp
/** The boxed CARD frame's own loom room: enough for the restrained focus lift without wasting the row. */
private val BoxedLoomRoom = 12.dp
/** An Up Next game, a listing and a channel: each big enough for its two lines at the house type scale. */
private val GameW = 190.dp
private val GameH = 148.dp
private val GameLogo = 24.dp
private val UpcomingW = 290.dp
private val UpcomingH = 60.dp
private val ChannelW = 260.dp
private val ChannelH = 72.dp
private val InfoW = 192.dp
private val InfoH = 116.dp
/** Home is a persistent vertical browse feed: a cinematic hero followed by every shelf. */
@Composable
internal fun HomeStage(
    hero: @Composable (modifier: Modifier, contentInset: Dp) -> Unit,
    heroFocus: FocusRequester,
    shelves: List<Shelf>,
    entryEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val entryTaken = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        ShellChrome.lastContentFocus = heroFocus
        onDispose { if (ShellChrome.lastContentFocus === heroFocus) ShellChrome.lastContentFocus = null }
    }

    // Identity-based focus memory: Back from a detail screen lands on the exact Home card again.
    val columnState = rememberLazyListState()
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val focusRegistry = rememberKeyFocusRegistry()
    val itemKeys = remember(shelves) { shelves.flatMap { shelf -> shelf.entries.map { entry -> "${shelf.key}:${entry.key}" } } }
    LaunchedEffect(itemKeys) {
        restoreKeyFocus("home", itemKeys, focusRegistry) { flat ->
            var offset = 0
            for ((shelfIndex, shelf) in shelves.withIndex()) {
                val local = flat - offset
                if (local < shelf.entries.size) {
                    columnState.scrollToItem(shelfIndex)
                    withFrameNanos { }
                    withFrameNanos { }
                    rowStates[shelf.key]?.scrollToItem(local)
                    break
                }
                offset += shelf.entries.size
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val cardWidth = (
            (maxWidth - ThemeSpec.Safe.horizontal * 2 - HomeCardGap * (HomeVisibleCards - 1)) / HomeVisibleCards
        ).coerceAtLeast(MinHomeCardW)
        val cardHeight = cardWidth * 9f / 16f
        RailFocusLine {
            LazyColumn(
                state = columnState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusGroup()
                    .onFocusChanged { if (it.hasFocus) entryTaken[0] = true },
                contentPadding = PaddingValues(bottom = ThemeSpec.Safe.vertical),
            ) {
                item(key = "home-hero", contentType = "hero") {
                    hero(
                        Modifier
                            .fillMaxWidth()
                            .height(HomeHeroHeight)
                            .bleedUnderShell(underStart = false)
                            .onFocusChanged { if (it.hasFocus) ShellChrome.lastContentFocus = heroFocus }
                            .entryFocus(enabled = entryEnabled && !entryTaken[0], requester = heroFocus),
                        ThemeSpec.Safe.horizontal - ThemeSpec.Space.xl,
                    )
                }
                itemsIndexed(shelves, key = { _, shelf -> shelf.key }, contentType = { _, shelf -> shelf.face }) { _, shelf ->
                    val rowState = rememberLazyListState()
                    rowStates[shelf.key] = rowState
                    ShelfBlock(
                        shelf = shelf,
                        rowState = rowState,
                        focusRegistry = focusRegistry,
                        cardWidth = cardWidth,
                        cardHeight = cardHeight,
                    )
                }
            }
        }
    }
}

/** The featured area leaves the first shelf and its card captions inside a 540dp-high viewport. */
internal val HomeHeroHeight = 280.dp

/**
 * One shelf: its heading, then the cards in a row that remembers where focus left them. [firstCard] lets the
 * caller hold on to the row's entry card; the first row hands its own to the banner above.
 */
@Composable
internal fun ShelfBlock(
    shelf: Shelf,
    rowState: LazyListState,
    focusRegistry: MutableMap<String, FocusRequester>,
    cardWidth: Dp,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    firstCard: FocusRequester? = null,
) {
    val scope = rememberCoroutineScope()
    val first = firstCard ?: remember { FocusRequester() }
    // The index holding focus, so RIGHT is only claimed on a row's last card.
    val focusedIndex = remember { intArrayOf(-1) }
    val livePulse = if (shelf.face == ShelfFace.LIVE_GAME) rememberLiveEdge() else null
    PrefetchAhead(rowState, remember(shelf.entries) { shelf.entries.mapNotNull { (it as? ShelfEntry.Card)?.art } }, cardWidth, cardHeight)
    Column(modifier.fillMaxWidth()) {
        when {
            shelf.subheading -> SubHeading(shelf.title, shelf.note)
            else -> SectionTitle(
                shelf.title,
                Modifier.padding(start = ThemeSpec.Safe.horizontal, end = ThemeSpec.Safe.horizontal, top = ThemeSpec.Space.sm, bottom = ThemeSpec.Space.sm),
                note = shelf.note,
            )
        }
        LazyRow(
            state = rowState,
            modifier = Modifier
                .restoreRowFocus()
                // The peek of the next card dissolves into the banner instead of being sliced at the viewport.
                .railEdgeFade(rowState)
                .onPreviewKeyEvent { ev ->
                    val last = shelf.entries.lastIndex
                    // RIGHT on the last card wraps to the first: the row scrolls home and its entry node takes focus.
                    val wrap = ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight && last > 0 && focusedIndex[0] == last
                    if (wrap) scope.launch { rowState.scrollToItem(0); first.awaitFocus() }
                    wrap
                },
            // The sides line the first card up with the banner's copy; the top gap leaves a lifted card room
            // inside the row's clip. CARD and CATALOG tiles both loom at ThemeSpec.Focus.liftPoster —
            // CARD's boxed frame needs the smaller room, CATALOG's taller posters the larger.
            contentPadding = when (shelf.face) {
                ShelfFace.CATALOG -> PaddingValues(start = ThemeSpec.Safe.horizontal, end = ThemeSpec.Safe.horizontal, top = PosterRailLoomRoom, bottom = PosterRailLoomRoom)
                ShelfFace.CARD -> PaddingValues(start = ThemeSpec.Safe.horizontal, end = ThemeSpec.Safe.horizontal, top = BoxedLoomRoom, bottom = BoxedLoomRoom)
                else -> PaddingValues(
                    start = ThemeSpec.Safe.horizontal,
                    end = ThemeSpec.Safe.horizontal,
                    top = ThemeSpec.Space.md,
                    bottom = ThemeSpec.Space.md,
                )
            },
            horizontalArrangement = Arrangement.spacedBy(HomeCardGap),
        ) {
            itemsIndexed(shelf.entries, key = { _, e -> e.key }, contentType = { _, _ -> shelf.face }) { i, entry ->
                val itemRequester = remember(entry.key) { if (i == 0) first else FocusRequester() }
                val item = Modifier
                    .keyFocusTarget(focusRegistry, zone = "home", key = "${shelf.key}:${entry.key}", requester = itemRequester)
                    // hasFocus, not isFocused: a catalogue tile's focusable is PosterTile's inner box, one
                    // level below this wrapper — the same ancestor wiring VodBrowse's RailTile uses.
                    .onFocusChanged {
                        if (it.hasFocus) {
                            focusedIndex[0] = i
                            ShellChrome.lastContentFocus = itemRequester
                        }
                    }
                val app = entry as? ShelfEntry.App
                if (app != null && shelf.face == ShelfFace.APP) {
                    AppTile(app.app, app.locked, item, onClick = entry.onClick, onLongClick = app.menu)
                } else {
                    ShelfCard(entry, shelf.face, livePulse, item, cardWidth, cardHeight)
                }
            }
        }
    }
}

/** One shelf card: the glass surface, its focus report to the hero, its options on MENU and hold-OK, and its face. */
@Composable
private fun ShelfCard(
    entry: ShelfEntry,
    face: ShelfFace,
    livePulse: State<Float>?,
    modifier: Modifier,
    cardWidth: Dp,
    cardHeight: Dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val report = LocalTitleFocus.current
    // The hero above mirrors the card that has the floor — except the catalogue face, whose PosterTile
    // makes the same report from its own focus; two live reports would fight over the hero mirror.
    if (report != null && focused && face != ShelfFace.CATALOG) {
        val mirrored = entry.focusTitle()
        DisposableEffect(entry.key) {
            report(mirrored)
            onDispose { report(null) }
        }
    }
    val menu = entry.menu
    // While this card holds focus, its MENU opens the card's options (MainActivity asks the hub first).
    DisposableEffect(menu, focused) {
        MenuKeyHub.set(if (menu != null) ({ menu(); true }) else null, enabled = menu != null && focused)
        onDispose { MenuKeyHub.set(null, enabled = true) }
    }
    val click = if (menu != null) {
        Modifier.combinedClickableNoIndication(interactionSource = interaction, onLongClick = menu, onClick = entry.onClick)
    } else {
        Modifier.clickableNoIndication(interaction, entry.onClick)
    }
    // One focus-scale state per card — two would double the focus click sound. The boxed CARD face takes
    // its frame (and its scale state) from the house cardSurface, as the grid's GridPoster does, at the
    // poster lift Netflix gives its continue-watching boxes; the CATALOG face leaves frame, scale, click
    // and report to its PosterTile; every other face stays the single-purpose box it was, the same lift
    // and glass spelled directly.
    val surface = if (face == ShelfFace.CARD) {
        Modifier.focusZLift(focused).cardSurface(focused, lift = ThemeSpec.Focus.liftPoster, pressed = pressed).then(click)
    } else {
        Modifier.focusZLift(focused)
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed = pressed))
            .glassSurface(focused, CardShape)
            .then(click)
    }
    when (face) {
        ShelfFace.CARD -> {
            val card = entry as? ShelfEntry.Card
            val progress = card?.progress ?: 0f
            // One framed box per card, after the grid's classic card anatomy: art and caption share one
            // frame, so a rail reads as a row of boxes instead of tiles with captions floating beneath.
            Box(modifier.width(cardWidth).then(surface)) {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(cardHeight)) {
                        CardArtwork(card?.art, entry.title, Modifier.fillMaxSize())
                        if (progress > 0f) ResumeBar(Modifier.align(Alignment.BottomStart), progress, 2.dp)
                    }
                    // The content block: a raised plate inside the frame, in place of the old free-floating caption.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(ThemeSemantics.surface2)
                            .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.cozy),
                        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
                    ) {
                        Text(
                            entry.title, fontSize = ThemeSpec.Type.title, fontFamily = ThemeSpec.Font.broadcast,
                            fontWeight = FontWeight.SemiBold,
                            color = if (focused) ThemeSemantics.textEmphatic else ThemeSemantics.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        // Keep the metadata line even when it is empty so every card in the rail has the same height.
                        Text(
                            card?.subtitle.orEmpty(), fontSize = ThemeSpec.Type.meta, fontFamily = ThemeSpec.Font.body,
                            color = ThemeSemantics.textSecondary,
                            minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        ShelfFace.CATALOG -> {
            val card = entry as? ShelfEntry.Card
            // The catalogue tile is the house PosterTile: portrait 2:3 art alone at rest, the caption and
            // the hero mirror revealed on focus — the same TitleFocus the boxed face reported. PosterTile
            // owns the card's one focus interaction, click included, so this face's modifier is only the
            // row's identity target, the same ancestor wiring VodBrowse's RailTile gives its posters.
            // Catalogue entries carry no options, so the MENU hub above stays dormant for this face.
            PosterTile(
                imageUrl = card?.art,
                title = entry.title,
                meta = card?.subtitle,
                modifier = modifier,
                width = CatalogTileW,
                progress = card?.progress ?: 0f,
                onClick = entry.onClick,
            )
        }
        ShelfFace.LIVE_GAME -> {
            val game = (entry as? ShelfEntry.Game)?.game
            Box(
                modifier.size(GameW, GameH).then(surface)
                    // A live card's edge breathes red while it rests; focus replaces it with the house ring.
                    .then(if (game?.live == true && !focused) Modifier.rememberLiveEdge(livePulse) else Modifier),
            ) { if (game != null) MiniBug(game) }
        }
        ShelfFace.UPCOMING -> {
            val game = entry as? ShelfEntry.Game
            Box(modifier.width(UpcomingW).height(UpcomingH).then(surface).padding(horizontal = ThemeSpec.Space.cozy)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                    game?.let {
                        TeamMark(it.game.logoB)
                        TeamMark(it.game.logoA)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
                        AutoSizeText(entry.title, fontSize = ThemeSpec.Type.cardTitle, fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.SemiBold, color = ThemeSemantics.textPrimary, minScale = 0.8f)
                        game?.note?.let {
                            Text(it, fontSize = ThemeSpec.Type.label, fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.Bold, color = ThemeSemantics.textSecondary, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1)
                        }
                    }
                }
            }
        }
        ShelfFace.CHANNEL -> {
            val channel = entry as? ShelfEntry.Channel
            Box(modifier.width(ChannelW).height(ChannelH).then(surface).padding(ThemeSpec.Space.cozy)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                    channel?.let { ChannelLogo(it.stream, ChannelH - ThemeSpec.Space.lg) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
                        AutoSizeText(entry.title, fontSize = ThemeSpec.Type.cardTitle, fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.SemiBold, color = ThemeSemantics.textPrimary, minScale = 0.8f)
                        channel?.let { c ->
                            // Only a card with a listing needs the channel's name under the programme.
                            if (c.onNow != null) {
                                Text(GuideModel.displayName(c.stream.name), fontSize = ThemeSpec.Type.label, fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.Bold, color = ThemeSemantics.textTertiary, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            c.onNow?.let { ResumeBar(Modifier.fillMaxWidth(), it.progress, 2.dp) }
                        }
                    }
                }
            }
        }
        ShelfFace.SERVICE -> {
            val app = entry as? ShelfEntry.App ?: return
            val icon = rememberAppIcon(app.app)
            Row(
                modifier
                    .widthIn(min = 120.dp)
                    .height(56.dp)
                    .then(surface)
                    .padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (icon != null) {
                        androidx.compose.foundation.Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxSize())
                    } else {
                        Text(app.title.take(1).uppercase(), fontSize = ThemeSpec.Type.body, color = ThemeSemantics.accent, fontWeight = FontWeight.Bold)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        app.title, fontSize = ThemeSpec.Type.meta, fontFamily = ThemeSpec.Font.broadcast,
                        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (focused) ThemeSemantics.textEmphatic else ThemeSemantics.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Open", fontSize = ThemeSpec.Type.label, fontFamily = ThemeSpec.Font.broadcast,
                        color = ThemeSemantics.textSecondary, maxLines = 1,
                    )
                }
            }
        }
        ShelfFace.INFO -> {
            val info = entry as? ShelfEntry.Info ?: return
            val tint = ThemeSemantics.textSecondary
            Box(
                modifier.width(InfoW).height(InfoH).then(surface)
                    .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.sm),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
                    Text(
                        info.title, fontSize = ThemeSpec.Type.label, fontFamily = ThemeSpec.Font.broadcast,
                        fontWeight = FontWeight.Bold, color = tint, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        info.value, fontSize = ThemeSpec.Type.title, fontFamily = ThemeSpec.Font.broadcast,
                        fontWeight = FontWeight.Bold, color = info.accent ?: ThemeSemantics.textEmphatic, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    info.lines.take(3).forEach { line ->
                        Text(
                            line, fontSize = ThemeSpec.Type.meta, fontFamily = ThemeSpec.Font.body,
                            color = ThemeSemantics.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        ShelfFace.APP -> Unit
    }
}

/** The house progress track: a translucent rail with the accent fill, [height] thick. */
@Composable
private fun ResumeBar(modifier: Modifier, progress: Float, height: Dp) {
    Box(modifier.fillMaxWidth().height(height).background(ThemeSemantics.progressTrack)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(ThemeSemantics.accent))
    }
}

/** One breath shared by the live cards on a shelf, read in the draw phase so the pulse repaints an edge without recomposing. */
@Composable
private fun rememberLiveEdge(): State<Float> = rememberInfiniteTransition(label = "rememberLiveEdge").animateFloat(
    initialValue = 1f, targetValue = 0.35f,
    animationSpec = infiniteRepeatable(tween(ThemeSpec.Motion.PULSE_MS, easing = LinearEasing), RepeatMode.Reverse), label = "edge",
)

private fun Modifier.rememberLiveEdge(pulse: State<Float>?): Modifier {
    if (pulse == null) return this
    return drawWithContent {
        drawContent()
        val radius = ThemeSpec.Card.corner
        drawRoundRect(
            ThemeSemantics.live.copy(alpha = pulse.value),
            cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
            style = Stroke(ThemeSpec.Focus.ringWidth.toPx()),
        )
    }
}

private val MATCH_SCORE = Regex("\\d+\\u2013\\d+")

/**
 * A game card as a scorebug read top to bottom: each side's colour, mark, name and score on its own line,
 * the side trailing dimmed, then the period and clock over the broadcaster's own mark.
 */
@Composable
internal fun MiniBug(g: ShelfGame) {
    val scores = if (MATCH_SCORE.matches(g.time)) g.time.split('\u2013') else emptyList()
    val away = scores.getOrNull(0)?.toIntOrNull()
    val home = scores.getOrNull(1)?.toIntOrNull()
    val ink = ThemeSemantics.onMedia
    val trailing = ink.copy(alpha = 0.45f)
    Column(Modifier.fillMaxSize().background(ThemeSemantics.mediaInk).padding(ThemeSpec.Space.md)) {
        TeamLine(g.abbrB, g.logoB, plateColors(g.colorB, g.altColorB, ThemeSemantics.cornerRed), scores.getOrNull(0), if (away != null && home != null && away < home) trailing else ink)
        TeamLine(g.abbrA, g.logoA, plateColors(g.colorA, g.altColorA, ThemeSemantics.cornerBlue), scores.getOrNull(1), if (away != null && home != null && home < away) trailing else ink)
        Row(Modifier.fillMaxWidth().padding(top = ThemeSpec.Space.xs), verticalAlignment = Alignment.CenterVertically) {
            AutoSizeText(
                g.status?.removePrefix("LIVE ")?.takeIf { it.isNotBlank() } ?: g.time,
                fontSize = ThemeSpec.Type.meta, fontFamily = ThemeSpec.Font.mono, fontWeight = FontWeight.Bold,
                color = if (g.live) ThemeSemantics.onMedia else ThemeSemantics.onMediaMuted, fontFeatureSettings = TABULAR,
                modifier = Modifier.weight(1f),
            )
            g.network?.let { NetworkBadge(it, ThemeSpec.Space.xl) }
        }
    }
}

/** One side of the scorebug: a bar of the team's colour, its mark, its name, and its score in mono. */
@Composable
private fun TeamLine(abbr: String, logo: String?, colors: PlateColors, score: String?, ink: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
        Box(Modifier.width(ThemeSpec.Space.xs).height(GameLogo).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)).background(colors.outer))
        if (logo != null) AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(GameLogo))
        AutoSizeText(abbr, fontSize = ThemeSpec.Type.cardTitle, fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.Bold, color = ink, modifier = Modifier.weight(1f))
        Text(
            score.orEmpty(), fontSize = ThemeSpec.Type.score, lineHeight = ThemeSpec.Type.score * 1.05f,
            fontFamily = ThemeSpec.Font.mono, fontWeight = FontWeight.Bold, color = ink,
            style = TextStyle(fontFeatureSettings = TABULAR), maxLines = 1, softWrap = false,
        )
    }
}

/** A team's mark at list size; nothing when the feed has none. */
@Composable
private fun TeamMark(logo: String?) {
    if (logo != null) {
        AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(UpcomingH - ThemeSpec.Space.lg))
    }
}

/** The second row of a two-part shelf: a tracked label, lighter than a full heading. */
@Composable
private fun SubHeading(title: String, note: String?) {
    Row(
        Modifier.fillMaxWidth().padding(start = ThemeSpec.Safe.horizontal, end = ThemeSpec.Safe.horizontal, top = ThemeSpec.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(ThemeSpec.Space.cozy, ThemeSpec.Space.xl).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)).background(ThemeSemantics.accent.copy(alpha = 0.55f)))
        Spacer(Modifier.width(ThemeSpec.Space.sm))
        Text(title, fontSize = ThemeSpec.Type.label, fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.Bold, color = ThemeSemantics.textSecondary, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1)
        note?.let {
            Spacer(Modifier.weight(1f))
            Text(it, fontSize = ThemeSpec.Type.micro, fontFamily = ThemeSpec.Font.broadcast, color = ThemeSemantics.textTertiary, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1)
        }
    }
}



/** The one option a Continue watching card has: leave the shelf. MENU and hold-OK both land here. */
private fun removeMenu(title: String, remove: () -> Unit): () -> Unit =
    { TvDialogs.menu(title, listOf("Remove from Continue watching"), onPick = { i -> if (i == 0) remove() }) }

/* Per-row limits: a shelf is a browse past the catalogue, not the catalogue. */
private const val CARDS_PER_SHELF = 16
private const val GAMES_PER_ROW = 12
/** Provider catalogue rows on Home; the rest stay on the Movies page. */
private const val CATALOG_ROWS = 3
/** Channels the "Live TV" shelf shows and reads now-playing listings for. */
internal const val LIVE_SHELF_CHANNELS = 30

/**
 * The shelves Home shows, built from whatever has loaded: Up Next (what you were watching, here and in other
 * apps), Live Sports and what is Upcoming, Live TV, YouTube Trending, Streaming Apps, the streaming catalogue,
 * Recommended, then Apps. A shelf with no data is left out rather than shown empty.
 */
internal fun buildHomeShelves(
    ctx: Context,
    recents: List<AppPreferences.RecentEntry>,
    watchNext: List<WatchNextItem>,
    scores: Map<String, List<EspnScoreboardClient.Score>>,
    bigGames: List<BigEvent>,
    channels: List<XtreamClient.LiveStream>,
    onNow: Map<Long, NowOn>,
    vodCategories: List<XtreamClient.Category>,
    movies: List<XtreamClient.VodStream>,
    cine: List<CatalogRow>,
    apps: List<LauncherApp>,
    cwArt: Map<String, String> = emptyMap(),
    ytTrending: List<YouTubeApiClient.YouTubeVideo> = emptyList(),
    recommended: List<StremioClient.Metum> = emptyList(),
    glance: List<ShelfEntry.Info> = emptyList(),
    onPlay: (url: String, title: String, refId: String) -> Unit,
    onCine: (StremioClient.Metum) -> Unit,
    // A card changed something the shelves are built from (a removal, a hidden app).
    onChanged: () -> Unit,
    onAppMenu: (HomeMenuSpec) -> Unit,
    onAppPin: (HomePinSpec) -> Unit,
): List<Shelf> {
    val client = ProviderSession.current
    val nowMs = System.currentTimeMillis()

    /* ── Live Sports: today's games, live first, then what is upcoming ── */
    fun gameEntry(league: String, s: EspnScoreboardClient.Score): ShelfEntry.Game {
        val status = stateLabel(s, nowMs)
        return ShelfEntry.Game(
            key = "$league:${s.name}",
            title = "${s.nameB ?: s.abbrB ?: "?"} at ${s.nameA ?: s.abbrA ?: "?"}",
            onClick = { SectionNav.go(AppSection.SPORTS) },
            game = ShelfGame(
                s.abbrB ?: "?", s.abbrA ?: "?", s.logoB, s.logoA,
                if (s.state == "in") "${s.scoreB ?: "0"}\u2013${s.scoreA ?: "0"}" else clockOf(s.startMs),
                s.colorB, s.altColorB, s.colorA, s.altColorA,
                live = s.state == "in", status = status, network = s.network,
            ),
            note = status.ifBlank { null },
        )
    }
    val today = scores.flatMap { (league, list) -> list.map { league to it } }
        .filter { (_, s) -> s.state == "in" || s.state == "pre" }
    // The games worth interrupting the hero for lead the carousel; the rest of the day's follow in feed order.
    val live = (bigGames.filter { it.live }.map { it.league to it.game } + today.filter { (_, s) -> s.state == "in" })
        .distinctBy { (league, s) -> "$league:${s.name}" }
        .take(GAMES_PER_ROW)
        .map { (league, s) -> gameEntry(league, s) }
    val comingUp = today.filter { (_, s) -> s.state == "pre" }
        .sortedBy { (_, s) -> s.startMs }
        .take(GAMES_PER_ROW)
        .map { (league, s) -> gameEntry(league, s) }

    val liveNow = client?.let { c ->
        channels.take(LIVE_SHELF_CHANNELS).mapNotNull { s ->
            val nowPlaying = onNow[s.streamId]?.takeIf { it.title.isNotBlank() } ?: return@mapNotNull null
            ShelfEntry.Channel(
                key = "live:${s.streamId}", title = nowPlaying.title,
                onClick = { onPlay(c.liveUrl(s.streamId), s.name, "live:${s.streamId}") },
                stream = s, onNow = nowPlaying,
            )
        }
    }.orEmpty()

    /* ── Continue watching: the player's own resume points, then what other apps put in Watch Next ── */
    fun norm(t: String) = titleKey(t)
    // One card per show, as the streaming apps have it: the episode played last stands for its show.
    val resumed = resumeRecents(recents, CARDS_PER_SHELF)
    val seenTitles = resumed.flatMap { listOf(norm(it.title), norm(splitTitle(it.title).name)) }.toSet()
    val hidden = AppPreferences.hiddenWatchNext()
    val next = watchNext
        .filter { norm(it.title) !in seenTitles }
        .map { w -> w to "${w.packageName}:${w.title}" }
        .filter { (_, wnKey) -> wnKey !in hidden }
        .distinctBy { (_, wnKey) -> norm(wnKey) }
        .take(CARDS_PER_SHELF)
    val watching = resumed.map { e ->
        // The card names the show; where the episode sits goes on the line beneath, as "S1, E2".
        val parts = splitTitle(e.title)
        ShelfEntry.Card(
            key = "r:${e.ref}", title = parts.name,
            art = e.poster.ifBlank { null } ?: cwArt[GuideMetadata.cwArtKey(e.title)],
            subtitle = resumeLine(parts, e.positionMs, e.durationMs),
            progress = if (e.durationMs > 0) (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f) else 0f,
            menu = removeMenu(parts.name) { AppPreferences.removeRecentByRef(e.ref); onChanged() },
            onClick = { if (e.url.isNotBlank()) onPlay(e.url, e.title, e.ref) },
        )
    } + next.map { (w, wnKey) ->
        // Rows from other apps can't be deleted, so the card is hidden locally instead.
        ShelfEntry.Card(
            key = "w:$wnKey", title = w.title, art = w.posterUri, subtitle = watchNextLine(w.watchNextType), progress = w.progress,
            menu = removeMenu(w.title) { AppPreferences.hideWatchNext(wnKey); onChanged() },
            onClick = { WatchNextRepository.launch(ctx, w) },
        )
    }

    val ytShelf = ytTrending.take(CARDS_PER_SHELF).map { v ->
        ShelfEntry.Card(
            key = "yt:${v.videoId}",
            title = v.title,
            art = v.thumb,
            subtitle = v.channel,
            onClick = { onPlay("https://www.youtube.com/watch?v=${v.videoId}", v.title, "yt:${v.videoId}") },
        )
    }

    val hiddenApps = AppPreferences.hiddenApps()
    val pins = AppPreferences.appPins()
    val launchCounts = AppPreferences.launchCounts()
    val serviceShortcuts = streamingApps(apps)
        .take(12)
        .map { app ->
            val locked = app.packageName in pins
            val launch: () -> Unit = {
                AppPreferences.bumpLaunchCount(app.packageName)
                AppsRepository.launchApp(ctx, app.packageName)
            }
            ShelfEntry.App(
                key = "svc:${app.packageName}", title = app.label,
                onClick = { if (locked) onAppPin(HomePinSpec.Unlock(app, launch)) else launch() },
                app = app, locked = locked,
                menu = { onAppMenu(appMenuSpec(ctx, app, locked, launch, onAppPin) { onChanged() }) },
            )
        }

    fun vodRow(name: String, list: List<XtreamClient.VodStream>): Shelf = Shelf(
        "vod:$name", name, ShelfFace.CATALOG,
        list.take(CARDS_PER_SHELF).map { m ->
            ShelfEntry.Card(
                key = "m:${m.streamId}", title = m.name, art = m.icon,
                subtitle = m.rating?.toFloatOrNull()?.let { "Rated %.1f".format(it) },
                onClick = { client?.let { c -> onPlay(c.movieUrl(m.streamId, m.ext), m.name, "movie:${m.streamId}") } },
            )
        },
    )

    val streamingRows = client?.let { c ->
        val byCategory = movies.groupBy { it.categoryId.orEmpty() }
        val named = vodCategories.filter { !byCategory[it.category_id].isNullOrEmpty() }
        val preferred = listOf("new" to "New", "series" to "Series", "kid" to "Kids")
        val picked = mutableListOf<Shelf>()
        val used = mutableSetOf<String>()
        preferred.forEach { (hint, label) ->
            val cat = named.firstOrNull { (it.category_name ?: "").lowercase().contains(hint) && it.category_id !in used }
            if (cat != null) {
                used += cat.category_id
                picked += vodRow(label, byCategory[cat.category_id].orEmpty())
            }
        }
        val remaining = named.filter { it.category_id !in used }.take((CATALOG_ROWS - picked.size).coerceAtLeast(0))
        remaining.forEach { cat -> picked += vodRow(cat.category_name.ifBlank { "Movies" }, byCategory[cat.category_id].orEmpty()) }
        if (picked.isEmpty() && movies.isNotEmpty()) listOf(vodRow("Movies", movies)) else picked
    } ?: run {
        val rows = cine.filter { it.items.isNotEmpty() }
        if (rows.size > 1) {
            rows.map { row ->
                Shelf("cine:${row.title}", row.title, ShelfFace.CATALOG, row.items.take(CARDS_PER_SHELF).map { m ->
                    ShelfEntry.Card(
                        key = "c:${m.id}", title = m.name, art = m.background ?: m.poster,
                        subtitle = listOfNotNull(m.releaseInfo?.take(4), m.imdbRating).joinToString(" \u00b7 ").ifBlank { null },
                        onClick = { onCine(m) },
                    )
                })
            }
        } else {
            listOfNotNull(rows.firstOrNull()?.let { row ->
                Shelf("popular", "Popular", ShelfFace.CATALOG, row.items.take(24).map { m ->
                    ShelfEntry.Card(key = "c:${m.id}", title = m.name, art = m.background ?: m.poster, onClick = { onCine(m) })
                })
            })
        }
    }

    /* ── Recommended: nothing a catalogue row above already shows, however it is spelled there ── */
    val catalogueTitles = streamingRows.flatMap { it.entries }.map { it.title }
    val recommendedShelf = unseenTitles(recommended, catalogueTitles) { it.name }.take(CARDS_PER_SHELF).map { m ->
        ShelfEntry.Card(
            key = "rec:${m.id}", title = m.name, art = m.poster ?: m.background,
            subtitle = listOfNotNull(m.releaseInfo?.take(4), m.imdbRating).joinToString(" \u00b7 ").ifBlank { null },
            onClick = { onCine(m) },
        )
    }

    /* ── Apps: most used first, with the same options as the Apps page. A service with a card on the Streaming
       Apps row above stands there once, not again here. ── */
    val servicePackages = serviceShortcuts.map { it.app.packageName }.toSet()
    val appShelf = apps
        .filter { it.packageName !in hiddenApps && it.packageName !in servicePackages }
        .sortedByDescending { launchCounts[it.packageName] ?: 0 }
        .take(CARDS_PER_SHELF)
        .map { app ->
            val locked = app.packageName in pins
            val launch: () -> Unit = {
                AppPreferences.bumpLaunchCount(app.packageName)
                AppsRepository.launchApp(ctx, app.packageName)
            }
            ShelfEntry.App(
                key = "app:${app.packageName}", title = app.label,
                onClick = { if (locked) onAppPin(HomePinSpec.Unlock(app, launch)) else launch() },
                app = app, locked = locked,
                menu = { onAppMenu(appMenuSpec(ctx, app, locked, launch, onAppPin) { onChanged() }) },
            )
        }

    return buildList<Shelf> {
        // The continuation shelf stays labelled: its position and purpose remain clear as the carousel changes.
        if (watching.isNotEmpty()) add(Shelf("continue", "Continue Watching", ShelfFace.CARD, watching))
        if (glance.isNotEmpty()) add(Shelf("glance", "Today", ShelfFace.INFO, glance))
        if (live.isNotEmpty()) add(Shelf("sports", "Live Sports", ShelfFace.LIVE_GAME, live, note = "${live.size} live"))
        if (comingUp.isNotEmpty()) add(Shelf("sports:upcoming", "Upcoming", ShelfFace.UPCOMING, comingUp, subheading = live.isNotEmpty()))
        if (liveNow.isNotEmpty()) add(Shelf("live", "Live TV", ShelfFace.CHANNEL, liveNow, note = "${channels.size} channels"))
        if (ytShelf.isNotEmpty()) add(Shelf("yt-trending", "YouTube Trending", ShelfFace.CARD, ytShelf))
        if (serviceShortcuts.isNotEmpty()) add(Shelf("streaming-services", "Streaming Apps", ShelfFace.SERVICE, serviceShortcuts))
        streamingRows.forEach { add(it) }
        if (recommendedShelf.isNotEmpty()) add(Shelf("recommended", "In Cinemas Now", ShelfFace.CARD, recommendedShelf))
        if (appShelf.isNotEmpty()) add(Shelf("apps", "Apps", ShelfFace.APP, appShelf))
    }.distinctBy { it.key }
}
