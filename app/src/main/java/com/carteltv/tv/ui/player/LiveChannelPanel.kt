package com.carteltv.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.R
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.GuideModel
import com.carteltv.tv.data.xtream.LiveChannels
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.ui.components.ClockFormat
import com.carteltv.tv.ui.components.FocusMemoryStore
import com.carteltv.tv.ui.components.TransientStatus
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.combinedClickableNoIndication
import com.carteltv.tv.ui.live.ChannelLogo
import com.carteltv.tv.ui.live.GroupChips
import com.carteltv.tv.ui.live.GuideData
import com.carteltv.tv.ui.live.TimelineGuide
import com.carteltv.tv.ui.live.channelsFor
import com.carteltv.tv.ui.settings.PinLatch
import com.carteltv.tv.ui.theme.PhosphorIcon
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.delay

/** The live layer over the picture: the channel list (OK) or the full guide (Left). The video keeps playing under both. */
internal enum class LiveOverlay { CHANNELS, GUIDE }

/** The list the viewer browses live TV through; it outlives one channel so the panel reopens where it was. */
internal object LiveBrowse {
    @Volatile var pick: String = "all"
}

/** The channel list closes on its own once the viewer stops using it, so it never sits over the picture forgotten. */
private const val PANEL_IDLE_MS = 15_000L

/** Walking the group rail rebuilds the channel list only once the cursor rests on a group. */
private const val RAIL_SETTLE_MS = 150L

/** The playing row may not be laid out on the first frame; the panel asks for it a few times before giving up. */
private const val FOCUS_ATTEMPTS = 10
private const val FOCUS_RETRY_MS = 48L

/** The provider's channels and groups as the browse layer needs them; the lists come from LiveChannels' cache when it is warm. */
@Composable
private fun rememberLiveLists(client: XtreamClient): Pair<List<XtreamClient.LiveStream>, List<XtreamClient.Category>> {
    val all by produceState(LiveChannels.cached(), client) {
        suspendRunCatching { LiveChannels.all(client) }.getOrNull()?.let { value = it }
    }
    val groups by produceState(emptyList<XtreamClient.Category>(), client) {
        suspendRunCatching { LiveChannels.groups(client) }.getOrNull()?.let { value = it }
    }
    return all to groups
}

@Composable
private fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * The channel list over the playing picture: groups on the left, the group's channels with what is on
 * now beside them. It opens on the channel playing; OK tunes, hold OK keeps it as a favourite, Right
 * opens the full guide and Back closes. Channel up/down then walks the list a channel was tuned from.
 */
