package com.carteltv.tv.ui.youtube
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.youtube.YouTubeApiClient
import com.carteltv.tv.data.youtube.YouTubeLibrary
import com.carteltv.tv.data.youtube.YouTubeSubscriptions
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.CardArtwork
import com.carteltv.tv.ui.components.Chip
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.FilterChip
import com.carteltv.tv.ui.components.RailFocusLine
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.TvTextField
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.combinedClickableNoIndication
import com.carteltv.tv.ui.components.focusZLift
import com.carteltv.tv.ui.components.railEdgeFade
import com.carteltv.tv.ui.components.railWrapItem
import com.carteltv.tv.ui.components.railWrapToStart
import com.carteltv.tv.ui.components.rememberRailWrapState
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.shimmer
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.player.PlayerGlyph
import com.carteltv.tv.ui.player.PlayerGlyphIcon
import com.carteltv.tv.ui.sports.DialogButton
import com.carteltv.tv.ui.sports.DialogFrame
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.theme.glassSurface
import kotlinx.coroutines.delay

private typealias Video = YouTubeApiClient.YouTubeVideo

private val RAIL =
    listOf(
        "Search" to PlayerGlyph.SEARCH,
        "Home" to PlayerGlyph.HOME,
        "Trending" to PlayerGlyph.SYNC,
        "Subscriptions" to PlayerGlyph.SOURCES,
        "Library" to PlayerGlyph.BOOKMARK,
        "History" to PlayerGlyph.HISTORY,
        "Settings" to PlayerGlyph.SETTINGS,
    )

@Composable
internal fun YouTubeRail(
    selected: String,
    signedIn: Boolean,
    unseenSubCount: Int = 0,
    onSelect: (String) -> Unit,
    onAccount: () -> Unit,
) {
    Column(
        Modifier
            .width(128.dp)
            .fillMaxHeight()
            .background(ThemeSemantics.surfaceRest)
            .border(width = 1.dp, color = ThemeSemantics.hairline, shape = RoundedCornerShape(0.dp))
            .padding(top = ThemeSpec.Space.md, bottom = ThemeSpec.Safe.vertical, start = ThemeSpec.Space.sm, end = ThemeSpec.Space.sm),
        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for ((label, glyph) in RAIL) {
            if (label == "Settings") Spacer(Modifier.weight(1f))
            RailItem(
                glyph,
                label,
                selected == label,
                badge = if (label == "Subscriptions") unseenSubCount else 0,
            ) { onSelect(label) }
        }
        Spacer(Modifier.height(ThemeSpec.Space.cozy))
        RailItem(
            PlayerGlyph.PERSON,
            if (signedIn) "Sign out" else "Sign in",
            selected = false,
            accent = signedIn,
            onClick = onAccount,
        )
        Spacer(Modifier.height(ThemeSpec.Space.lg))
    }
}

