package com.carteltv.tv.ui.sports
import com.carteltv.tv.data.espn.LeagueCatalog
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.core.suspendRunCatching

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.carteltv.tv.alert.LiveScoreMonitor
import com.carteltv.tv.alert.KickoffAlertScheduler
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.espn.EspnScoreboardClient
import com.carteltv.tv.data.espn.GameChannelFinder
import com.carteltv.tv.data.espn.GameChannelResolver
import com.carteltv.tv.data.espn.GuideGameMatcher
import com.carteltv.tv.data.espn.MatchConditions
import com.carteltv.tv.data.espn.MatchConditionsSource
import com.carteltv.tv.data.espn.surfaceForLeague
import com.carteltv.tv.data.sportsguide.SportsGuideFeedClient
import com.carteltv.tv.data.xtream.EpgIndexDatabase
import com.carteltv.tv.data.xtream.LiveChannels
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusRing
import kotlinx.coroutines.withContext
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.SurfaceBackdrop
import com.carteltv.tv.ui.nav.cardSurface

@Composable
internal fun DialogButton(
    label: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    Row(
        modifier
            .fillMaxWidth()
            .cardSurface(focused)
            .clickableNoIndication(interaction, onClick)
            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            com.carteltv.tv.ui.theme.PhosphorIcon(icon, ThemeSemantics.textEmphatic, 20.dp)
            Spacer(Modifier.width(ThemeSpec.Space.cozy))
        }
        Text(label, fontSize = ThemeSpec.Type.body, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // A long detail must not push the label out of the row: it gets a ceiling and ellipsizes.
        detail?.let {
            Text(
                it, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = ThemeSpec.Space.cozy).widthIn(max = 220.dp),
            )
        }
    }
}

/**
 * The finder's reasons are written for debugging ("guide: both teams in the title with a vs/at, at game"). The viewer
 * only needs to know why a channel is offered.
 */
internal fun matchReasonLabel(reason: String): String = when {
    reason.startsWith("guide:") -> "On the guide now"
    else -> reason.replaceFirstChar { it.uppercase() }
}

/**
 * The sheet the sports, guide and YouTube menus open on, in the house dialog chrome. [subtitle] is a plate line
 * (time · channel, league · network) in the broadcast face; [message] is long-form copy in the reading face,
 * kept to three lines so a programme description is read rather than ellipsized away.
 */
@Composable
internal fun DialogFrame(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    message: String? = null,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 680.dp)
                .heightIn(max = 470.dp)
                .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .background(ThemeSemantics.surfaceModal)
                .padding(ThemeSpec.Space.lg),
            verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
        ) {
            Text(title, fontSize = ThemeSpec.Type.title, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, fontSize = ThemeSpec.Type.meta, fontFamily = ThemeSpec.Font.broadcast, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            message?.let { Text(it, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(ThemeSpec.Space.xs))
            // The options can outgrow a 1080p screen, so they scroll under a fixed title instead of being clipped.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            ) { content() }
        }
    }
}

