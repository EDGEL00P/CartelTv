package com.carteltv.tv.ui.nav
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.R
import com.carteltv.tv.data.apps.LauncherApp
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.ui.components.ClockFormat
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.rememberClock
import com.carteltv.tv.ui.components.tryFocus
import com.carteltv.tv.ui.theme.PhosphorIcon
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.controlSurface
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusRing
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.theme.glassPane
import kotlin.math.cos
import kotlin.math.sin

/** Shell state shared by the top bar and the screens beneath it. */
object ShellChrome {
    var barHidden by mutableStateOf(false)
    var barFocused by mutableStateOf(false)

    /** Attached to the current section; however focus enters the top bar, it lands here. */
    val barFocus = FocusRequester()

    /** Last focused content node; Down from the top bar returns here. */
    var lastContentFocus: FocusRequester? = null
}

/**
 * Who gets the remote's MENU key before MainActivity's own fallback (open the dock). The focused
 * node registers while it has focus — e.g. a Continue Watching card offering Remove — and the
 * Activity asks here first, so per-card menus win over the shell's default.
 */
object MenuKeyHub {
    private var handler: (() -> Boolean)? = null

    /** Registers [h] while [enabled]; call from DisposableEffect so unfocus/dispose clears it. */
    fun set(
        h: (() -> Boolean)?,
        enabled: Boolean,
    ) {
        handler = if (enabled) h else null
    }

    /** True when a registered handler consumed the key. */
    fun tryHandle(): Boolean = handler?.invoke() ?: false
}

/** Hides the rail for as long as the caller stays in composition: full-screen playback and similar surfaces. */
@Composable
fun HideBarWhile() {
    DisposableEffect(Unit) {
        ShellChrome.barHidden = true
        onDispose { ShellChrome.barHidden = false }
    }
}

/** Lets a backdrop run under the top navigation and, when requested, the shell's start inset. */
internal fun Modifier.bleedUnderShell(
    underBar: Boolean = true,
    underStart: Boolean = true,
): Modifier =
    layout { measurable, constraints ->
        val left = if (underStart) ShellInsetStart.roundToPx() else 0
        val top = if (underBar) TopBarClearance.roundToPx() else OverscanV.roundToPx()
        val placeable =
            measurable.measure(
                Constraints.fixed(constraints.maxWidth + left, constraints.maxHeight + top),
            )
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-left, -top) }
    }

/** Overscan-safe top inset (5% of a 540dp-high TV), per Android's TV layout guidance. */
internal val OverscanV = ThemeSpec.Safe.vertical

/** The main navigation row's height. */
internal val BarHeight = 60.dp

/** Air between the top bar and page content. */
private val BarGap = 12.dp

/** What a screen leaves free along its top edge for the always-visible navigation. A bar-hidden screen keeps the overscan-safe top inset so its own header never rides the bezel. */
internal val TopBarClearance: Dp
    get() = if (ShellChrome.barHidden) ThemeSpec.Safe.vertical else ThemeSpec.Safe.vertical + BarHeight + BarGap

/**
 * What a screen adds to its left so its own gutter lands on the safe margin. Zero while the bar is hidden: a
 * full-surface app (YouTube) lays out its own edges.
 */
internal val ShellInsetStart: Dp
    get() = if (ShellChrome.barHidden) 0.dp else ThemeSpec.Safe.horizontal - ThemeSpec.Space.margin

/** A title on the bar: a section, or a folder whose sections open as a second row above the bar. */
private sealed interface BarItem {
    data class Tab(
        val section: AppSection,
    ) : BarItem

    /** [goesToFirst]: Select on the folder opens its first section, since the folder itself is a real place (Movies & TV). */
    data class Folder(
        val label: Int,
        val children: List<AppSection>,
        val goesToFirst: Boolean,
    ) : BarItem
}

/**
 * Sections that live on the Apps page, off the bar until visits earn them a title; voice and deep links reach
 * them regardless. While any of them is open and not promoted, Apps is the selected title.
 */
internal val OverflowTabs =
    listOf(AppSection.YOUTUBE, AppSection.YOUTUBEMUSIC, AppSection.WEATHER, AppSection.SETUP)

/** Visits before an overflow section earns its own title on the bar. */
private const val PROMOTE_AFTER = 5

/** The overflow section promoted onto the bar, if any: the most visited, ties keeping the default order. */
internal fun promotedOverflow(
    all: List<AppSection>,
    visits: Map<String, Int>,
): AppSection? =
    all
        .filter { (visits[it.name] ?: 0) >= PROMOTE_AFTER }
        .maxByOrNull { visits[it.name] ?: 0 }

private fun barGroups(promoted: AppSection?): List<List<BarItem>> =
    listOf(
        listOf(BarItem.Tab(AppSection.SEARCH), BarItem.Tab(AppSection.HOME)),
        listOf(
            BarItem.Tab(AppSection.LIVE),
            BarItem.Tab(AppSection.SPORTS),
            BarItem.Folder(
                R.string.nav_movies_tv,
                listOf(AppSection.MOVIES, AppSection.TV, AppSection.LIBRARY, AppSection.DISCOVER, AppSection.SHELVES),
                goesToFirst = true,
            ),
        ),
        listOfNotNull(
            BarItem.Tab(AppSection.APPS),
            promoted?.let { BarItem.Tab(it) },
        ),
    )