@Composable
private fun RailItem(
    glyph: PlayerGlyph,
    label: String,
    selected: Boolean,
    badge: Int = 0,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    val active = selected || focused || accent
    Row(
        Modifier
            .width(116.dp)
            .cardSurface(focused, ThemeSpec.Focus.liftCompact)
            .clip(shape)
            .background(if (selected) ThemeSemantics.surfaceRaised else Color.Transparent)
            .clickableNoIndication(interaction, onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).height(44.dp).background(if (selected) ThemeSemantics.accent else Color.Transparent))
        Column(
            Modifier.weight(1f).padding(vertical = ThemeSpec.Space.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
        ) {
            Box {
                PlayerGlyphIcon(glyph, if (active) ThemeSemantics.accent else YouTubeDim, 18.dp)
                if (badge > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(ThemeSemantics.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            badge.coerceAtMost(9).toString(),
                            fontSize = ThemeSpec.Type.micro,
                            color = ThemeSemantics.textInverse,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                label,
                fontSize = ThemeSpec.Type.label,
                fontFamily = ThemeSpec.Font.broadcast,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (active) ThemeSemantics.textEmphatic else YouTubeDim,
            )
        }
    }
}

/**
 * The boxed classic video tile, after GridPoster: one frame whose head is the 16:9 artwork with
 * its duration/resume overlays and whose foot is the raised content block carrying title, channel,
 * views and upload date. Captions inside the frame keep a shelf reading as a row of boxes, and the
 * frame's lift and ring replace the artwork's own glass plate.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YouTubeCard(
    v: Video,
    saved: Boolean,
    modifier: Modifier = Modifier,
    width: Dp? = 200.dp,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val resumeMs = remember(v.videoId) { AppPreferences.titleResumeMs("yt:${v.videoId}") }
    val resume = resumeMs > 5_000L
    val progress =
        remember(v.duration, resumeMs) {
            val total = durationSecs(v.duration) * 1000L
            if (resume && total > 0) (resumeMs.toFloat() / total).coerceIn(0.02f, 1f) else 0f
        }
    val meta = remember(v.publishedMs, v.publishedText, v.viewsText) { uploadLine(v) }
    Box(
        modifier
            .let { if (width != null) it.width(width) else it }
            .focusZLift(focused)
            // Netflix scale: the focused tile looms over its row, not a quiet lift.
            .cardSurface(focused, lift = ThemeSpec.Focus.liftPoster, pressed = pressed)
            .combinedClickableNoIndication(interactionSource = interaction, onClick = onClick, onLongClick = onMenu),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                CardArtwork(v.thumb, v.title, Modifier.fillMaxSize())
                if (focused) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(YouTubeAccent.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        PlayerGlyphIcon(PlayerGlyph.PLAY, Color.White, 14.dp)
                    }
                }
                if (saved) {
                    Box(
                        Modifier
                            .align(
                                Alignment.TopStart,
                            ).padding(
                                ThemeSpec.Space.xs,
                            ).size(20.dp)
                            .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                            .background(ThemeSemantics.scrimChip),
                        contentAlignment = Alignment.Center,
                    ) { PlayerGlyphIcon(PlayerGlyph.BOOKMARK_ON, YouTubeBrass, 12.dp) }
                }
                if (resume) {
                    Text(
                        "RESUME",
                        fontSize = ThemeSpec.Type.micro,
                        fontWeight = FontWeight.Bold,
                        color = YouTubeAccent,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(ThemeSpec.Space.xs)
                                .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                                .background(ThemeSemantics.scrimChip)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        maxLines = 1,
                    )
                }
                if (v.duration != null) {
                    Text(
                        v.duration,
                        fontSize = ThemeSpec.Type.micro,
                        color = ThemeSemantics.textEmphatic,
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(ThemeSpec.Space.xs)
                                .background(ThemeSemantics.scrimChip, RoundedCornerShape(ThemeSpec.Card.cornerSm))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        maxLines = 1,
                    )
                }
                if (progress >
                    0f
                ) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(ThemeSemantics.progressTrack),
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(YouTubeAccent))
                    }
                }
                if (focused) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(1.5.dp)
                            .background(YouTubeAccent),
                    )
                }
            }
            // The classic content block: a raised plate inside the frame, in place of free-floating captions.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(ThemeSemantics.surface2)
                    .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.cozy),
                verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
            ) {
                // Two title lines whatever the title's length, so every tile in the rail stands equally tall.
                Text(
                    v.title,
                    fontSize = ThemeSpec.Type.cardTitle,
                    fontFamily = ThemeSpec.Font.broadcast,
                    fontWeight = FontWeight.SemiBold,
                    color = if (focused) ThemeSemantics.textEmphatic else ThemeSemantics.textPrimary,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                v.channel?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontSize = ThemeSpec.Type.label,
                        fontFamily = ThemeSpec.Font.body,
                        color = ThemeSemantics.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        fontSize = ThemeSpec.Type.label,
                        fontFamily = ThemeSpec.Font.body,
                        color = ThemeSemantics.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** "1:02:03" / "12:34" → seconds; 0 when absent or unparsable (e.g. LIVE). */
private fun durationSecs(d: String?): Long =
    d?.split(":")?.map { it.toLongOrNull() ?: return 0L }?.fold(0L) { acc, p -> acc * 60 + p } ?: 0L

/** "Channel · 1.2M views · 2 days ago": the line under a title. */
private fun byline(v: Video): String? =
    listOfNotNull(
        v.channel,
        v.viewsText,
        uploadedLabel(v).takeIf { it.isNotEmpty() },
    ).joinToString("  \u00b7  ").ifEmpty { null }

/** The tile's metadata: views and the upload stamp, never the channel (that is its own line). */
private fun uploadLine(v: Video): String = listOfNotNull(v.viewsText, uploadedLabel(v).takeIf { it.isNotEmpty() }).joinToString(" \u00b7 ")

/** Exact upload date/time when the feed carries it, otherwise YouTube's own age text. */
private fun uploadedLabel(v: Video): String = YouTubeSubscriptions.uploadedAt(v.publishedMs).ifEmpty { v.publishedText.orEmpty() }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    v: Video,
    saved: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    // The card surface, spelled out so the OK-press dip can ride the same lift.
    Row(
        Modifier
            .fillMaxWidth()
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .glassSurface(focused, shape)
            .combinedClickableNoIndication(interactionSource = interaction, onClick = onClick, onLongClick = onMenu)
            .padding(ThemeSpec.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
    ) {
        Box(Modifier.width(220.dp).aspectRatio(16f / 9f).clip(shape)) {
            CardArtwork(v.thumb, v.title, Modifier.fillMaxSize())
            if (v.duration != null) {
                Text(
                    v.duration,
                    fontSize = ThemeSpec.Type.label,
                    color = ThemeSemantics.textEmphatic,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(ThemeSpec.Space.sm)
                            .background(
                                ThemeSemantics.scrimChip,
                                RoundedCornerShape(ThemeSpec.Card.cornerSm),
                            ).padding(horizontal = ThemeSpec.Space.xs, vertical = 1.dp),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                v.title,
                fontSize = ThemeSpec.Type.body,
                color = ThemeSemantics.textEmphatic,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            byline(v)?.let {
                Text(
                    it,
                    fontSize = ThemeSpec.Type.meta,
                    color = YouTubeDim,
                    maxLines = 1,
                    modifier = Modifier.padding(top = ThemeSpec.Space.xs),
                )
            }
        }
        if (saved) PlayerGlyphIcon(PlayerGlyph.BOOKMARK_ON, YouTubeBrass, 22.dp)
    }
}

@Composable
private fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) = FilterChip(label, selected, onClick = onClick)

@Composable
private fun SkeletonShelves() {
    Column(Modifier.padding(start = ThemeSpec.Space.margin).shimmer(), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.lg)) {
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                // A shelf heading's placeholder is a small container, so it takes the small corner.
                Box(
                    Modifier
                        .width(220.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                        .background(YouTubeCardIdle),
                )
                // A rail, not a grid row: fixed-width tiles that run past the edge the way the loaded shelves do.
                LazyRow(userScrollEnabled = false, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                    items(
                        6,
                    ) {
                        Box(
                            Modifier
                                .width(200.dp)
                                .height(112.dp)
                                .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                                .background(YouTubeCardIdle),
                        )
                    }
                }
            }
        }
    }
}

/** Search results while they load: rows in the shape the results arrive in, under one shared sweep. */
@Composable
private fun SkeletonResults() {
    Column(Modifier.shimmer(), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
        repeat(4) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(
                        RoundedCornerShape(ThemeSpec.Card.corner),
                    ).background(YouTubeCardIdle)
                    .padding(ThemeSpec.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
            ) {
                Box(
                    Modifier
                        .width(
                            220.dp,
                        ).aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                        .background(ThemeSemantics.surfaceRaised),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                0.6f,
                            ).height(18.dp)
                            .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                            .background(ThemeSemantics.surfaceRaised),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(
                                0.35f,
                            ).height(14.dp)
                            .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                            .background(ThemeSemantics.surfaceRaised),
                    )
                }
            }
        }
    }
}

