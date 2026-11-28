package com.carteltv.tv.ui.sports
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.carteltv.tv.alert.KickoffAlertScheduler
import com.carteltv.tv.alert.LiveScoreMonitor
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.espn.EspnScoreboardClient
import com.carteltv.tv.data.espn.LeagueCatalog
import com.carteltv.tv.data.espn.ScoreboardModel
import com.carteltv.tv.data.espn.ScoreboardRepository
import com.carteltv.tv.data.espn.SportsTimeZone
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.sportsguide.SportsGuideFeedClient
import com.carteltv.tv.ui.components.AutoSizeText
import com.carteltv.tv.ui.components.Chip
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.GameCardSkeleton
import com.carteltv.tv.ui.components.PageError
import com.carteltv.tv.ui.components.RailFocusLine
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.StagedScreen
import com.carteltv.tv.ui.components.TABULAR
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.components.railEdgeFade
import com.carteltv.tv.ui.components.railWrapItem
import com.carteltv.tv.ui.components.railWrapToStart
import com.carteltv.tv.ui.components.rememberAppStarted
import com.carteltv.tv.ui.components.rememberClock
import com.carteltv.tv.ui.components.rememberRailWrapState
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.nav.NetworkBadge
import com.carteltv.tv.ui.nav.PageChipRow
import com.carteltv.tv.ui.nav.PagePadding
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.theme.LiveBadge
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
/*
 * Sports: league chips, a day strip, then rows of game cards.
 * OK on a card follows it (goal alerts); followed games carry a star.
 */

private typealias Game = Pair<String, EspnScoreboardClient.Score>

/** One scoreboard pull: the day's games by league, and whether "nothing" came from the network rather than the calendar. */
private class Pull(
    val byLeague: Map<String, List<EspnScoreboardClient.Score>>,
    val failed: Boolean,
)

// Days and kickoff times use the sports zone (from the viewer's IP, or the one they chose), not the TV's own
// setting, which is often wrong. Reading SportsTimeZone.live in a composable recomposes it when the zone changes.
private fun dayLabel(offset: Int): String =
    com.carteltv.tv.data.espn.EspnDateMapper
        .label(offset, com.carteltv.tv.data.espn.SportsTimeZone.live, System.currentTimeMillis())

/** Rail width for a score card: wide enough that both team rows keep logo, record and tabular score on one line. */
private val GameCardW = 360.dp