/**
 * Always-visible top navigation: one row of peer sections; the overflow sections sit on the Apps page, and a
 * frequent one can earn a direct shortcut. Folders open beneath the main row.
 * Down returns to the last content stop.
 */
@Composable
internal fun TopBar(
    current: AppSection,
    onGo: (AppSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasProfiles =
        com.carteltv.tv.data.preferences.Profiles.state.value
            .isNotEmpty()
    val visits = remember(current) { AppPreferences.sectionVisits() }
    // Without profiles the bar's end button is Settings itself, so Settings never also earns a bar title.
    val promoted =
        remember(visits, hasProfiles) {
            promotedOverflow(if (hasProfiles) OverflowTabs else OverflowTabs - AppSection.SETUP, visits)
        }
    // With no profiles the end button is Settings, so Settings is current there rather than under Apps.
    val settingsButtonCurrent = !hasProfiles && navSlot(current) == AppSection.SETUP
    val slot = navSlot(current).let { if (it in OverflowTabs && it != promoted && !settingsButtonCurrent) AppSection.APPS else it }
    var hasFocus by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<BarItem.Folder?>(null) }
    val go = { tab: AppSection ->
        AppPreferences.bumpSectionVisit(tab.name)
        onGo(tab)
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to ThemeSemantics.stage.copy(alpha = 0.98f),
                    0.7f to ThemeSemantics.stage.copy(alpha = 0.88f),
                    1f to Color.Transparent,
                ),
            ).padding(start = ThemeSpec.Safe.horizontal, end = ThemeSpec.Safe.horizontal, top = ThemeSpec.Safe.vertical)
            .onFocusChanged {
                hasFocus = it.hasFocus
                ShellChrome.barFocused = it.hasFocus
                if (!it.hasFocus) open = null
            }.focusProperties {
                // However focus enters the bar, it lands on the current section, not whichever title sits above.
                onEnter = { ShellChrome.barFocus.tryFocus() }
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Down && open == null) {
                        ShellChrome.lastContentFocus?.let { runCatching { it.requestFocus() } }
                    }
                }
            }.focusGroup(),
        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
    ) {
        Row(
            Modifier.fillMaxWidth().height(BarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
        ) {
            barGroups(promoted).forEachIndexed { i, group ->
                if (i >
                    0
                ) {
                    Box(
                        Modifier
                            .padding(horizontal = ThemeSpec.Space.xs)
                            .width(1.dp)
                            .height(20.dp)
                            .background(ThemeSemantics.hairline),
                    )
                }
                group.forEach { item ->
                    when (item) {
                        is BarItem.Tab -> {
                            BarTitle(
                                stringResource(item.section.label),
                                if (item.section ==
                                    AppSection.SEARCH
                                ) {
                                    navIcon(item.section)
                                } else {
                                    null
                                },
                                selected = item.section == slot,
                                anchor =
                                    item.section == slot,
                                onFocused = { open = null },
                                onClick = { go(item.section) },
                            )
                        }

                        is BarItem.Folder -> {
                            val inside = slot in item.children
                            BarTitle(
                                stringResource(item.label),
                                null,
                                selected = inside && promoted != slot,
                                anchor = inside && promoted != slot,
                                onFocused = {
                                    open = item
                                },
                                onClick = {
                                    if (item.goesToFirst &&
                                        !inside
                                    ) {
                                        go(item.children.first())
                                    } else {
                                        open = if (open == item) null else item
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            BarClock()
            Spacer(Modifier.width(ThemeSpec.Space.sm))
            // One job per glyph: the person opens the profile picker; with no profiles yet the button is Settings.
            if (hasProfiles) {
                ScreenActionButton(R.drawable.ph_user, "Profiles") {
                    com.carteltv.tv.data.preferences.Profiles.pickerShown = true
                }
            } else {
                ScreenActionButton(
                    navIcon(AppSection.SETUP),
                    stringResource(AppSection.SETUP.label),
                    modifier = if (settingsButtonCurrent) Modifier.focusRequester(ShellChrome.barFocus) else Modifier,
                    selected = settingsButtonCurrent,
                ) { go(AppSection.SETUP) }
            }
        }
        open?.let { folder ->
            Row(
                Modifier
                    .height(BarHeight)
                    .glassPane(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                    .padding(horizontal = ThemeSpec.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
            ) {
                folder.children.forEach { tab ->
                    BarTitle(
                        stringResource(tab.label),
                        navIcon(tab),
                        selected = tab == slot,
                        anchor = false,
                        onFocused = { },
                        onClick = {
                            open = null
                            go(tab)
                        },
                    )
                }
            }
        }
    }
}

/**
 * One title: icon and label in a capsule pill. The current section rests on a raised glass pill; focus brightens
 * it and adds a focus ring, so the two never look alike when they sit on different titles.
 * [anchor] marks the title focus lands on when it enters the bar.
 */
@Composable
private fun BarTitle(
    label: String,
    @DrawableRes icon: Int?,
    selected: Boolean,
    anchor: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed = pressed)
    val spec = tween<Color>(ThemeSpec.Motion.DUR_MID, easing = ThemeSpec.Motion.easeEnter)
    val ink by animateColorAsState(
        when {
            focused -> ThemeSemantics.stage
            selected -> ThemeSemantics.textInverse
            else -> ThemeSemantics.textSecondary
        },
        spec,
        label = "barInk",
    )
    val pill by animateColorAsState(
        when {
            focused -> ThemeSemantics.textEmphatic
            selected -> ThemeSemantics.accent
            else -> Color.Transparent
        },
        spec,
        label = "barPill",
    )
    Row(
        Modifier
            .then(if (anchor) Modifier.focusRequester(ShellChrome.barFocus) else Modifier)
            .height(48.dp)
            .focusLift(scale)
            .clip(CircleShape)
            .background(pill)
            .focusRing(focused, CircleShape)
            .clickableNoIndication(interaction, onClick)
            .padding(horizontal = ThemeSpec.Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs + 2.dp),
    ) {
        icon?.let { PhosphorIcon(it, ink, 20.dp) }
        Text(
            label,
            fontSize = ThemeSpec.Type.body,
            fontFamily = ThemeSpec.Font.broadcast,
            fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal,
            color = ink,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** What makes an installed app a streaming channel rather than a tool. */
private val STREAMING_HINTS =
    listOf(
        "netflix",
        "disney",
        "hulu",
        "prime",
        "amazon",
        "max",
        "hbo",
        "peacock",
        "paramount",
        "apple tv",
        "youtube",
        "tubi",
        "pluto",
        "plex",
        "crunchyroll",
    )

/** Which navigation bar title lights up for [tab]: the guide page is Live TV's, and every other section has a title of its own. */
internal fun navSlot(tab: AppSection): AppSection = if (tab == AppSection.GUIDE) AppSection.LIVE else tab

/**
 * The streaming services among [apps], most launched first: what the Apps page's Channels & Apps group and
 * Home's Streaming shelf are both built from. Hidden apps are left out here; a PIN stays each shelf's own gate.
 */
internal fun streamingApps(apps: List<LauncherApp>): List<LauncherApp> {
    val hidden = AppPreferences.hiddenApps()
    val launchCounts = AppPreferences.launchCounts()
    return apps
        .filter { it.packageName !in hidden }
        .filter { app ->
            val hay = (app.label + " " + app.packageName).lowercase()
            STREAMING_HINTS.any { it in hay }
        }.sortedByDescending { launchCounts[it.packageName] ?: 0 }
}

internal fun navIcon(tab: AppSection): Int =
    when (tab) {
        AppSection.HOME -> R.drawable.ph_house
        AppSection.APPS -> R.drawable.ph_squares_four
        AppSection.LIVE -> R.drawable.ph_broadcast
        AppSection.GUIDE -> R.drawable.ph_list_bullets
        AppSection.SPORTS -> R.drawable.ph_basketball
        AppSection.WEATHER -> R.drawable.ph_cloud_sun
        AppSection.MOVIES -> R.drawable.ph_film_strip
        AppSection.TV -> R.drawable.ph_monitor_play
        AppSection.LIBRARY -> R.drawable.ph_bookmark_simple
        AppSection.DISCOVER -> R.drawable.ph_magnifying_glass
        AppSection.SHELVES -> R.drawable.ph_stack
        AppSection.YOUTUBE -> R.drawable.ph_youtube_logo
        AppSection.YOUTUBEMUSIC -> R.drawable.ph_music_notes
        AppSection.RECENTS -> R.drawable.ph_clock_counter_clockwise
        AppSection.SEARCH -> R.drawable.ph_magnifying_glass
        AppSection.SETUP -> R.drawable.ph_gear_six
    }

/** A real masthead action: large enough for the remote, labelled for accessibility, and visibly focused. */
@Composable
internal fun ScreenActionButton(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    // The app's one control language: a glass capsule at rest, the accent while its section is current (as the
    // bar's pills are), the white key light under focus.
    Box(
        modifier
            .size(48.dp)
            .semantics { contentDescription = label }
            .controlSurface(focused, selected, pressed)
            .clickableNoIndication(interaction, onClick),
        contentAlignment = Alignment.Center,
    ) {
        PhosphorIcon(icon, ThemeSemantics.controlInk(focused, selected), 22.dp)
    }
}

/** The time of day at the bar's end, beside Profiles or Settings, in the person's 12- or 24-hour choice. */
@Composable
private fun BarClock() {
    val now = rememberClock(30_000L)
    Text(
        ClockFormat.time(now.time, AppPreferences.clock24),
        fontSize = ThemeSpec.Type.meta,
        fontFamily = ThemeSpec.Font.broadcast,
        fontWeight = FontWeight.Bold,
        color = ThemeSemantics.textSecondary,
        // Tabular figures keep the digits from shifting as the minutes turn.
        style = TextStyle(fontFeatureSettings = "tnum"),
        maxLines = 1,
    )
}