/** What to do with one game: watch it, follow it, follow a team, or be reminded at kickoff. */
@Composable
fun GameSheet(
    league: String,
    g: EspnScoreboardClient.Score,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val client = ProviderSession.current
    var tick by remember { mutableIntStateOf(0) }
    var matches by remember { mutableStateOf<List<GameChannelResolver.Match>?>(null) }
    var guideRights by remember(g.name, g.startMs) { mutableStateOf<List<String>>(emptyList()) }
    val first = remember { FocusRequester() }
    val conditions by produceState<MatchConditions?>(null, g.name, g.startMs, g.location) {
        value = MatchConditionsSource.load(g)
    }
    val surface = remember(league) { surfaceForLeague(league) }

    LaunchedEffect(g.name, g.startMs, league) {
        val feed = SportsGuideFeedClient.cached() ?: return@LaunchedEffect
        val slug = LeagueCatalog.ALL_LEAGUES
            .firstOrNull { it.first.equals(league, ignoreCase = true) }
            ?.second?.substringAfterLast('/') ?: league.lowercase()
        val leagueGames = SportsGuideFeedClient.gamesForLeague(feed, slug)
        val targetMs = g.startMs
        val match = leagueGames.minByOrNull { game ->
            val gameMs = runCatching { java.time.Instant.parse(game.startUtc).toEpochMilli() }.getOrDefault(0L)
            val timeDelta = if (targetMs > 0L && gameMs > 0L) kotlin.math.abs(gameMs - targetMs) else Long.MAX_VALUE
            val title = game.title.lowercase()
            val nameA = (g.nameA ?: "").lowercase()
            val nameB = (g.nameB ?: "").lowercase()
            val nameHit = (nameA.isNotBlank() && title.contains(nameA)) || (nameB.isNotBlank() && title.contains(nameB))
            if (nameHit) timeDelta else timeDelta + 86_400_000L
        }
        val chosen = match?.takeIf {
            val gameMs = runCatching { java.time.Instant.parse(it.startUtc).toEpochMilli() }.getOrDefault(0L)
            targetMs <= 0L || gameMs <= 0L || kotlin.math.abs(gameMs - targetMs) <= 3_600_000L
        }
        guideRights = (chosen?.rightsCurated ?: emptyList())
            .filter { it.isNotBlank() }
            .distinct()
    }

    LaunchedEffect(g.name) { matches = GameChannelFinder.rank(ctx, g, league) }
    // Asked again when the matches arrive, so focus moves from the placeholder button to "Tune in".
    LaunchedEffect(matches != null) { suspendRunCatching { first.awaitFocus() } }

    fun watch(m: GameChannelResolver.Match) {
        val c = client ?: return
        AppPreferences.sportsChannelPicks = GameChannelResolver.withPick(AppPreferences.sportsChannelPicks, g, m.stream.streamId)
        AppLauncher.player(ctx, c.liveUrl(m.stream.streamId), m.stream.name, "live:${m.stream.streamId}")
        onDismiss()
    }

    val followed = remember(tick) { LiveScoreMonitor.isFollowed(ctx, league, g.name) }
    val reminded = remember(tick) { KickoffAlertScheduler.hasAlert(ctx, league, g.name) }
    val list = matches
    val top = list?.firstOrNull()

    DialogFrame(
        title = "${g.nameB ?: g.abbrB ?: ""} at ${g.nameA ?: g.abbrA ?: ""}",
        subtitle = listOfNotNull(league, g.network).joinToString(" · "),
        onDismiss = onDismiss,
    ) {
        surface?.let { playingSurface ->
            Box(
                Modifier.fillMaxWidth().height(92.dp)
                    .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                    .background(ThemeSemantics.surfaceRest),
            ) {
                SurfaceBackdrop(playingSurface, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.78f)))))
                Text(
                    league,
                    fontSize = ThemeSpec.Type.meta,
                    fontWeight = FontWeight.Bold,
                    color = ThemeSemantics.onMedia,
                    modifier = Modifier.align(Alignment.BottomStart).padding(ThemeSpec.Space.md),
                    maxLines = 1,
                )
            }
        }
        conditions?.let { w ->
            Text(
                MatchConditionsSource.summary(w, { "%.0f°".format(it) }, { "%.0f km/h".format(it) }),
                fontSize = ThemeSpec.Type.meta,
                color = ThemeSemantics.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            client == null -> Text("Sign in to a TV provider to watch the game.", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            list == null -> Text("Finding the broadcast…", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            top == null -> Text("No channel is showing this game.", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            else -> {
                DialogButton("Tune in", com.carteltv.tv.data.xtream.GuideModel.displayName(top.stream.name), Modifier.focusRequester(first)) { watch(top) }
                if (list.size > 1) {
                    Text("Other channels", fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary,
                        modifier = Modifier.padding(top = ThemeSpec.Space.sm), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    // The sheet already scrolls, so a lazy grid cannot live here: pair the alternates into two-up rows.
                    list.drop(1).take(4).chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                            pair.forEach { m ->
                                DialogButton(
                                    com.carteltv.tv.data.xtream.GuideModel.displayName(m.stream.name), matchReasonLabel(m.reason),
                                    Modifier.weight(1f),
                                ) { watch(m) }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (guideRights.isNotEmpty()) {
            Text(
                "Where to watch",
                fontSize = ThemeSpec.Type.label,
                color = ThemeSemantics.textTertiary,
                modifier = Modifier.padding(top = ThemeSpec.Space.sm),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            guideRights.take(6).forEach { right ->
                Text(
                    right,
                    fontSize = ThemeSpec.Type.meta,
                    color = ThemeSemantics.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = ThemeSpec.Space.xs),
                )
            }
        }

        DialogButton(
            if (followed) "Stop tracking" else "Track this game",
            modifier = if (top == null) Modifier.focusRequester(first) else Modifier,
        ) { LiveScoreMonitor.toggle(ctx, league, g.name); tick++; onChanged() }

        if (g.state == "pre") {
            DialogButton(if (reminded) KickoffAlertScheduler.CANCEL_TEXT else KickoffAlertScheduler.ARM_TEXT) {
                if (reminded) KickoffAlertScheduler.cancelAlert(ctx, league, g.name)
                else {
                    val c = client
                    KickoffAlertScheduler.armAlert(ctx, league, g, top?.let { c?.liveUrl(it.stream.streamId) }, top?.stream?.name)
                }
                tick++; onChanged()
            }
        }

        listOf(g.nameB, g.nameA).filterNotNull().distinct().forEach { team ->
            val mine = remember(tick, team) { KickoffAlertScheduler.inCrew(ctx, league, team) }
            DialogButton(if (mine) "Unfollow $team" else "Follow $team") {
                KickoffAlertScheduler.toggleCrew(ctx, league, team); tick++; onChanged()
            }
        }
    }
}

/** Switch leagues on or off and mark a favourite. */
@Composable
fun LeaguePicker(onDismiss: () -> Unit, onChanged: () -> Unit) {
    var enabled by remember {
        mutableStateOf(LeagueCatalog.enabled(AppPreferences.activeLeagues).map { it.first }.toSet())
    }
    var fav by remember { mutableStateOf(AppPreferences.sportsFavoriteLeague) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { suspendRunCatching { first.awaitFocus() } }

    DialogFrame("Leagues", null, onDismiss, message = "Press OK to switch a league on or off. Choose \u2605 to open on it first.") {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.heightIn(max = 460.dp),
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
        ) {
            items(LeagueCatalog.REGIONS, key = { "region:" + it.first }, contentType = { "region" }) { (region, labels) ->
                DialogButton(label = "$region leagues", detail = "Use these leagues") {
                    val next = labels.toSet()
                    enabled = next
                    AppPreferences.activeLeagues = LeagueCatalog.ALL_LEAGUES.map { it.first }.filter { it in next }.joinToString(",")
                    onChanged()
                }
            }
            items(LeagueCatalog.ALL_LEAGUES, key = { it.first }, contentType = { "feed" }) { (label, _) ->
                Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    DialogButton(
                        label = label,
                        detail = if (label in enabled) "On" else "Off",
                        modifier = Modifier.weight(1f).let { if (label == LeagueCatalog.ALL_LEAGUES.first().first) it.focusRequester(first) else it },
                    ) {
                        val next = if (label in enabled) enabled - label else enabled + label
                        if (next.isNotEmpty()) {
                            enabled = next
                            AppPreferences.activeLeagues = LeagueCatalog.ALL_LEAGUES.map { it.first }.filter { it in next }.joinToString(",")
                            onChanged()
                        }
                    }
                    DialogButton(if (fav == label) "\u2605" else "\u2606", modifier = Modifier.width(72.dp)) {
                        fav = if (fav == label) "" else label
                        AppPreferences.sportsFavoriteLeague = fav
                        onChanged()
                    }
                }
            }
        }
    }
}