@Composable
internal fun LiveChannelPanel(
    playingId: Long?,
    onTune: (XtreamClient.LiveStream) -> Unit,
    onGuide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val client = ProviderSession.current ?: return onDismiss()
    val ctx = LocalContext.current
    val (all, groups) = rememberLiveLists(client)
    val unlocked = PinLatch.isOpen("groups")
    val locked = remember { AppPreferences.channelGroupConfig().locked }
    var pick by remember { mutableStateOf(LiveBrowse.pick) }
    var railCursor by remember { mutableStateOf(pick) }
    LaunchedEffect(railCursor) {
        delay(RAIL_SETTLE_MS)
        pick = railCursor
        LiveBrowse.pick = railCursor
    }
    val channels = remember(all, groups, pick, unlocked) { channelsFor(pick, all, groups, unlocked) }
    val rail =
        remember(groups, unlocked) {
            buildList {
                add("all" to "All channels")
                add("fav" to "Favourites")
                add("recent" to "Recent")
                groups
                    .filter { unlocked || it.category_id !in locked }
                    .forEach { add(it.category_id to it.category_name) }
            }
        }
    val now = rememberNow()

    var touched by remember { mutableIntStateOf(0) }
    LaunchedEffect(touched) {
        delay(PANEL_IDLE_MS)
        onDismiss()
    }
    BackHandler { onDismiss() }

    val listState = rememberLazyListState()
    val entry = remember { FocusRequester() }
    val entryIndex = channels.indexOfFirst { it.streamId == playingId }.coerceAtLeast(0)
    var opened by remember { mutableStateOf(false) }
    // The rail cannot take focus until the list holds it, so the panel always opens on the channel playing.
    LaunchedEffect(channels.isNotEmpty()) {
        if (opened || channels.isEmpty()) return@LaunchedEffect
        listState.scrollToItem((entryIndex - 3).coerceAtLeast(0))
        repeat(FOCUS_ATTEMPTS) {
            if (suspendRunCatching { entry.awaitFocus() }.getOrDefault(false)) {
                opened = true
                return@LaunchedEffect
            }
            delay(FOCUS_RETRY_MS)
        }
        opened = true
    }
    LaunchedEffect(pick) { if (opened) listState.scrollToItem(0) }

    val shape = RoundedCornerShape(ThemeSpec.Card.cornerLg)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.72f), 0.65f to Color.Transparent))
            .onPreviewKeyEvent { closeOnBack(it, onDismiss) || run { if (it.type == KeyEventType.KeyDown) touched++; false } },
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .padding(start = ThemeSpec.Safe.horizontal, top = ThemeSpec.Safe.vertical, bottom = ThemeSpec.Safe.vertical)
                .playerGlass(shape, heavy = true)
                .padding(ThemeSpec.Space.sm),
        ) {
            LazyColumn(
                Modifier.width(196.dp).fillMaxHeight().focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(rail, key = { _, g -> g.first }) { _, (id, label) ->
                    PanelRow(
                        selected = id == pick,
                        modifier = Modifier.focusProperties { canFocus = opened },
                        onFocused = { if (opened) railCursor = id },
                        onClick = { railCursor = id },
                    ) { ink, _ ->
                        Text(
                            label,
                            fontSize = ThemeSpec.Type.label,
                            fontWeight = if (id == pick) FontWeight.SemiBold else FontWeight.Medium,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(Modifier.padding(horizontal = ThemeSpec.Space.sm).width(1.dp).fillMaxHeight().background(PlayerChrome.hairline))
            Column(Modifier.width(400.dp).fillMaxHeight()) {
                PanelHeader(rail.firstOrNull { it.first == pick }?.second ?: "Channels", channels.size)
                if (channels.isEmpty()) {
                    Text(
                        if (all.isEmpty()) "Loading channels…" else "Nothing in this group",
                        fontSize = ThemeSpec.Type.label,
                        color = PlayerChrome.inkDim,
                        modifier = Modifier.padding(ThemeSpec.Space.md),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth().focusRestorer(entry),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(channels, key = { _, s -> s.streamId }) { index, stream ->
                            GuideData.loadDetached(ctx, client, stream.streamId)
                            val slots = GuideData.slots[stream.streamId].orEmpty()
                            val slot = slots.firstOrNull { GuideModel.phase(it, now) == GuideModel.Phase.LIVE }
                            val next = slot?.let { live -> slots.firstOrNull { it.startMs >= live.stopMs } }
                            PanelRow(
                                selected = stream.streamId == playingId,
                                modifier =
                                    (if (index == entryIndex) Modifier.focusRequester(entry) else Modifier)
                                        .onPreviewKeyEvent {
                                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) {
                                                onGuide()
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                onLongClick = {
                                    AppPreferences.toggleFav("live:${stream.streamId}")
                                    val name = GuideModel.displayName(stream.name)
                                    TransientStatus.show(
                                        if (AppPreferences.isFav("live:${stream.streamId}")) "$name added to Favourites" else "$name removed from Favourites",
                                    )
                                },
                                onClick = {
                                    // Channel up/down after this walks the list the channel came from.
                                    ChannelTuning.zapList = channels
                                    onTune(stream)
                                },
                            ) { ink, focused ->
                                ChannelRowContent(
                                    stream = stream,
                                    number = channelNumberOf(all, stream).takeIf { it > 0 } ?: (index + 1),
                                    slot = slot,
                                    next = next,
                                    now = now,
                                    playing = stream.streamId == playingId,
                                    focused = focused,
                                    ink = ink,
                                )
                            }
                        }
                    }
                }
                PanelHints()
            }
        }
    }
}

/**
 * Back closes the layer. Compose turns an unclaimed Back press into a focus exit before BackHandler hears it,
 * which strands focus and leaves the layer up, so the layer claims the press itself: down is swallowed and up closes it.
 */
private fun closeOnBack(
    ev: androidx.compose.ui.input.key.KeyEvent,
    onDismiss: () -> Unit,
): Boolean {
    if (ev.key != Key.Back) return false
    if (ev.type == KeyEventType.KeyUp) onDismiss()
    return true
}

@Composable
private fun PanelHeader(
    label: String,
    count: Int,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = ThemeSpec.Type.title,
            fontFamily = ThemeSpec.Font.broadcast,
            fontWeight = FontWeight.Bold,
            color = PlayerChrome.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 0) {
            Text(count.toString(), fontSize = ThemeSpec.Type.label, fontFamily = ThemeSpec.Font.mono, color = PlayerChrome.inkDim)
        }
    }
}

@Composable
private fun PanelHints() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
    ) {
        listOf("OK Watch", "Hold OK Favourite", "▶ Guide").forEach {
            Text(it, fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkDim, maxLines = 1)
        }
    }
}

/** One focusable row of the panel. The focused row is the player's solid light pill; the playing or chosen one is a quiet well. */
@Composable
private fun PanelRow(
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable (ink: Color, focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
            .background(
                when {
                    focused -> PlayerChrome.focusFill
                    selected -> PlayerChrome.wellStrong
                    else -> Color.Transparent
                },
            ).onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }.combinedClickableNoIndication(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.sm),
    ) {
        content(if (focused) PlayerChrome.onFocusFill else PlayerChrome.ink, focused)
    }
}

