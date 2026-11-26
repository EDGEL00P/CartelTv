package com.carteltv.tv.ui.sports
import com.carteltv.tv.data.espn.LeagueCatalog
import com.carteltv.tv.data.espn.ScoreboardRepository
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.core.suspendRunCatching

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import com.carteltv.tv.data.espn.ScoreboardModel
import com.carteltv.tv.data.espn.SportsTimeZone
import com.carteltv.tv.data.espn.VenueClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.widthIn
import com.carteltv.tv.ui.components.AutoSizeText
import com.carteltv.tv.ui.components.TABULAR
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.carteltv.tv.alert.LiveScoreMonitor
import com.carteltv.tv.alert.KickoffAlertScheduler
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.espn.EspnScoreboardClient
import com.carteltv.tv.data.sportsguide.SportsGuideFeedClient
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.LiveBadge

import androidx.compose.foundation.focusable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.time.ZoneId
import com.carteltv.tv.ui.components.Chip
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.GameCardSkeleton
import com.carteltv.tv.ui.components.PageError
import com.carteltv.tv.ui.components.RailFocusLine
import com.carteltv.tv.ui.components.railEdgeFade
import com.carteltv.tv.ui.components.railWrapItem
import com.carteltv.tv.ui.components.railWrapToStart
import com.carteltv.tv.ui.components.rememberRailWrapState
import com.carteltv.tv.ui.components.rememberAppStarted
import com.carteltv.tv.ui.components.rememberClock
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.StagedScreen
import com.carteltv.tv.ui.nav.NetworkBadge
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.nav.PageChipRow
import com.carteltv.tv.ui.nav.PagePadding
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.entryFocus
/*
 * Sports: league chips, a day strip, then rows of game cards.
 * OK on a card follows it (goal alerts); followed games carry a star.
 */

private typealias Game = Pair<String, EspnScoreboardClient.Score>

/** One scoreboard pull: the day's games by league, and whether "nothing" came from the network rather than the calendar. */
private class Pull(val byLeague: Map<String, List<EspnScoreboardClient.Score>>, val failed: Boolean)

// Days and kickoff times use the sports zone (from the viewer's IP, or the one they chose), not the TV's own
// setting, which is often wrong. Reading SportsTimeZone.live in a composable recomposes it when the zone changes.
private fun dayLabel(offset: Int): String =
    com.carteltv.tv.data.espn.EspnDateMapper.label(offset, com.carteltv.tv.data.espn.SportsTimeZone.live, System.currentTimeMillis())