@Composable
internal fun YouTubeStage(
    view: String,
    shelves: List<YouTubeApiClient.YouTubeShelf>,
    extraShelves: List<YouTubeApiClient.YouTubeShelf>,
    results: List<Video>,
    query: String,
    busy: Boolean,
    notice: String?,
    libTick: Int,
    subs: List<YouTubeSubscriptions.Sub>,
    subFeed: List<Video>,
    subBusy: Boolean,
    channel: YouTubeApiClient.YouTubeChannel?,
    channelBusy: Boolean,
    channelNotice: String?,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onRecentSearch: (String) -> Unit,
    onRetryFeed: () -> Unit,
    onClearHistory: () -> Unit,
    onPlay: (Video, List<Video>) -> Unit,
    onMenu: (Video) -> Unit,
    onToggleSaved: (Video) -> Unit,
    onOpenChannel: (String, String) -> Unit,
    onToggleSub: (YouTubeSubscriptions.Sub) -> Unit,
    onRefreshSubs: () -> Unit,
) {
    val savedIds = remember(libTick) { YouTubeLibrary.saved().map { it.videoId }.toSet() }
    when (view) {
        "HISTORY" -> {
            val videos = remember(libTick) { YouTubeLibrary.history() }
            VideoGrid(
                title = "History",
                note = "${videos.size}",
                header = { HeaderActions { if (videos.isNotEmpty()) ActionButton("Clear history", filled = false) { onClearHistory() } } },
                videos = videos,
                savedIds = savedIds,
                onPlay = onPlay,
                onMenu = onMenu,
                empty = "Videos you watch will appear here.",
            )
        }

        "SAVED", "LIBRARY" -> {
            val saved = remember(libTick) { YouTubeLibrary.saved() }
            val history = remember(libTick) { YouTubeLibrary.history() }
            VideoGrid(
                title = "Library",
                note = "${saved.size + history.size}",
                header =
                    if (history.isNotEmpty()) {
                        {
                            Text(
                                "Watch Later / Saved and recent history",
                                fontSize = ThemeSpec.Type.meta,
                                color = YouTubeDim,
                                modifier = Modifier.padding(start = ThemeSpec.Space.margin, bottom = ThemeSpec.Space.sm),
                            )
                        }
                    } else {
                        null
                    },
                videos = (saved + history).distinctBy { it.videoId },
                savedIds = savedIds,
                onPlay = onPlay,
                onMenu = onMenu,
                empty = "Saved videos and watch history will appear here.",
            )
        }

        "SUBS" -> {
            val shown =
                if (com.carteltv.tv.data.youtube.YouTubeSession
                        .signedIn()
                ) {
                    subFeed.size
                } else {
                    subs.size
                }
            VideoGrid(
                title = "Subscriptions",
                note = "$shown",
                header = {
                    Column(Modifier.padding(start = ThemeSpec.Space.margin)) {
                        HeaderActions {
                            if (subs.isNotEmpty() ||
                                subFeed.isNotEmpty()
                            ) {
                                ActionButton("Refresh", filled = false) { onRefreshSubs() }
                            }
                        }
                        if (subs.isNotEmpty()) {
                            LazyRow(
                                Modifier.padding(top = ThemeSpec.Space.sm, bottom = ThemeSpec.Space.sm).restoreRowFocus(),
                                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                            ) {
                                items(subs, key = { it.id }) { s -> Pill(s.name, false) { onOpenChannel(s.id, s.name) } }
                            }
                        }
                    }
                },
                videos = subFeed,
                savedIds = savedIds,
                onPlay = onPlay,
                onMenu = onMenu,
                empty =
                    when {
                        subBusy -> {
                            "Loading your subscriptions\u2026"
                        }

                        subs.isEmpty() &&
                            com.carteltv.tv.data.youtube.YouTubeSession
                                .signedIn()
                        -> {
                            "Couldn't load your subscriptions. Check the connection, or sign out and sign in again."
                        }

                        subs.isEmpty() -> {
                            "No subscriptions yet. Press and hold OK on a video and choose Subscribe, or open a channel from its menu."
                        }

                        else -> {
                            "No recent uploads from your channels."
                        }
                    },
            )
        }

        "TRENDING" -> {
            val trending =
                shelves.firstOrNull { it.title.contains("Trending", true) }?.videos
                    ?: shelves.flatMap { it.videos }.distinctBy { it.videoId }.take(24)
            VideoGrid(
                title = "Trending Now",
                note = "${trending.size}",
                videos = trending,
                savedIds = savedIds,
                onPlay = onPlay,
                onMenu = onMenu,
                empty = if (busy) "Loading trending videos\u2026" else notice ?: "No trending videos right now.",
            )
        }

        "SETTINGS" -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                    Text(
                        "YouTube Settings",
                        fontSize = ThemeSpec.Type.titleL,
                        color = ThemeSemantics.textEmphatic,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Use the app Settings screen for playback and account options.",
                        fontSize = ThemeSpec.Type.body,
                        color = YouTubeDim,
                    )
                }
            }
        }

        "CHANNEL" -> {
            val subscribed = remember(libTick, channel?.id) { YouTubeSubscriptions.isSubscribed(channel?.id) }
            VideoGrid(
                title = "Recent Videos",
                header = {
                    Row(
                        Modifier.padding(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal, bottom = ThemeSpec.Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(76.dp).clip(CircleShape).background(YouTubeCardIdle)) {
                            channel?.avatar?.let {
                                AsyncImage(
                                    model = it,
                                    contentDescription = "${channel?.name ?: "Channel"} avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.width(ThemeSpec.Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                channel?.name ?: if (channelBusy) "Loading channel\u2026" else "Channel",
                                fontSize = ThemeSpec.Type.titleL,
                                color = ThemeSemantics.textEmphatic,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            channel?.let {
                                Text(
                                    androidx.compose.ui.res
                                        .stringResource(com.carteltv.tv.R.string.youtube_recent_videos, it.videos.size),
                                    fontSize = ThemeSpec.Type.meta,
                                    color = YouTubeDim,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (channel != null) {
                            ActionButton(if (subscribed) "Unsubscribe" else "Subscribe", filled = !subscribed) {
                                onToggleSub(YouTubeSubscriptions.Sub(channel.id, channel.name, channel.avatar))
                            }
                        }
                    }
                },
                videos = channel?.videos.orEmpty(),
                savedIds = savedIds,
                onPlay = { v, _ -> onPlay(v, channel?.videos.orEmpty()) },
                onMenu = onMenu,
                empty = channelNotice ?: if (channelBusy) "Loading uploads\u2026" else "No videos found on this channel.",
            )
        }

        "SEARCH" -> {
            SearchStage(query, results, busy, notice, libTick, savedIds, onQuery, onSearch, onRecentSearch, onPlay, onMenu)
        }

        else -> {
            HomeStage(shelves, extraShelves, busy, notice, savedIds, onRetryFeed, onPlay, onMenu)
        }
    }
}

/** Right-aligned actions over a rail page, on the same right inset every stage row uses. */
@Composable
private fun HeaderActions(actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = ThemeSpec.Space.sm, end = ThemeSpec.Safe.horizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun HomeStage(
    youtubeShelves: List<YouTubeApiClient.YouTubeShelf>,
    extraShelves: List<YouTubeApiClient.YouTubeShelf>,
    busy: Boolean,
    notice: String?,
    savedIds: Set<String>,
    onRetry: () -> Unit,
    onPlay: (Video, List<Video>) -> Unit,
    onMenu: (Video) -> Unit,
) {
    val shelves =
        remember(youtubeShelves, extraShelves) {
            val merged = (extraShelves + youtubeShelves).filter { it.videos.isNotEmpty() }
            val byTitle = merged.associateBy { it.title.lowercase() }
            val ordered =
                buildList {
                    byTitle["trending now"]?.let(::add) ?: byTitle["trending"]?.let(::add)
                    byTitle["from your subscriptions"]?.let(::add)
                    byTitle["continue watching"]?.let(::add)
                    byTitle["recommended for you"]?.let(::add) ?: byTitle["recommended"]?.let(::add)
                    byTitle["shorts"]?.let(::add)
                    byTitle["playlists"]?.let(::add)
                    byTitle["watch later"]?.let(::add) ?: byTitle["saved for later"]?.let(::add)
                    merged.forEach { s -> if (none { it.title == s.title }) add(s) }
                }
            ordered.map { shelf ->
                val t = shelf.title.lowercase()
                val renamed =
                    when {
                        t == "continue watching" -> "Continue Watching / History"
                        t == "saved for later" -> "Watch Later"
                        t.contains("recommend") -> "Recommended For You"
                        t.contains("trending") -> "Trending Now"
                        else -> shelf.title
                    }
                if (renamed == shelf.title) shelf else YouTubeApiClient.YouTubeShelf(renamed, shelf.videos)
            }
        }
    val firstFocus = remember { FocusRequester() }
    val firstId =
        shelves
            .firstOrNull()
            ?.videos
            ?.firstOrNull()
            ?.videoId
    LaunchedEffect(firstId) { if (firstId != null) suspendRunCatching { firstFocus.awaitFocus() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = ThemeSpec.Space.md, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.lg),
    ) {
        if (shelves.isEmpty() && busy) item { SkeletonShelves() }
        shelves.forEachIndexed { shelfIndex, shelf ->
            item(key = "h_${shelf.title}", contentType = "shelfHead") {
                Text(
                    shelf.title,
                    fontSize = ThemeSpec.Type.title,
                    fontWeight = FontWeight.SemiBold,
                    color = ThemeSemantics.textEmphatic,
                    fontFamily = ThemeSpec.Font.display,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = ThemeSpec.Space.margin, top = ThemeSpec.Space.sm),
                )
            }
            item(key = "r_${shelf.title}", contentType = "shelfRow") {
                ShelfRow(shelf, savedIds, if (shelfIndex == 0) firstFocus else null, onPlay, onMenu)
            }
        }
        if (shelves.isEmpty() && !busy) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                    EmptyState(notice ?: "Nothing to show yet.")
                    Box(Modifier.padding(start = ThemeSpec.Space.margin)) { ActionButton("Retry", filled = true) { onRetry() } }
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(
    shelf: YouTubeApiClient.YouTubeShelf,
    savedIds: Set<String>,
    firstFocus: FocusRequester?,
    onPlay: (Video, List<Video>) -> Unit,
    onMenu: (Video) -> Unit,
) {
    val title = shelf.title.lowercase()
    val isShorts = title.contains("short")
    val isPlaylist = title.contains("playlist") || title.contains("watch later") || title.contains("liked")
    val videos = shelf.videos.distinctBy { it.videoId }
    LazyRow(
        Modifier.restoreRowFocus(),
        contentPadding = PaddingValues(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal),
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
    ) {
        if (isPlaylist) {
            item(key = "pl_${shelf.title}") {
                PlaylistCard(shelf.title, videos, onOpen = { videos.firstOrNull()?.let { onPlay(it, videos) } })
            }
            items(videos.take(8), key = { "plv_${shelf.title}_${it.videoId}" }) { v ->
                val mod =
                    if (firstFocus != null &&
                        v.videoId == videos.firstOrNull()?.videoId
                    ) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    }
                YouTubeCard(v, saved = v.videoId in savedIds, modifier = mod, onClick = { onPlay(v, videos) }, onMenu = { onMenu(v) })
            }
        } else if (isShorts) {
            items(videos.take(12), key = { "sh_${shelf.title}_${it.videoId}" }) { v ->
                val mod =
                    if (firstFocus != null &&
                        v.videoId == videos.firstOrNull()?.videoId
                    ) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    }
                ShortCard(v, modifier = mod, onClick = { onPlay(v, videos) }, onMenu = { onMenu(v) })
            }
        } else {
            items(videos, key = { "${shelf.title}_${it.videoId}" }) { v ->
                val mod =
                    if (firstFocus != null &&
                        v.videoId == videos.firstOrNull()?.videoId
                    ) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    }
                YouTubeCard(v, saved = v.videoId in savedIds, modifier = mod, onClick = { onPlay(v, videos) }, onMenu = { onMenu(v) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortCard(
    v: Video,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    Column(
        modifier
            .width(79.dp)
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .combinedClickableNoIndication(interactionSource = interaction, onClick = onClick, onLongClick = onMenu),
    ) {
        Box(Modifier.fillMaxWidth().height(140.dp).glassSurface(focused, shape)) {
            CardArtwork(v.thumb, v.title, Modifier.fillMaxSize())
            if (focused) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(YouTubeAccent.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayerGlyphIcon(PlayerGlyph.PLAY, Color.White, 14.dp)
                }
            }
            if (focused) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .background(YouTubeAccent),
                )
            }
        }
        Text(
            v.title,
            fontSize = ThemeSpec.Type.label,
            color = ThemeSemantics.textEmphatic,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = ThemeSpec.Space.xs),
        )
        val meta = remember(v.publishedMs, v.publishedText, v.viewsText) { uploadLine(v) }
        if (meta.isNotEmpty()) {
            Text(
                meta,
                fontSize = ThemeSpec.Type.micro,
                color = ThemeSemantics.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun PlaylistCard(
    title: String,
    videos: List<Video>,
    onOpen: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    val thumbs = videos.take(4)
    Column(
        Modifier
            .width(140.dp)
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .clickableNoIndication(interaction, onOpen),
    ) {
        Box(Modifier.fillMaxWidth().height(79.dp).glassSurface(focused, shape)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { CardArtwork(thumbs.getOrNull(0)?.thumb, title, Modifier.fillMaxSize()) }
                    Box(Modifier.weight(1f).fillMaxHeight()) { CardArtwork(thumbs.getOrNull(1)?.thumb, title, Modifier.fillMaxSize()) }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { CardArtwork(thumbs.getOrNull(2)?.thumb, title, Modifier.fillMaxSize()) }
                    Box(Modifier.weight(1f).fillMaxHeight()) { CardArtwork(thumbs.getOrNull(3)?.thumb, title, Modifier.fillMaxSize()) }
                }
            }
            Text(
                "${videos.size} videos",
                fontSize = ThemeSpec.Type.micro,
                color = ThemeSemantics.onMedia,
                modifier =
                    Modifier
                        .align(
                            Alignment.BottomEnd,
                        ).padding(4.dp)
                        .background(ThemeSemantics.scrimChip, RoundedCornerShape(2.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
            )
            if (focused) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .background(YouTubeAccent),
                )
            }
        }
        Text(
            title,
            fontSize = ThemeSpec.Type.meta,
            color = ThemeSemantics.textEmphatic,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = ThemeSpec.Space.xs),
        )
        if (focused) {
            Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                videos.take(3).forEach { v ->
                    Text(
                        v.title,
                        fontSize = ThemeSpec.Type.micro,
                        color = ThemeSemantics.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * One browsed collection as a labelled slider rail: a SectionTitle over cards the width of every house rail,
 * running off the page edge instead of wrapping. [header] sits above the rail and may hold actions or chips;
 * [note] is the quiet count beside the heading. The play queue stays the whole [videos] list, as the grid's was.
 */
@Composable
private fun VideoGrid(
    title: String,
    note: String? = null,
    header: (@Composable () -> Unit)? = null,
    videos: List<Video>,
    empty: String,
    savedIds: Set<String>,
    onPlay: (Video, List<Video>) -> Unit,
    onMenu: (Video) -> Unit,
) {
    val first = remember { FocusRequester() }
    val cards = videos.distinctBy { it.videoId }
    val rowState = rememberLazyListState()
    LaunchedEffect(cards.firstOrNull()?.videoId) { if (cards.isNotEmpty()) suspendRunCatching { first.awaitFocus() } }
    // Every rail on the page parks its focused card on the same line, the way a streaming app keeps its
    // sliders aligned while you browse.
    RailFocusLine {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = ThemeSpec.Space.md, bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
        ) {
            if (header != null) item(contentType = "masthead") { header() }
            if (cards.isEmpty()) {
                item(contentType = "empty") {
                    EmptyState(empty, Modifier.padding(top = ThemeSpec.Space.margin))
                }
            } else {
                item(contentType = "rail") {
                    val wrapState = rememberRailWrapState()
                    Column(Modifier.fillMaxWidth()) {
                        SectionTitle(
                            title,
                            Modifier.padding(start = ThemeSpec.Space.margin, top = ThemeSpec.Space.xs, bottom = ThemeSpec.Space.xs),
                            note = note,
                        )
                        LazyRow(
                            state = rowState,
                            // The peek of the next card dissolves into the page instead of being sliced at the viewport.
                            modifier =
                                Modifier
                                    .restoreRowFocus()
                                    .railEdgeFade(rowState)
                                    .railWrapToStart(wrapState, rowState, cards.size, first),
                            // Vertical room so a Netflix-scale focused card is never clipped by the row.
                            contentPadding =
                                PaddingValues(
                                    start = ThemeSpec.Space.margin,
                                    end = ThemeSpec.Safe.horizontal,
                                    top = 32.dp,
                                    bottom = 32.dp,
                                ),
                            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                        ) {
                            itemsIndexed(cards, key = { _, v -> v.videoId }) { index, v ->
                                YouTubeCard(
                                    v,
                                    saved = v.videoId in savedIds,
                                    modifier =
                                        Modifier
                                            .railWrapItem(wrapState, index)
                                            .then(if (v.videoId == cards.first().videoId) Modifier.focusRequester(first) else Modifier),
                                    onClick = { onPlay(v, videos) },
                                    onMenu = { onMenu(v) },
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
private fun SearchStage(
    query: String,
    results: List<Video>,
    busy: Boolean,
    notice: String?,
    libTick: Int,
    savedIds: Set<String>,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onRecent: (String) -> Unit,
    onPlay: (Video, List<Video>) -> Unit,
    onMenu: (Video) -> Unit,
) {
    val recents = remember(libTick, results.size) { YouTubeLibrary.searches() }
    var typed by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    // Results follow the typing, as on the house Search page. The stage asks for them
    // after a pause so a word is not fetched once per key.
    LaunchedEffect(typed.text) {
        if (typed.text.trim().length < 2) return@LaunchedEffect
        delay(450)
        onSearch()
    }
    LaunchedEffect(query) {
        if (typed.text != query) typed = TextFieldValue(query, TextRange(query.length))
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                top = ThemeSpec.Space.md,
                bottom = 90.dp,
                start = ThemeSpec.Space.margin,
                end = ThemeSpec.Safe.horizontal,
            ),
        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
    ) {
        item(key = "field") {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { suspendRunCatching { focus.awaitFocus() } }
            TvTextField(
                value = typed,
                onValueChange = { value ->
                    typed = value
                    onQuery(value.text)
                },
                hint = "Search YouTube",
                phoneLabel = "YouTube search",
                requester = focus,
                keyboardActions = KeyboardActions(onDone = { onSearch() }),
            )
        }
        if (recents.isNotEmpty() && typed.text.isBlank()) {
            item(key = "recents") {
                Column {
                    Text(
                        androidx.compose.ui.res
                            .stringResource(com.carteltv.tv.R.string.youtube_recent_searches),
                        fontSize = ThemeSpec.Type.meta,
                        color = YouTubeDim,
                        modifier = Modifier.padding(bottom = ThemeSpec.Space.sm),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LazyRow(Modifier.restoreRowFocus(), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md)) {
                        items(recents, key = { it }) { r ->
                            Pill(r, false) {
                                typed = TextFieldValue(r, TextRange(r.length))
                                onQuery(r)
                                onRecent(r)
                            }
                        }
                    }
                }
            }
        }
        if (results.isNotEmpty()) {
            item(key = "count") {
                SectionTitle(
                    androidx.compose.ui.res
                        .stringResource(com.carteltv.tv.R.string.youtube_results_count, results.size),
                    note = null,
                )
            }
        }
        items(results.distinctBy { it.videoId }, key = { it.videoId }) { v ->
            ResultRow(v, v.videoId in savedIds, onClick = { onPlay(v, results) }, onMenu = { onMenu(v) })
        }
        if (busy && results.isEmpty() && typed.text.isNotBlank()) item(key = "loading") { SkeletonResults() }
        if (results.isEmpty() && !busy) {
            item(key = "empty") {
                EmptyState(
                    notice ?: if (typed.text.isBlank()) "Search YouTube." else "No results for \"${typed.text}\"",
                    hint = if (typed.text.isBlank()) "Type with the remote or a paired phone." else "Try fewer words.",
                )
            }
        }
    }
}

@Composable
internal fun YouTubeMenu(
    v: Video,
    view: String,
    subscribed: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleSaved: () -> Unit,
    onRemoveHistory: () -> Unit,
    onChannel: (() -> Unit)?,
    onToggleSub: (() -> Unit)?,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { suspendRunCatching { first.awaitFocus() } }
    val saved = remember(v.videoId) { YouTubeLibrary.isSaved(v.videoId) }
    DialogFrame(v.title, byline(v), onDismiss) {
        DialogButton("Play", null, Modifier.focusRequester(first)) { onPlay() }
        DialogButton(if (saved) "Remove from saved" else "Save for later") { onToggleSaved() }
        if (onChannel != null) DialogButton("Go to channel", v.channel) { onChannel() }
        if (onToggleSub != null) DialogButton(if (subscribed) "Unsubscribe" else "Subscribe", v.channel) { onToggleSub() }
        if (view == "HISTORY") DialogButton("Remove from history") { onRemoveHistory() }
    }
}