@Composable
private fun ChannelRowContent(
    stream: XtreamClient.LiveStream,
    number: Int,
    slot: GuideModel.Slot?,
    next: GuideModel.Slot?,
    now: Long,
    playing: Boolean,
    focused: Boolean,
    ink: Color,
) {
    val muted = ink.copy(alpha = 0.68f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            number.toString(),
            modifier = Modifier.width(40.dp),
            fontSize = ThemeSpec.Type.label,
            fontFamily = ThemeSpec.Font.mono,
            color = muted,
            maxLines = 1,
        )
        ChannelLogo(stream, 34.dp)
        Spacer(Modifier.width(ThemeSpec.Space.cozy))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    GuideModel.displayName(stream.name),
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = ThemeSpec.Type.meta,
                    fontFamily = ThemeSpec.Font.broadcast,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playing) {
                    Spacer(Modifier.width(ThemeSpec.Space.sm))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(ThemeSemantics.live))
                }
            }
            // A channel without guide data is just its name: no placeholder line to read past.
            if (slot != null) {
                Text(
                    slot.title,
                    fontSize = ThemeSpec.Type.micro,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(CircleShape)
                        .background(ink.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(programmeProgress(now, slot.startMs, slot.stopMs))
                            .fillMaxHeight()
                            .background(if (focused) ink else ThemeSemantics.live),
                    )
                }
                // Detail only for the row in focus: when it ends and what follows.
                if (focused) {
                    Text(
                        buildString {
                            append(timeLeftLabel(slot.stopMs - now))
                            if (next != null) {
                                append("  ·  ")
                                append(ClockFormat.time(next.startMs, AppPreferences.clock24))
                                append("  ")
                                append(next.title)
                            }
                        },
                        modifier = Modifier.padding(top = 3.dp),
                        fontSize = ThemeSpec.Type.micro,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The full guide over the playing picture: the same grid as the Live TV page, opened on the channel
 * playing and the list the panel last showed. OK on a programme tunes its channel; Back returns to the picture.
 */
@Composable
internal fun LiveGuideOverlay(
    playingId: Long?,
    onTune: (XtreamClient.LiveStream) -> Unit,
    onDismiss: () -> Unit,
) {
    val client = ProviderSession.current ?: return onDismiss()
    val ctx = LocalContext.current
    val (all, groups) = rememberLiveLists(client)
    val unlocked = PinLatch.isOpen("groups")
    var pick by remember { mutableStateOf(LiveBrowse.pick) }
    val channels = remember(all, groups, pick, unlocked) { channelsFor(pick, all, groups, unlocked) }
    var selectedId by remember { mutableStateOf(playingId) }
    val zone = "player-guide:$pick"
    // The grid restores focus from memory; seeding it opens the guide on the channel playing.
    remember(zone) { playingId?.let { FocusMemoryStore.remember(zone, it.toString()) } }
    val now = rememberNow()
    BackHandler { onDismiss() }

    fun tune(stream: XtreamClient.LiveStream) {
        ChannelTuning.zapList = channels
        onTune(stream)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.80f), Color.Black.copy(alpha = 0.94f))))
            .onPreviewKeyEvent { closeOnBack(it, onDismiss) }
            .padding(top = ThemeSpec.Safe.vertical, bottom = ThemeSpec.Space.sm),
    ) {
        Row(
            Modifier.padding(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal, bottom = ThemeSpec.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhosphorIcon(R.drawable.ph_broadcast, ThemeSemantics.live, 16.dp)
            Spacer(Modifier.width(ThemeSpec.Space.sm))
            Text(
                "Guide",
                fontSize = ThemeSpec.Type.titleL,
                fontFamily = ThemeSpec.Font.broadcast,
                fontWeight = FontWeight.Bold,
                color = PlayerChrome.ink,
            )
        }
        GroupChips(groups, pick) { next ->
            pick = next
            LiveBrowse.pick = next
        }
        if (channels.isEmpty()) {
            Text(
                if (all.isEmpty()) "Loading channels…" else "Nothing in this group",
                fontSize = ThemeSpec.Type.label,
                color = PlayerChrome.inkDim,
                modifier = Modifier.padding(start = ThemeSpec.Space.margin, top = ThemeSpec.Space.md),
            )
        } else {
            TimelineGuide(
                channels = channels,
                selectedId = selectedId,
                zone = zone,
                now = now,
                client = client,
                ctx = ctx,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal, top = ThemeSpec.Space.sm),
                onSelect = { selectedId = it.streamId },
                onWatch = ::tune,
                onTune = { s, _ -> tune(s) },
                onDetails = { s, _ -> tune(s) },
            )
        }
    }
}