/** Rail width for a score card: wide enough that both team rows keep logo, record and tabular score on one line. */
private val GameCardW = 300.dp

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
        suspendRunCatching { com.carteltv.tv.data.espn.SportsTimeZone.refreshIfStale() }
        com.carteltv.tv.data.espn.SportsTimeZone.reload()
    }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        val feed = SportsGuideFeedClient.cached() ?: return@LaunchedEffect
        guideBySlug = feed.leagues.associate { it.slug.lowercase() to it.rightsCurated.filter(String::isNotBlank) }
            .filterValues { it.isNotEmpty() }
    }
    LaunchedEffect(day, leaguesTick, started, zone.id, retryTick) {
        if (!started) return@LaunchedEffect
        suspend fun pull(maxAgeMs: Long, onLeague: ((String, List<EspnScoreboardClient.Score>) -> Unit)? = null): Pull = coroutineScope {
            // ESPN files games under the US Eastern date, so a local day can touch two of its dates: fetch each,
            // then keep only the games that really start inside this day in the viewer's zone.
            val now = System.currentTimeMillis()
            val window = com.carteltv.tv.data.espn.EspnDateMapper.window(zone, day, now)
            val dates = com.carteltv.tv.data.espn.EspnDateMapper.espnDates(window)
            val feeds = LeagueCatalog.enabled(AppPreferences.activeLeagues)
            // A feed that cannot be reached hands back an empty board exactly like a day with nothing on, so an empty
            // pull is only a failure when a feed, asked directly, cannot be read either. That check runs alongside the
            // boards when the link is already known to be down (no extra wait), and otherwise only when the pull is empty.
            val probe = async(start = if (com.carteltv.tv.data.network.Connectivity.isOnline) CoroutineStart.LAZY else CoroutineStart.DEFAULT) {
                suspendRunCatching { ScoreboardRepository.source.scoreboardOrNull(feeds.first().second, dates.first()) }.getOrNull() == null
            }
            val byLeague = feeds.map { (label, path) ->
                async {
                    val boards = dates.map { d -> async { ScoreboardRepository.scoreboard(path, date = d, maxAgeMs = maxAgeMs) } }.awaitAll()
                    val games = com.carteltv.tv.data.espn.EspnDateMapper.merge(boards).filter { com.carteltv.tv.data.espn.EspnDateMapper.belongs(it, window, now) }
                    onLeague?.invoke(label, games)
                    label to games
                }
            }.awaitAll().toMap()
            val failed = byLeague.values.all { it.isEmpty() } && probe.await()
            Pull(byLeague, failed)
        }
        loading = true
        // Last-known boards paint straight away; the pull below replaces them within moments.
        if (data.isEmpty()) {
            val now = System.currentTimeMillis()
            val window = com.carteltv.tv.data.espn.EspnDateMapper.window(zone, day, now)
            val dates = com.carteltv.tv.data.espn.EspnDateMapper.espnDates(window)
            val seen = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                LeagueCatalog.enabled(AppPreferences.activeLeagues).associate { (label, path) ->
                    val boards = dates.mapNotNull { ScoreboardRepository.lastKnown(path, it) }
                    label to com.carteltv.tv.data.espn.EspnDateMapper.merge(boards).filter { com.carteltv.tv.data.espn.EspnDateMapper.belongs(it, window, now) }
                }
            }
            if (seen.values.any { it.isNotEmpty() }) { data = seen; loading = false }
        }
        // A pull that throws is a failure too; it used to take the whole poll down with it.
        // Leagues appear as they arrive instead of the screen waiting on the slowest feed.
        val partial = LinkedHashMap<String, List<EspnScoreboardClient.Score>>()
        val first = suspendRunCatching {
            pull(45_000L) { label, games ->
                partial[label] = games
                if (games.isNotEmpty()) { data = LinkedHashMap(partial); loading = false }
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
            delay(com.carteltv.tv.data.espn.ScoreRefreshPolicy.nextDelayMs(data.values.flatten(), System.currentTimeMillis()))
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
        val window = com.carteltv.tv.data.espn.EspnDateMapper.window(zone, day, now)
        val inWindow = { startMs: Long, state: String ->
            startMs > 0 && startMs in window.startMs until window.endMs || state == "in"
        }
        val field = coroutineScope {
            com.carteltv.tv.data.espn.RacingClient.SERIES.map { (label, path) ->
                async { suspendRunCatching { com.carteltv.tv.data.espn.RacingClient.cached(label, path, maxAgeMs = 600_000L) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
                .filter { inWindow(it.startMs, it.state) }
        }
        val tennis = coroutineScope {
            com.carteltv.tv.data.espn.TennisScoreboardClient.SERIES.map { (label, path) ->
                async { suspendRunCatching { com.carteltv.tv.data.espn.TennisScoreboardClient.cached(label, path, maxAgeMs = 600_000L) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
                .filter { inWindow(it.startMs, it.state) }
                .map { com.carteltv.tv.data.espn.RacingClient.Race(it.name, it.tournament.ifBlank { it.tour }, null, it.startMs, it.state, it.detail) }
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
    val sections = listOf(
        "Your teams" to mine,
        "Live now" to live,
        (if (day == 0) "Later today" else dayLabel(day)) to upcoming,
        "Finished" to finished,
    )
    val firstSection = sections.indexOfFirst { it.second.isNotEmpty() }
    // Section rails recycle as the page scrolls, so each row keeps its scroll place in here. Keyed on the filter:
    // a new league or day is new content, and its rails should start from their first card, not where the old sat.
    val rowStates = remember(league, day) { mutableMapOf<String, LazyListState>() }

    ScreenPage(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.title_sports), icon = com.carteltv.tv.R.drawable.ph_basketball) {
    // The frame owns the backdrop; the staged section only tints it.
    StagedScreen(section = com.carteltv.tv.nav.AppSection.SPORTS) {
    PageChipRow {
        item { SportChip("All", league == null, null) { league = null } }
        items(leagues.keys.toList(), key = { it }) { lg ->
            SportChip(lg, league == lg, leagues[lg]?.firstOrNull()?.leagueLogo) { league = lg }
        }
        item { SportChip("Leagues", false, null) { managingLeagues = true } }
        if (races.isNotEmpty()) item { SportChip("Golf, racing and tennis", league == "Field", null) { league = if (league == "Field") null else "Field" } }
    }
    PageChipRow {
        // The live count leads the strip with a chip's own height, so it sits level with the day chips.
        item {
            Row(Modifier.padding(vertical = ThemeSpec.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (liveCount > 0) ThemeSemantics.live else ThemeSemantics.textTertiary))
                Spacer(Modifier.width(ThemeSpec.Space.sm))
                Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.live_action, liveCount), fontSize = ThemeSpec.Type.meta, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
        sections.forEachIndexed { si, (title, list) ->
            if (list.isEmpty()) return@forEachIndexed
            item(key = "sec_$title", contentType = "section") {
                // A section recycled off screen keeps its scroll position through this map, so scrolling back up continues where you left.
                val rowState = rowStates.getOrPut(title) { LazyListState() }
                val games = list.distinctBy { "${it.first}:${it.second.name}" }
                val wrapState = rememberRailWrapState()
                val wrapFirst = remember { FocusRequester() }
                Column(Modifier.fillMaxWidth()) {
                    SectionTitle(title, Modifier.fillMaxWidth().padding(top = ThemeSpec.Space.xs))
                    LazyRow(
                        state = rowState,
                        modifier = Modifier
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
                            val rights = remember(guideBySlug, lg) {
                                val slug = LeagueCatalog.ALL_LEAGUES.firstOrNull { it.first.equals(lg, ignoreCase = true) }
                                    ?.second?.substringAfterLast('/')?.lowercase().orEmpty()
                                guideBySlug[slug].orEmpty()
                            }
                            GameCard(
                                league = lg, g = g, followed = followed, shielded = shielded, rights = rights,
                                modifier = Modifier
                                    .railWrapItem(wrapState, i)
                                    .then(
                                        if (i == 0) {
                                            Modifier
                                                .focusRequester(wrapFirst)
                                                .then(
                                                    if (si == firstSection) {
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
                    loading -> Row(Modifier.padding(top = ThemeSpec.Space.sm), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md)) {
                        repeat(3) { GameCardSkeleton(GameCardW) }
                    }
                    loadFailed -> PageError("Couldn't load scores. Check the connection, then try again.", onRetry = { retryTick++ })
                    else -> EmptyState(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.scores_no_games), hint = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.scores_no_games_hint))
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
private fun SportChip(label: String, selected: Boolean, logo: String?, onClick: () -> Unit) =
    Chip(label, selected, leading = logo?.let { url -> { AsyncImage(model = url, contentDescription = "$label logo", modifier = Modifier.size(22.dp)) } }, onClick = onClick)

@Composable
private fun RaceRow(r: com.carteltv.tv.data.espn.RacingClient.Race, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val zone = SportsTimeZone.live
    val live = r.state == "in"
    val status = when (r.state) {
        "in" -> r.detail.ifBlank { "LIVE" }
        "post" -> r.detail.ifBlank { "Final" }
        else -> if (r.startMs > 0) {
            val until = r.startMs - System.currentTimeMillis()
            "STARTS IN ${ScoreboardModel.countdown(until)}".uppercase(java.util.Locale.ROOT) +
                " · " + com.carteltv.tv.data.espn.EspnDateMapper.clock(r.startMs, zone)
        } else "UPCOMING"
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
                r.name, fontSize = ThemeSpec.Type.body, fontWeight = FontWeight.Bold,
                color = ThemeSemantics.textEmphatic, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(r.series.ifBlank { null }, r.circuit).joinToString(" · "),
                fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(ThemeSpec.Space.cozy))
        Text(
            status, fontSize = ThemeSpec.Type.meta, fontWeight = FontWeight.SemiBold,
            color = if (live) ThemeSemantics.live else ThemeSemantics.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GameCard(
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
    val venueZone by produceState<ZoneId?>(null, g.location) { value = VenueClock.zoneFor(g.location) }
    val venueNow = VenueClock.localTime(venueZone, now.time)
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
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (live) ThemeSemantics.live else Color.Transparent))
        Column(Modifier.padding(start = ThemeSpec.Space.md, end = ThemeSpec.Space.md, top = ThemeSpec.Space.cozy, bottom = ThemeSpec.Space.cozy)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    league, fontSize = ThemeSpec.Type.label, fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.SemiBold,
                    color = ThemeSemantics.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (followed) Text("\u2605", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.accent, maxLines = 1)
                if (live) {
                    Spacer(Modifier.width(ThemeSpec.Space.sm))
                    LiveBadge()
                }
            }
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            TeamRow(bug.away, plate = awayPlate, dim = decided && awayPts < homePts, lead = decided && awayPts > homePts, showScore = g.state != "pre")
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            TeamRow(bug.home, plate = homePlate, dim = decided && homePts < awayPts, lead = decided && homePts > awayPts, showScore = g.state != "pre")
            bug.place?.let { place ->
                Spacer(Modifier.height(ThemeSpec.Space.sm))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        place, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    (venueNow ?: venueZone?.id)?.let {
                        Text(
                            it, fontSize = ThemeSpec.Type.label, fontFamily = ThemeSpec.Font.broadcast,
                            fontWeight = FontWeight.SemiBold, color = ThemeSemantics.textSecondary,
                            maxLines = 1, modifier = Modifier.padding(start = ThemeSpec.Space.sm),
                        )
                    }
                }
            }
            Spacer(Modifier.height(ThemeSpec.Space.cozy))
            Box(Modifier.fillMaxWidth().height(1.dp).background(ThemeSemantics.hairline))
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Row(Modifier.fillMaxWidth().heightIn(min = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bug.status, fontSize = ThemeSpec.Type.meta, fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (live) ThemeSemantics.live else ThemeSemantics.textSecondary, modifier = Modifier.weight(1f),
                )
                bug.odds?.let {
                    Text(
                        it, fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.SemiBold,
                        color = ThemeSemantics.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = ThemeSpec.Space.sm),
                    )
                }
                if (bug.networks.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) { bug.networks.take(2).forEach { NetworkBadge(it, 24.dp) } }
                } else if (rights.isNotEmpty()) {
                    Text(
                        rights.take(2).joinToString(" · "),
                        fontSize = ThemeSpec.Type.label,
                        color = ThemeSemantics.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = ThemeSpec.Space.sm).widthIn(max = 180.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamRow(side: ScoreboardModel.Side, plate: PlateColors, dim: Boolean, lead: Boolean, showScore: Boolean) {
    val ink = if (dim) ThemeSemantics.textSecondary else ThemeSemantics.textEmphatic
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)).background(plate.outer))
        Spacer(Modifier.width(ThemeSpec.Space.xs))
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (side.logo != null) AsyncImage(model = side.logo, contentDescription = "${side.name} logo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (dim) 0.55f else 1f })
        }
        Spacer(Modifier.width(ThemeSpec.Space.cozy))
        Column(Modifier.weight(1f)) {
            Text(
                side.name, fontSize = ThemeSpec.Type.body, fontWeight = if (dim) FontWeight.Medium else FontWeight.Bold,
                color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            side.record?.let { Text(it, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, maxLines = 1) }
        }
        if (showScore) {
            AutoSizeText(
                side.score ?: "", fontSize = ThemeSpec.Type.score, fontFamily = ThemeSpec.Font.broadcast, fontWeight = if (lead) FontWeight.Black else FontWeight.Bold,
                color = if (lead) ThemeSemantics.win else ink, fontFeatureSettings = TABULAR, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 44.dp, max = 72.dp),            )
        }
    }
}