@Composable
fun ScoresScreen() {
    val ctx = LocalContext.current
    var day by remember { mutableIntStateOf(0) }
    var league by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf<Map<String, List<EspnScoreboardClient.Score>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    // A pull that could not reach the feeds. Kept apart from an empty day, which is not an error.
    var loadFailed by remember { mutableStateOf(false) }
    var retryTick by remember { mutableIntStateOf(0) }
    var followTick by remember { mutableIntStateOf(0) }
    var leaguesTick by remember { mutableIntStateOf(0) }
    var shielded by remember { mutableStateOf(AppPreferences.spoilerShield) }
    var openGame by remember { mutableStateOf<Game?>(null) }
    var managingLeagues by remember { mutableStateOf(false) }
    var races by remember { mutableStateOf<List<com.carteltv.tv.data.espn.RacingClient.Race>>(emptyList()) }
    var guideBySlug by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    val started = rememberAppStarted()
    val zone = com.carteltv.tv.data.espn.SportsTimeZone.live
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        // Look up the viewer's zone from their IP (cached for half a day), then apply it.
        suspendRunCatching {
            com.carteltv.tv.data.espn.SportsTimeZone
                .refreshIfStale()
        }
        com.carteltv.tv.data.espn.SportsTimeZone
            .reload()
    }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        val feed = SportsGuideFeedClient.cached() ?: return@LaunchedEffect
        guideBySlug =
            feed.leagues
                .associate { it.slug.lowercase() to it.rightsCurated.filter(String::isNotBlank) }
                .filterValues { it.isNotEmpty() }
    }
    LaunchedEffect(day, leaguesTick, started, zone.id, retryTick) {
        if (!started) return@LaunchedEffect

        suspend fun pull(
            maxAgeMs: Long,
            onLeague: ((String, List<EspnScoreboardClient.Score>) -> Unit)? = null,
        ): Pull =
            coroutineScope {
                // ESPN files games under the US Eastern date, so a local day can touch two of its dates: fetch each,
                // then keep only the games that really start inside this day in the viewer's zone.
                val now = System.currentTimeMillis()
                val window =
                    com.carteltv.tv.data.espn.EspnDateMapper
                        .window(zone, day, now)
                val dates =
                    com.carteltv.tv.data.espn.EspnDateMapper
                        .espnDates(window)
                val feeds = LeagueCatalog.enabled(AppPreferences.activeLeagues)
                // A feed that cannot be reached hands back an empty board exactly like a day with nothing on, so an empty
                // pull is only a failure when a feed, asked directly, cannot be read either. That check runs alongside the
                // boards when the link is already known to be down (no extra wait), and otherwise only when the pull is empty.
                val probe =
                    async(start = if (com.carteltv.tv.data.network.Connectivity.isOnline) CoroutineStart.LAZY else CoroutineStart.DEFAULT) {
                        suspendRunCatching {
                            ScoreboardRepository.source.scoreboardOrNull(
                                feeds.first().second,
                                dates.first(),
                            )
                        }.getOrNull() ==
                            null
                    }
                val byLeague =
                    feeds
                        .map { (label, path) ->
                            async {
                                val boards =
                                    dates
                                        .map { d ->
                                            async { ScoreboardRepository.scoreboard(path, date = d, maxAgeMs = maxAgeMs) }
                                        }.awaitAll()
                                val games =
                                    com.carteltv.tv.data.espn.EspnDateMapper.merge(boards).filter {
                                        com.carteltv.tv.data.espn.EspnDateMapper
                                            .belongs(it, window, now)
                                    }
                                onLeague?.invoke(label, games)
                                label to games
                            }
                        }.awaitAll()
                        .toMap()
                val failed = byLeague.values.all { it.isEmpty() } && probe.await()
                Pull(byLeague, failed)
            }
        loading = true
        // Last-known boards paint straight away; the pull below replaces them within moments.
        if (data.isEmpty()) {
            val now = System.currentTimeMillis()
            val window =
                com.carteltv.tv.data.espn.EspnDateMapper
                    .window(zone, day, now)
            val dates =
                com.carteltv.tv.data.espn.EspnDateMapper
                    .espnDates(window)
            val seen =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    LeagueCatalog.enabled(AppPreferences.activeLeagues).associate { (label, path) ->
                        val boards = dates.mapNotNull { ScoreboardRepository.lastKnown(path, it) }
                        label to
                            com.carteltv.tv.data.espn.EspnDateMapper
                                .merge(
                                    boards,
                                ).filter {
                                    com.carteltv.tv.data.espn.EspnDateMapper
                                        .belongs(it, window, now)
                                }
                    }
                }
            if (seen.values.any { it.isNotEmpty() }) {
                data = seen
                loading = false
            }
        }
        // A pull that throws is a failure too; it used to take the whole poll down with it.
        // Leagues appear as they arrive instead of the screen waiting on the slowest feed.
        val partial = LinkedHashMap<String, List<EspnScoreboardClient.Score>>()
        val first =
            suspendRunCatching {
                pull(45_000L) { label, games ->
                    partial[label] = games
                    if (games.isNotEmpty()) {
                        data = LinkedHashMap(partial)
                        loading = false
                    }
                }
            }.getOrNull()
        loading = false
        if (first == null) {
            loadFailed = true
        } else {
            loadFailed = first.failed
            data = first.byLeague
            if (day == 0) data.forEach { (lg, list) -> KickoffAlertScheduler.autoArm(ctx, lg, list) }
        }
        while (true) {
            // Every 15 s while a game is live, rarely when nothing is on. A failed fetch keeps the last scores.
            delay(
                com.carteltv.tv.data.espn.ScoreRefreshPolicy
                    .nextDelayMs(data.values.flatten(), System.currentTimeMillis()),
            )
            suspendRunCatching { pull(com.carteltv.tv.data.espn.ScoreRefreshPolicy.SHARE_MS) }.getOrNull()?.let {
                data = it.byLeague
                loadFailed = it.failed
            }
        }
    }
    LaunchedEffect(leaguesTick) {
        val fav = AppPreferences.sportsFavoriteLeague
        if (league == null && fav.isNotBlank() && LeagueCatalog.enabled(AppPreferences.activeLeagues).any { it.first == fav }) league = fav
    }
    LaunchedEffect(day, started) {
        if (!started) return@LaunchedEffect
        // Race weekends share the game window; the series list is tiny, so one fetch per date is cheap.
        val now = System.currentTimeMillis()
        val window =
            com.carteltv.tv.data.espn.EspnDateMapper
                .window(zone, day, now)
        val inWindow = { startMs: Long, state: String ->
            startMs > 0 && startMs in window.startMs until window.endMs || state == "in"
        }
        val field =
            coroutineScope {
                com.carteltv.tv.data.espn.RacingClient.SERIES
                    .map { (label, path) ->
                        async {
                            suspendRunCatching {
                                com.carteltv.tv.data.espn.RacingClient.cached(
                                    label,
                                    path,
                                    maxAgeMs = 600_000L,
                                )
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll()
                    .flatten()
                    .filter { inWindow(it.startMs, it.state) }
            }
        val tennis =
            coroutineScope {
                com.carteltv.tv.data.espn.TennisScoreboardClient.SERIES
                    .map { (label, path) ->
                        async {
                            suspendRunCatching {
                                com.carteltv.tv.data.espn.TennisScoreboardClient.cached(
                                    label,
                                    path,
                                    maxAgeMs = 600_000L,
                                )
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll()
                    .flatten()
                    .filter { inWindow(it.startMs, it.state) }
                    .map {
                        com.carteltv.tv.data.espn.RacingClient.Race(
                            it.name,
                            it.tournament.ifBlank { it.tour },
                            null,
                            it.startMs,
                            it.state,
                            it.detail,
                        )
                    }
            }
        // The feeds take no date, so the same event can arrive twice; lazy keys must be unique.
        races = (field + tennis).distinctBy { "${it.name}_${it.startMs}" }.sortedBy { it.startMs }
    }

    val crewTick = KickoffAlertScheduler.version
    val catalogOrder = remember { LeagueCatalog.ALL_LEAGUES.mapIndexed { i, l -> l.first to i }.toMap() }
    // Feeds land in whatever order they answer, so the chips and the boards follow the catalogue's order instead.
    val ordered = data.entries.sortedBy { catalogOrder[it.key] ?: Int.MAX_VALUE }.associate { it.key to it.value }
    val games: List<Game> = ordered.flatMap { (lg, list) -> list.map { lg to it } }
    val leagues = if (AppPreferences.sportsHideEmptyLeagues) ordered.filterValues { it.isNotEmpty() } else ordered
    val shown = games.filter { league == null || it.first == league }
    val mine = remember(crewTick, shown, followTick) { shown.filter { KickoffAlertScheduler.involvesCrew(ctx, it.first, it.second) } }
    val live = shown.filter { it.second.state == "in" }
    val upcoming = shown.filter { it.second.state == "pre" }.sortedBy { it.second.startMs }
    val finished = shown.filter { it.second.state == "post" }
    val liveCount = games.count { it.second.state == "in" }
    // The marquee: the top live game, else the next to start. A broadcast-style spotlight above the rails,
    // the way a network leads with its featured match and a bookmaker pins its headline event.
    val featured: Game? = shown.firstOrNull { it.second.state == "in" } ?: upcoming.firstOrNull()
    val sections =
        listOf(
            "Your teams" to mine,
            "Live now" to live,
            (if (day == 0) "Later today" else dayLabel(day)) to upcoming,
            "Finished" to finished,
        )
    val firstSection = sections.indexOfFirst { it.second.isNotEmpty() }
    // Section rails recycle as the page scrolls, so each row keeps its scroll place in here. Keyed on the filter:
    // a new league or day is new content, and its rails should start from their first card, not where the old sat.
    val rowStates = remember(league, day) { mutableMapOf<String, LazyListState>() }

    ScreenPage {
        // The frame owns the backdrop; the staged section only tints it.
        StagedScreen(section = com.carteltv.tv.nav.AppSection.SPORTS) {
            PageChipRow {
                item { SportChip("All", league == null, null) { league = null } }
                items(leagues.keys.toList(), key = { it }) { lg ->
                    SportChip(lg, league == lg, leagues[lg]?.firstOrNull()?.leagueLogo) { league = lg }
                }
                item { SportChip("Leagues", false, null) { managingLeagues = true } }
                if (races.isNotEmpty()) {
                    item {
                        SportChip("Golf, racing and tennis", league == "Field", null) {
                            league =
                                if (league == "Field") null else "Field"
                        }
                    }
                }
            }
            PageChipRow {
                // The live count leads the strip with a chip's own height, so it sits level with the day chips.
                item {
                    Row(Modifier.padding(vertical = ThemeSpec.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape).background(
                                if (liveCount >
                                    0
                                ) {
                                    ThemeSemantics.live
                                } else {
                                    ThemeSemantics.textTertiary
                                },
                            ),
                        )
                        Spacer(Modifier.width(ThemeSpec.Space.sm))
                        Text(
                            androidx.compose.ui.res
                                .stringResource(com.carteltv.tv.R.string.live_action, liveCount),
                            fontSize = ThemeSpec.Type.meta,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = ThemeSemantics.textSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                items((-1..6).toList(), key = { it }) { off -> SportChip(dayLabel(off), day == off, null) { day = off } }
                item {
                    SportChip(if (shielded) "Spoiler shield on" else "Spoiler shield", shielded, null) {
                        shielded = !shielded
                        AppPreferences.spoilerShield = shielded
                    }
                }
            }
            // A section is one rail now: its cards scroll off sideways instead of stacking in columns, and every rail
            // parks its focused card on the same line, the way a streaming page keeps its sliders aligned as you browse
            // down it. Race rows and empty states still run the page's full width.
            RailFocusLine {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().restoreRowFocus(),
                    contentPadding = PagePadding,
                    verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                ) {
                    // The marquee scorebug: one featured game blown up to broadcast scale above the rails. It owns the
                    // screen's entry focus while it is here; with no featured game the first rail's first card takes it.
                    featured?.let { (flg, fg) ->
                        item(key = "featured_$flg:${fg.name}", contentType = "featured") {
                            val followed = remember(followTick, flg, fg.name) { LiveScoreMonitor.isFollowed(ctx, flg, fg.name) }
                            val rights =
                                remember(guideBySlug, flg) {
                                    val slug =
                                        LeagueCatalog.ALL_LEAGUES
                                            .firstOrNull { it.first.equals(flg, ignoreCase = true) }
                                            ?.second
                                            ?.substringAfterLast('/')
                                            ?.lowercase()
                                            .orEmpty()
                                    guideBySlug[slug].orEmpty()
                                }
                            FeaturedGame(
                                league = flg,
                                g = fg,
                                followed = followed,
                                shielded = shielded,
                                rights = rights,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .entryFocus(key = Pair(day, league)),
                            ) { openGame = flg to fg }
                        }
                    }
                    sections.forEachIndexed { si, (title, list) ->
                        if (list.isEmpty()) return@forEachIndexed
                        item(key = "sec_$title", contentType = "section") {
                            // A section recycled off screen keeps its scroll position through this map, so scrolling back up continues where you left.
                            val rowState = rowStates.getOrPut(title) { LazyListState() }
                            val games = list.distinctBy { "${it.first}:${it.second.name}" }
                            val wrapState = rememberRailWrapState()
                            val wrapFirst = remember { FocusRequester() }
                            Column(Modifier.fillMaxWidth()) {
                                // A count rides the section head the way a network's "Live now · 3" does, so the
                                // rail's weight is legible before a single card is read.
                                SectionTitle(
                                    title,
                                    Modifier.fillMaxWidth().padding(top = ThemeSpec.Space.xs),
                                    note = if (list.size > 1) "${list.size}" else null,
                                )
                                LazyRow(
                                    state = rowState,
                                    modifier =
                                        Modifier
                                            .restoreRowFocus()
                                            .railEdgeFade(rowState)
                                            .railWrapToStart(wrapState, rowState, games.size, wrapFirst),
                                    // Head room so a lifted card's ring is never clipped by the row's own bounds.
                                    contentPadding = PaddingValues(vertical = ThemeSpec.Space.sm),
                                    horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                                ) {
                                    // Lazy keys must be unique, so a repeated game collapses to its first entry; the section prefix
                                    // keeps a game that sits in both "Your Crew" and its state section unique, and the key stays
                                    // stable when scores reorder.
                                    itemsIndexed(
                                        games,
                                        key = { _, g -> "game_${title}_${g.first}:${g.second.name}" },
                                        contentType = { _, _ -> "game" },
                                    ) { i, (lg, g) ->
                                        val followed = remember(followTick, lg, g.name) { LiveScoreMonitor.isFollowed(ctx, lg, g.name) }
                                        val rights =
                                            remember(guideBySlug, lg) {
                                                val slug =
                                                    LeagueCatalog.ALL_LEAGUES
                                                        .firstOrNull { it.first.equals(lg, ignoreCase = true) }
                                                        ?.second
                                                        ?.substringAfterLast('/')
                                                        ?.lowercase()
                                                        .orEmpty()
                                                guideBySlug[slug].orEmpty()
                                            }
                                        GameCard(
                                            league = lg,
                                            g = g,
                                            followed = followed,
                                            shielded = shielded,
                                            rights = rights,
                                            modifier =
                                                Modifier
                                                    .railWrapItem(wrapState, i)
                                                    .then(
                                                        if (i == 0) {
                                                            Modifier
                                                                .focusRequester(wrapFirst)
                                                                .then(
                                                                    if (si == firstSection && featured == null) {
                                                                        Modifier.entryFocus(key = Pair(day, league))
                                                                    } else {
                                                                        Modifier
                                                                    },
                                                                )
                                                        } else {
                                                            Modifier
                                                        },
                                                    ),
                                        ) { openGame = lg to g }
                                    }
                                }
                            }
                        }
                    }
                    // Field events & matches (F1 / NASCAR / IndyCar / PGA / ATP / WTA): schedule rows, not scorebugs.
                    val showRaces = races.isNotEmpty() && (league == null || league == "Field")
                    if (showRaces) {
                        item(key = "head_racing", contentType = "head") {
                            SectionTitle("Racing", Modifier.fillMaxWidth().padding(top = ThemeSpec.Space.xs))
                        }
                        items(
                            races,
                            key = { "race_${it.name}_${it.startMs}" },
                            contentType = { "race" },
                        ) { r ->
                            RaceRow(r, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (shown.isEmpty() && !showRaces) {
                        item(key = "empty", contentType = "empty") {
                            when {
                                // A pull that failed gets its own line and a way to try again; "no games" is kept for a day that really is empty.
                                loading -> {
                                    Row(
                                        Modifier.padding(top = ThemeSpec.Space.sm),
                                        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                                    ) {
                                        repeat(3) { GameCardSkeleton(GameCardW) }
                                    }
                                }

                                loadFailed -> {
                                    PageError("Couldn't load scores. Check the connection, then try again.", onRetry = { retryTick++ })
                                }

                                else -> {
                                    EmptyState(
                                        androidx.compose.ui.res
                                            .stringResource(com.carteltv.tv.R.string.scores_no_games),
                                        hint =
                                            androidx.compose.ui.res
                                                .stringResource(com.carteltv.tv.R.string.scores_no_games_hint),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    openGame?.let { (lg, g) ->
        GameSheet(lg, g, onDismiss = { openGame = null }, onChanged = { followTick++ })
    }
    if (managingLeagues) {
        LeaguePicker(onDismiss = { managingLeagues = false }, onChanged = { leaguesTick++ })
    }
}

@Composable
internal fun SportChip(
    label: String,
    selected: Boolean,
    logo: String?,
    onClick: () -> Unit,
) = Chip(
    label,
    selected,
    leading =
        logo?.let { url ->
            { AsyncImage(model = url, contentDescription = "$label logo", modifier = Modifier.size(22.dp)) }
        },
    onClick = onClick,
)

@Composable
internal fun RaceRow(
    r: com.carteltv.tv.data.espn.RacingClient.Race,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val zone = SportsTimeZone.live
    val live = r.state == "in"
    val status =
        when (r.state) {
            "in" -> {
                r.detail.ifBlank { "LIVE" }
            }

            "post" -> {
                r.detail.ifBlank { "Final" }
            }

            else -> {
                if (r.startMs > 0) {
                    val until = r.startMs - System.currentTimeMillis()
                    "STARTS IN ${ScoreboardModel.countdown(until)}".uppercase(java.util.Locale.ROOT) +
                        " · " +
                        com.carteltv.tv.data.espn.EspnDateMapper
                            .clock(r.startMs, zone)
                } else {
                    "UPCOMING"
                }
            }
        }
    Row(
        modifier
            .heightIn(min = 44.dp)
            .cardSurface(focused, ThemeSpec.Focus.liftCompact)
            .focusable(interactionSource = interaction)
            .padding(horizontal = ThemeSpec.Space.cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) Box(Modifier.size(8.dp).clip(CircleShape).background(ThemeSemantics.live))
        Spacer(Modifier.width(if (live) ThemeSpec.Space.sm else 0.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.name,
                fontSize = ThemeSpec.Type.body,
                fontWeight = FontWeight.Bold,
                color = ThemeSemantics.textEmphatic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(r.series.ifBlank { null }, r.circuit).joinToString(" · "),
                fontSize = ThemeSpec.Type.label,
                color = ThemeSemantics.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(ThemeSpec.Space.cozy))
        Text(
            status,
            fontSize = ThemeSpec.Type.meta,
            fontWeight = FontWeight.SemiBold,
            color = if (live) ThemeSemantics.live else ThemeSemantics.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun FeaturedGame(
    league: String,
    g: EspnScoreboardClient.Score,
    followed: Boolean,
    shielded: Boolean,
    rights: List<String>,
    modifier: Modifier,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val zone = SportsTimeZone.live
    val now = rememberClock(1_000L)
    val bug = remember(g, shielded, zone.id, now) { ScoreboardModel.build(g, now.time, zone, shielded) }
    val live = g.state == "in"
    val final = g.state == "post"
    val awayPts = bug.away.score?.toIntOrNull()
    val homePts = bug.home.score?.toIntOrNull()
    val decided = final && !shielded && awayPts != null && homePts != null && awayPts != homePts
    val awayPlate = plateColors(g.colorB, g.altColorB, ThemeSemantics.cornerRed)
    val homePlate = plateColors(g.colorA, g.altColorA, ThemeSemantics.cornerBlue)
    Column(
        modifier
            .cardSurface(focused, ThemeSpec.Focus.lift)
            .clickableNoIndication(interaction, onToggle),
    ) {
        // The marquee, built like a modern network hero (Netflix GRIDIRON / Prime NBA): a team-colour "wing"
        // slides in from each edge carrying an oversized crest, and a dark info panel between them holds the
        // score, the clock and the league line. A live game burns the wings brighter so the eye lands first.
        Box(
            Modifier
                .fillMaxWidth()
                .height(FeaturedHeight)
                .background(ThemeSemantics.mediaInk),
        ) {
            Row(Modifier.fillMaxSize()) {
                MarqueeWing(
                    side = bug.away,
                    plate = awayPlate,
                    dim = decided && awayPts != null && homePts != null && awayPts < homePts,
                    alignEnd = false,
                    live = live,
                    modifier = Modifier.weight(1f),
                )
                // The centre info panel: league + LIVE up top, the marquee score (or kickoff) and status below.
                Column(
                    Modifier.weight(1.35f).fillMaxHeight().padding(horizontal = ThemeSpec.Space.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            league.uppercase(java.util.Locale.ROOT),
                            fontSize = ThemeSpec.Type.label,
                            fontFamily = ThemeSpec.Font.broadcast,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = ThemeSpec.Type.trackCaps,
                            color = ThemeSemantics.onMediaMuted,
                            maxLines = 1,
                        )
                        if (followed) {
                            Spacer(Modifier.width(ThemeSpec.Space.sm))
                            Text("\u2605", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.accentBright, maxLines = 1)
                        }
                        if (live) {
                            Spacer(Modifier.width(ThemeSpec.Space.md))
                            LiveBadge()
                        }
                    }
                    Spacer(Modifier.height(ThemeSpec.Space.sm))
                    if (g.state == "pre") {
                        Text(
                            bug.kickoff.ifBlank { bug.status },
                            fontSize = ThemeSpec.Type.titleL,
                            fontFamily = ThemeSpec.Font.mono,
                            fontWeight = FontWeight.Bold,
                            color = ThemeSemantics.onMedia,
                            maxLines = 1,
                        )
                        Text(
                            bug.status,
                            fontSize = ThemeSpec.Type.label,
                            fontFamily = ThemeSpec.Font.broadcast,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = ThemeSpec.Type.trackCaps,
                            color = ThemeSemantics.onMediaMuted,
                            maxLines = 1,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FeaturedScore(bug.away.score, lead = decided && awayPts != null && homePts != null && awayPts > homePts)
                            Text(
                                "\u2013",
                                fontSize = ThemeSpec.Type.title,
                                color = ThemeSemantics.onMediaMuted,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = ThemeSpec.Space.sm),
                            )
                            FeaturedScore(bug.home.score, lead = decided && homePts != null && awayPts != null && homePts > awayPts)
                        }
                        Text(
                            bug.status,
                            fontSize = ThemeSpec.Type.label,
                            fontFamily = ThemeSpec.Font.broadcast,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = ThemeSpec.Type.trackCaps,
                            color = if (live) ThemeSemantics.onMedia else ThemeSemantics.onMediaMuted,
                            maxLines = 1,
                        )
                    }
                }
                MarqueeWing(
                    side = bug.home,
                    plate = homePlate,
                    dim = decided && awayPts != null && homePts != null && homePts < awayPts,
                    alignEnd = true,
                    live = live,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // The foot line: venue on the left, the book line and broadcasters on the right, the way a lower-third reads.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bug.place?.let {
                Text(
                    it,
                    fontSize = ThemeSpec.Type.label,
                    color = ThemeSemantics.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(ThemeSpec.Space.sm))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    bug.odds?.let {
                        Text(
                            it,
                            fontSize = ThemeSpec.Type.label,
                            fontWeight = FontWeight.SemiBold,
                            color = ThemeSemantics.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(ThemeSpec.Space.md))
                    }
                    if (bug.networks.isNotEmpty()) {
                        bug.networks.take(3).forEach { NetworkBadge(it, 22.dp) }
                    } else if (rights.isNotEmpty()) {
                        Text(
                            rights.take(2).joinToString(" \u00b7 "),
                            fontSize = ThemeSpec.Type.label,
                            color = ThemeSemantics.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The featured band's height: room for an oversized crest, the score and the league line. */
private val FeaturedHeight = 150.dp

/**
 * One team's "wing" on the marquee: a colour field that is solid at the screen edge and dissolves toward the
 * centre panel, carrying an oversized crest in a disc and the abbreviation + record. The home wing mirrors the
 * away one so the two frame the score.
 */
@Composable
private fun MarqueeWing(
    side: ScoreboardModel.Side,
    plate: PlateColors,
    dim: Boolean,
    alignEnd: Boolean,
    live: Boolean,
    modifier: Modifier,
) {
    val strength = if (live) 0.95f else 0.7f
    val gradient =
        if (alignEnd) {
            Brush.horizontalGradient(0f to plate.outer.copy(alpha = 0f), 1f to plate.outer.copy(alpha = strength))
        } else {
            Brush.horizontalGradient(0f to plate.outer.copy(alpha = strength), 1f to plate.outer.copy(alpha = 0f))
        }
    val ink = if (dim) ThemeSemantics.onMediaMuted else ThemeSemantics.onMedia
    Box(
        modifier.fillMaxHeight().background(gradient),
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            Modifier.padding(horizontal = ThemeSpec.Space.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
        ) {
            val crest: @Composable () -> Unit = {
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(ThemeSemantics.mediaInk.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (side.logo != null) {
                        AsyncImage(
                            model = side.logo,
                            contentDescription = "${side.name} logo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(ThemeSpec.Space.md).graphicsLayer { alpha = if (dim) 0.55f else 1f },
                        )
                    } else {
                        Text(
                            side.abbr,
                            fontSize = ThemeSpec.Type.titleL,
                            fontFamily = ThemeSpec.Font.display,
                            fontWeight = FontWeight.Bold,
                            color = ink,
                            maxLines = 1,
                        )
                    }
                }
            }
            val copy: @Composable () -> Unit = {
                Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
                    Text(
                        side.abbr,
                        fontSize = ThemeSpec.Type.title,
                        fontFamily = ThemeSpec.Font.display,
                        fontWeight = if (dim) FontWeight.SemiBold else FontWeight.Bold,
                        color = ink,
                        maxLines = 1,
                    )
                    side.record?.let {
                        Text(it, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.onMediaMuted, maxLines = 1)
                    }
                }
            }
            if (alignEnd) {
                copy()
                crest()
            } else {
                crest()
                copy()
            }
        }
    }
}


/** A big tabular score on the marquee; the leading side reads in the win colour. */
@Composable
internal fun FeaturedScore(
    score: String?,
    lead: Boolean,
) {
    AutoSizeText(
        score ?: "\u2013",
        fontSize = ThemeSpec.Type.score,
        fontFamily = ThemeSpec.Font.mono,
        fontWeight = if (lead) FontWeight.Black else FontWeight.Bold,
        color = if (lead) ThemeSemantics.win else ThemeSemantics.onMedia,
        fontFeatureSettings = TABULAR,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 56.dp, max = 96.dp),
    )
}

@Composable
internal fun GameCard(
    league: String,
    g: EspnScoreboardClient.Score,
    followed: Boolean,
    shielded: Boolean,
    rights: List<String> = emptyList(),
    modifier: Modifier,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val zone = SportsTimeZone.live
    val now = rememberClock(60_000L)
    val bug = remember(g, shielded, zone.id, now) { ScoreboardModel.build(g, now.time, zone, shielded) }
    val live = g.state == "in"
    val final = g.state == "post"
    val awayPts = bug.away.score?.toIntOrNull()
    val homePts = bug.home.score?.toIntOrNull()
    val decided = final && !shielded && awayPts != null && homePts != null && awayPts != homePts
    val awayPlate = plateColors(g.colorB, g.altColorB, ThemeSemantics.cornerRed)
    val homePlate = plateColors(g.colorA, g.altColorA, ThemeSemantics.cornerBlue)
    Column(
        modifier
            .width(GameCardW)
            .cardSurface(focused, ThemeSpec.Focus.liftCompact)
            .clickableNoIndication(interaction, onToggle),
    ) {
        // Header strip: the two brand colours to their halves behind the league line, so a rail reads as a wall
        // of matchups. A live game runs the strip at full strength and carries the breathing LIVE tag.
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        0f to awayPlate.outer.copy(alpha = if (live) 0.9f else 0.5f),
                        0.5f to awayPlate.outer.copy(alpha = 0.25f),
                        0.5f to homePlate.outer.copy(alpha = 0.25f),
                        1f to homePlate.outer.copy(alpha = if (live) 0.9f else 0.5f),
                    ),
                ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    league.uppercase(java.util.Locale.ROOT),
                    fontSize = ThemeSpec.Type.label,
                    fontFamily = ThemeSpec.Font.broadcast,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = ThemeSpec.Type.trackCaps,
                    color = ThemeSemantics.onMedia,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (followed) {
                    Spacer(Modifier.width(ThemeSpec.Space.sm))
                    Text("\u2605", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.onMedia, maxLines = 1)
                }
                if (live) {
                    Spacer(Modifier.width(ThemeSpec.Space.md))
                    LiveBadge()
                }
            }
        }
        // The scorebug body: crests at each outer edge, the marquee score and status stacked in the middle.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScorebugSide(
                bug.away,
                plate = awayPlate,
                dim = decided && awayPts != null && homePts != null && awayPts < homePts,
                alignEnd = false,
            )
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (g.state == "pre") {
                    AutoSizeText(
                        bug.kickoff.ifBlank { "--:--" },
                        fontSize = ThemeSpec.Type.title,
                        fontFamily = ThemeSpec.Font.mono,
                        fontWeight = FontWeight.Bold,
                        color = ThemeSemantics.textEmphatic,
                        fontFeatureSettings = TABULAR,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 72.dp),
                    )
                    Text(
                        bug.status,
                        fontSize = ThemeSpec.Type.micro,
                        fontFamily = ThemeSpec.Font.broadcast,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = ThemeSpec.Type.trackCaps,
                        color = ThemeSemantics.textTertiary,
                        maxLines = 1,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ScorebugScore(bug.away.score, lead = decided && awayPts != null && homePts != null && awayPts > homePts)
                        Text(
                            "\u2013",
                            fontSize = ThemeSpec.Type.meta,
                            color = ThemeSemantics.textTertiary,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = ThemeSpec.Space.xs),
                        )
                        ScorebugScore(bug.home.score, lead = decided && homePts != null && awayPts != null && homePts > awayPts)
                    }
                    Text(
                        bug.status,
                        fontSize = ThemeSpec.Type.micro,
                        fontFamily = ThemeSpec.Font.broadcast,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = ThemeSpec.Type.trackCaps,
                        color = if (live) ThemeSemantics.live else ThemeSemantics.textTertiary,
                        maxLines = 1,
                    )
                }
            }
            ScorebugSide(
                bug.home,
                plate = homePlate,
                dim = decided && awayPts != null && homePts != null && homePts < awayPts,
                alignEnd = true,
            )
        }
        // Footer rule: venue on the left, the book line and broadcasters on the right, like a lower-third.
        Box(Modifier.fillMaxWidth().height(1.dp).background(ThemeSemantics.hairline))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bug.place?.let {
                Text(
                    it,
                    fontSize = ThemeSpec.Type.micro,
                    color = ThemeSemantics.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(ThemeSpec.Space.sm))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    bug.odds?.let {
                        Text(
                            it,
                            fontSize = ThemeSpec.Type.micro,
                            fontWeight = FontWeight.SemiBold,
                            color = ThemeSemantics.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(ThemeSpec.Space.sm))
                    }
                    if (bug.networks.isNotEmpty()) {
                        bug.networks.take(2).forEach { NetworkBadge(it, 20.dp) }
                    } else if (rights.isNotEmpty()) {
                        Text(
                            rights.take(2).joinToString(" \u00b7 "),
                            fontSize = ThemeSpec.Type.micro,
                            color = ThemeSemantics.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A big tabular score in the scorebug body; the leading side reads in the win colour. */
@Composable
private fun ScorebugScore(score: String?, lead: Boolean) {
    AutoSizeText(
        score ?: "\u2013",
        fontSize = ThemeSpec.Type.score,
        fontFamily = ThemeSpec.Font.mono,
        fontWeight = if (lead) FontWeight.Black else FontWeight.Bold,
        color = if (lead) ThemeSemantics.win else ThemeSemantics.textEmphatic,
        fontFeatureSettings = TABULAR,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 40.dp, max = 64.dp),
    )
}

/** One team at the edge of the scorebug: a crest in a disc of its colour, abbreviation and record beside it. */
@Composable
private fun ScorebugSide(
    side: ScoreboardModel.Side,
    plate: PlateColors,
    dim: Boolean,
    alignEnd: Boolean,
) {
    val ink = if (dim) ThemeSemantics.textSecondary else ThemeSemantics.textEmphatic
    val crest: @Composable () -> Unit = {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(plate.outer.copy(alpha = if (dim) 0.35f else 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            if (side.logo != null) {
                AsyncImage(
                    model = side.logo,
                    contentDescription = "${side.name} logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(ThemeSpec.Space.sm).graphicsLayer { alpha = if (dim) 0.6f else 1f },
                )
            } else {
                Text(
                    side.abbr.take(2),
                    fontSize = ThemeSpec.Type.label,
                    fontFamily = ThemeSpec.Font.display,
                    fontWeight = FontWeight.Bold,
                    color = ThemeSemantics.onMedia,
                    maxLines = 1,
                )
            }
        }
    }
    val copy: @Composable () -> Unit = {
        Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
            Text(
                side.abbr,
                fontSize = ThemeSpec.Type.cardTitle,
                fontFamily = ThemeSpec.Font.display,
                fontWeight = if (dim) FontWeight.SemiBold else FontWeight.Bold,
                color = ink,
                maxLines = 1,
            )
            side.record?.let {
                Text(it, fontSize = ThemeSpec.Type.micro, color = ThemeSemantics.textTertiary, maxLines = 1)
            }
        }
    }
    if (alignEnd) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
            copy()
            crest()
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
            crest()
            copy()
        }
    }
}
