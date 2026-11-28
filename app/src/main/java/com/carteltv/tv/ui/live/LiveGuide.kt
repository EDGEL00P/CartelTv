package com.carteltv.tv.ui.live

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.carteltv.tv.AppScope
import com.carteltv.tv.MultiViewActivity
import com.carteltv.tv.R
import com.carteltv.tv.alert.KickoffAlertScheduler
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.ChannelLogos
import com.carteltv.tv.data.xtream.EpgIndexDatabase
import com.carteltv.tv.data.xtream.GuideModel
import com.carteltv.tv.data.xtream.LiveChannels
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.recording.RecordingScheduler
import com.carteltv.tv.recording.RecordingService
import com.carteltv.tv.ui.catalog.NeedProviderNotice
import com.carteltv.tv.ui.components.ClockFormat
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.FilterChip
import com.carteltv.tv.ui.components.FocusMemoryStore
import com.carteltv.tv.ui.components.PageError
import com.carteltv.tv.ui.components.PageLoading
import com.carteltv.tv.ui.components.StagedScreen
import com.carteltv.tv.ui.components.TransientStatus
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.combinedClickableNoIndication
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.components.keyFocusTarget
import com.carteltv.tv.ui.components.rememberKeyFocusRegistry
import com.carteltv.tv.ui.components.restoreKeyFocus
import com.carteltv.tv.ui.nav.PageChipRow
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.sports.DialogButton
import com.carteltv.tv.ui.sports.DialogFrame
import com.carteltv.tv.ui.theme.GenreTones
import com.carteltv.tv.ui.theme.PhosphorIcon
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusRing
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.theme.scaleAlpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal fun hhmm(ms: Long): String = ClockFormat.time(ms, clock24 = true)

/** Dense rows lift vertically only, so their focus rings stay inside the guide's clipped panels. */
@Composable
private fun Modifier.focusLiftRow(scale: State<Float>): Modifier = graphicsLayer { scaleY = scale.value }

/** Programme listings per channel, fetched lazily as rows scroll into view, a few at a time. */
internal object GuideData {
    private const val FRESH_MS = 15 * 60_000L
    val slots = mutableStateMapOf<Long, List<GuideModel.Slot>>()
    private val stamp = ConcurrentHashMap<Long, Long>()
    private val gate = Semaphore(4)
    private val inFlight = ConcurrentHashMap<Long, Job>()

    /** Row loads outlive the grid item that started them, so scrolling cannot cancel a fetch mid-way. */
    fun loadDetached(
        ctx: android.content.Context,
        client: XtreamClient,
        id: Long,
    ) {
        if (inFlight[id]?.isActive == true) return
        inFlight[id] =
            AppScope.io.launch(com.carteltv.tv.engine.stability.BackgroundWorkScheduler.quiet) {
                try {
                    load(ctx, client, id)
                } finally {
                    inFlight.remove(id)
                }
            }
    }

    fun cancelLoads() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    suspend fun load(
        ctx: android.content.Context,
        client: XtreamClient,
        id: Long,
    ) {
        val now = System.currentTimeMillis()
        if (slots.containsKey(id) && now - (stamp[id] ?: 0L) < FRESH_MS) return
        val stored =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { EpgIndexDatabase.get(ctx).programsFor(id, now - 24 * 60 * 60_000L) }.getOrDefault(emptyList())
            }
        if (stored.any { (it.stopTs?.toLongOrNull() ?: 0L) * 1000L > now }) {
            slots[id] = GuideModel.slots(stored)
            stamp[id] = now
            return
        }
        // Prefill with a cheap short-EPG pull so the row paints now/next immediately;
        // the full table replaces it and is the only copy persisted.
        if (!slots.containsKey(id)) {
            val quick =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    suspendRunCatching { client.shortEpg(id, 12) }.getOrNull()
                }
            quick?.takeIf { it.isNotEmpty() }?.let { slots[id] = GuideModel.slots(it) }
        }
        gate.withPermit {
            val entries =
                kotlinx.coroutines
                    .withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            client.epgTable(id)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }.orEmpty()
            slots[id] = GuideModel.slots(entries)
            stamp[id] = System.currentTimeMillis()
            if (entries.isNotEmpty()) {
                AppScope.io.launch(com.carteltv.tv.engine.stability.BackgroundWorkScheduler.quiet) {
                    runCatching { EpgIndexDatabase.get(ctx).replaceProgramsForStream(id, entries) }
                }
            }
        }
    }
}

internal fun channelsFor(
    pick: String,
    all: List<XtreamClient.LiveStream>,
    groups: List<XtreamClient.Category> = emptyList(),
    groupsUnlocked: Boolean = false,
): List<XtreamClient.LiveStream> {
    // PIN-locked groups stay out of the browsing lists until the session unlocked them; a direct
    // saved link (LiveChannels.byId) still plays, same rule as hidden groups.
    val locked =
        com.carteltv.tv.data.preferences.AppPreferences
            .channelGroupConfig()
            .locked
    val visible = { s: XtreamClient.LiveStream -> groupsUnlocked || s.categoryId !in locked }
    return when {
        // Channels that carry a guide come first: a grid that opens on rows of "No guide data" reads as broken.
        pick == "all" -> {
            all
                .filter {
                    visible(it) &&
                        !com.carteltv.tv.data.xtream.EventSlots
                            .isEmpty(it.name) &&
                        !com.carteltv.tv.data.xtream.NoticeChannels
                            .isNotice(it.name)
                }.sortedByDescending { !it.epgId.isNullOrBlank() }
        }

        pick == "fav" -> {
            AppPreferences.favIds().let { favs -> all.filter { "live:${it.streamId}" in favs && visible(it) } }
        }

        pick == "events" -> {
            com.carteltv.tv.data.xtream.EventSlots
                .schedule(all, System.currentTimeMillis())
                .filter { visible(it) }
        }

        pick == "recent" -> {
            AppPreferences.recentLive().let { ids ->
                if (ids.isEmpty()) {
                    emptyList()
                } else {
                    val byId = all.associateBy { it.streamId }
                    ids.mapNotNull { byId[it] }
                }
            }
        }

        pick.startsWith("cat:") -> {
            val ids = GuideCategories.parsePick(pick)?.let { (key, region) -> GuideCategories.idsFor(key, groups, region) }
            if (ids == null) {
                emptyList()
            } else {
                all
                    .filter {
                        it.categoryId in ids && visible(it) &&
                            !com.carteltv.tv.data.xtream.EventSlots
                                .isEmpty(it.name) &&
                            !com.carteltv.tv.data.xtream.NoticeChannels
                                .isNotice(it.name)
                    }.sortedByDescending { !it.epgId.isNullOrBlank() }
            }
        }

        else -> {
            all.filter {
                it.categoryId == pick && visible(it) &&
                    !com.carteltv.tv.data.xtream.EventSlots
                        .isEmpty(it.name) &&
                    !com.carteltv.tv.data.xtream.NoticeChannels
                        .isNotice(it.name)
            }
        }
    }
}

@Composable
internal fun GroupChips(
    groups: List<XtreamClient.Category>,
    pick: String,
    trailing: @Composable () -> Unit = {},
    onPick: (String) -> Unit,
) {
    // A chip whose every group carries the parent PIN asks for it first (XCIPTV-style group lock);
    // unlocking once opens all locked groups for the session.
    val lockedIds =
        com.carteltv.tv.data.preferences.AppPreferences
            .channelGroupConfig()
            .locked
    val unlocked =
        com.carteltv.tv.ui.settings.PinLatch
            .isOpen("groups")
    var pendingLock by remember { mutableStateOf<Pair<String, String>?>(null) } // pick to label

    fun request(
        p: String,
        label: String,
    ) {
        if (p == "all" || p == "fav" || p == "events" || p == "recent") {
            onPick(p)
            return
        }
        val ids = GuideCategories.parsePick(p)?.let { (key, region) -> GuideCategories.idsFor(key, groups, region) } ?: setOf(p)
        if (!unlocked && ids.isNotEmpty() && ids.all { it in lockedIds }) {
            pendingLock = p to label
        } else {
            onPick(p)
        }
    }
    pendingLock?.let { (p, label) ->
        com.carteltv.tv.ui.settings.PinGate(
            title = label,
            onOk = { pin ->
                if (com.carteltv.tv.data.preferences.AppPreferences
                        .settingsPinVerify(pin)
                ) {
                    com.carteltv.tv.ui.settings.PinLatch
                        .open("groups")
                    pendingLock = null
                    onPick(p)
                    true
                } else {
                    false
                }
            },
            onCancel = { pendingLock = null },
        )
        return
    }
    // The standard chip row, so the guide's filters sit at the same gutter and gap as every other page's.
    PageChipRow {
        item {
            FilterChip(
                androidx.compose.ui.res
                    .stringResource(com.carteltv.tv.R.string.filter_all),
                pick == "all",
                icon = R.drawable.ph_squares_four,
            ) {
                request("all", "")
            }
        }
        item {
            FilterChip(
                androidx.compose.ui.res
                    .stringResource(com.carteltv.tv.R.string.filter_favourites),
                pick == "fav",
                icon = R.drawable.ph_bookmark_simple,
            ) {
                request("fav", "")
            }
        }
        item {
            FilterChip(
                androidx.compose.ui.res
                    .stringResource(com.carteltv.tv.R.string.filter_live_events),
                pick == "events",
                icon = R.drawable.ph_basketball,
            ) {
                request("events", "")
            }
        }
        GuideCategories.present(groups).forEach { b ->
            item(key = b.key) {
                val gated = !unlocked && GuideCategories.idsFor(b.key, groups).let { it.isNotEmpty() && it.all { id -> id in lockedIds } }
                FilterChip(
                    if (gated) "PIN " + b.label else b.label,
                    GuideCategories.parsePick(pick)?.first == b.key,
                    icon = bucketIcon(b.key),
                ) {
                    request("cat:${b.key}", b.label)
                }
            }
        }
        item { trailing() }
    }
    // Second row: when the chosen category spans several regions (US / UK ...), they get their own sub-nav.
    val (bucket, region) = GuideCategories.parsePick(pick) ?: return
    val regions = GuideCategories.regionsFor(bucket, groups)
    if (regions.isEmpty()) return
    val label = GuideCategories.all.first { it.key == bucket }.label
    PageChipRow {
        item { FilterChip("All $label", region == null, icon = R.drawable.ph_squares_four) { request("cat:$bucket", label) } }
        regions.forEach { r ->
            item(key = "$bucket:$r") {
                val gated =
                    !unlocked && GuideCategories.idsFor(bucket, groups, r).let { it.isNotEmpty() && it.all { id -> id in lockedIds } }
                FilterChip(
                    if (gated) "PIN $r" else r,
                    region == r,
                    icon = R.drawable.ph_navigation_arrow,
                ) { request("cat:$bucket:$r", "$label $r") }
            }
        }
    }
}

/** The Phosphor mark for a category chip; every bucket gets one so no chip relies on text alone. */
private fun bucketIcon(key: String): Int =
    when (key) {
        "kids" -> R.drawable.ph_game_controller
        "sports" -> R.drawable.ph_basketball
        "news" -> R.drawable.ph_broadcast
        "movies" -> R.drawable.ph_film_strip
        "docs" -> R.drawable.ph_clock
        "music" -> R.drawable.ph_music_notes
        else -> R.drawable.ph_play_circle
    }

@Composable
internal fun QualityBadge(name: String) {
    val q = GuideModel.quality(name) ?: return
    Text(
        q,
        fontSize = ThemeSpec.Type.label,
        fontWeight = FontWeight.Bold,
        color = if (q == "4K" || q == "8K") ThemeSemantics.accent else ThemeSemantics.textSecondary,
        modifier =
            Modifier
                .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                .background(ThemeSemantics.surfaceRest)
                .padding(horizontal = ThemeSpec.Space.sm, vertical = 1.dp),
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

@Composable
internal fun ChannelLogo(
    stream: XtreamClient.LiveStream,
    size: Dp,
) = NameLogo(stream.name, size, stream.icon)

/** A real logo for a channel or network by name (bundled catalogue first, [fallbackIcon] if there is none); [plain] drops the panel behind it. */
@Composable
internal fun NameLogo(
    name: String,
    size: Dp,
    fallbackIcon: String? = null,
    plain: Boolean = false,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ChannelLogos.ensureLoaded(ctx) }
    val logoValue = if (ChannelLogos.loaded) ChannelLogos.urlFor(name) else null
    val packedId = logoValue?.takeIf { it.startsWith("#") }?.drop(1)?.toIntOrNull()
    val packedBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, packedId) {
        value = packedId?.let { id -> withContext(Dispatchers.IO) { ChannelLogos.packed(ctx, id) } }
    }
    val catalogue = if (packedId == null) logoValue else null
    var catalogueFailed by remember(catalogue) { mutableStateOf(false) }
    val url = if (catalogue != null && !catalogueFailed) catalogue else fallbackIcon
    Box(
        Modifier
            .size(
                size,
            ).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
            .background(if (plain) Color.Transparent else ThemeSemantics.surfaceRest),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = packedBitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.size(size - 6.dp),
            )
        } else if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.size(size - 6.dp),
                onError = { if (url == catalogue) catalogueFailed = true },
            )
        }
    }
}

/** Tune, catch up, record or set an alert for one programme on one channel. */
@Composable
internal fun ProgramSheet(
    stream: XtreamClient.LiveStream,
    slot: GuideModel.Slot,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val client =
        ProviderSession.current ?: run {
            onDismiss()
            return
        }
    val now = System.currentTimeMillis()
    val phase = GuideModel.phase(slot, now)
    val name = GuideModel.displayName(stream.name)
    val alertTitle = "$name \u00b7 ${slot.title}"
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { suspendRunCatching { first.awaitFocus() } }

    fun toast(msg: String) = TransientStatus.show(msg)

    fun tune() {
        AppLauncher.player(ctx, client.liveUrl(stream.streamId), stream.name, "live:${stream.streamId}")
        onDismiss()
    }

    fun fromStart(end: Long) {
        AppLauncher.player(ctx, client.timeshiftUrl(stream.streamId, slot.startMs, end), stream.name, "live:${stream.streamId}")
        onDismiss()
    }
    // Replay works only on channels the server actually archives; outside the window the archive copy is gone too.
    val catchupDays = stream.catchupDays
    val inCatchupWindow = catchupDays > 0 && now - slot.stopMs <= catchupDays * 24 * 3600_000L

    val fav = remember(tick) { AppPreferences.isFav("live:${stream.streamId}") }
    val alerted = remember(tick) { KickoffAlertScheduler.hasAlert(ctx, "guide", alertTitle) }
    // The slot's time and channel are the sheet's plate line; the blurb is long-form copy under it.
    val slotLine = "${hhmm(slot.startMs)} \u2013 ${hhmm(slot.stopMs)} \u00b7 $name"
    val blurb = slot.description?.takeIf { it.isNotBlank() }?.take(160)

    DialogFrame(slot.title, slotLine, onDismiss, message = blurb) {
        var firstDone = false

        @Composable fun DialogAction(
            label: String,
            icon: Int,
            detail: String? = null,
            onClick: () -> Unit,
        ) {
            DialogButton(label, detail, if (!firstDone) Modifier.focusRequester(first) else Modifier, icon = icon, onClick = onClick)
            firstDone = true
        }
        when (phase) {
            GuideModel.Phase.LIVE -> {
                DialogAction("Tune in", R.drawable.ph_play, GuideModel.leftLabel(slot, now)) { tune() }
                if (catchupDays > 0 &&
                    now - slot.startMs >= 60_000L
                ) {
                    DialogAction("Watch from the start", R.drawable.ph_clock_counter_clockwise) { fromStart(now) }
                }
                DialogAction("Record the rest", R.drawable.ph_record) {
                    val pad = AppPreferences.recPadAfterMin * 60_000L
                    RecordingService.start(
                        ctx.applicationContext,
                        client.liveUrl(stream.streamId),
                        alertTitle,
                        durationMs = (slot.stopMs - now + pad).coerceAtLeast(60_000L),
                    )
                    toast("Recording started: ${slot.title}")
                    onDismiss()
                }
            }

            GuideModel.Phase.PAST -> {
                if (inCatchupWindow) {
                    DialogAction(
                        "Watch from the start",
                        R.drawable.ph_clock_counter_clockwise,
                        "$catchupDays day${if (catchupDays == 1) "" else "s"} of catch-up",
                    ) {
                        fromStart(slot.stopMs)
                    }
                } else {
                    DialogAction("Tune in to the channel", R.drawable.ph_play, "No catch-up on this channel") { tune() }
                }
            }

            GuideModel.Phase.FUTURE -> {
                DialogAction(
                    if (alerted) KickoffAlertScheduler.CANCEL_TEXT else KickoffAlertScheduler.ARM_TEXT,
                    if (alerted) R.drawable.ph_x else R.drawable.ph_lightning,
                ) {
                    if (alerted) {
                        KickoffAlertScheduler.cancelAlert(ctx, "guide", alertTitle)
                    } else if (!KickoffAlertScheduler.armAlert(
                            ctx,
                            "guide",
                            alertTitle,
                            slot.startMs,
                            null,
                            client.liveUrl(stream.streamId),
                            name,
                        )
                    ) {
                        toast("Too close to the start to set an alert")
                    }
                    tick++
                }
                DialogAction("Record this programme", R.drawable.ph_record) {
                    RecordingScheduler.schedule(
                        ctx.applicationContext,
                        alertTitle,
                        client.liveUrl(stream.streamId),
                        slot.startMs,
                        (slot.stopMs - slot.startMs).coerceAtLeast(60_000L),
                    )
                    toast("Scheduled for ${hhmm(slot.startMs)}")
                    onDismiss()
                }
                DialogAction("Tune in to the channel", R.drawable.ph_play) { tune() }
            }
        }
        if (phase != GuideModel.Phase.PAST) {
            DialogAction("Record the whole series", R.drawable.ph_record) {
                // Series link: schedule every future broadcast of this programme title on this channel.
                val channelName = name
                val needle = GuideModel.cleanTitle(slot.title).lowercase()
                val url = client.liveUrl(stream.streamId)
                com.carteltv.tv.AppScope.io.launch(com.carteltv.tv.engine.stability.BackgroundWorkScheduler.quiet) {
                    val now = System.currentTimeMillis()
                    val upcoming =
                        suspendRunCatching { client.epgTable(stream.streamId) }
                            .getOrNull()
                            .orEmpty()
                            .mapNotNull { e ->
                                val start = e.startTs?.toLongOrNull()?.let { it * 1000L } ?: return@mapNotNull null
                                val stop = (e.stopTs?.toLongOrNull() ?: 0L) * 1000L
                                Triple(e, start, stop)
                            }.filter { (e, start, stop) ->
                                start > now && stop > start &&
                                    GuideModel.cleanTitle(e.title?.trim().orEmpty()).lowercase() == needle
                            }.sortedBy { it.second }
                            .take(40) // a bound on runaway schedules from broad titles
                    for ((e, start, stop) in upcoming) {
                        RecordingScheduler.schedule(
                            ctx.applicationContext,
                            "$channelName \u00b7 ${e.title?.trim().orEmpty()}",
                            url,
                            start,
                            (stop - start).coerceAtLeast(60_000L),
                        )
                    }
                }
                toast("Series recording scheduled: ${slot.title}")
                onDismiss()
            }
        }
        DialogAction(
            if (fav) "Remove from favourites" else "Add to favourites",
            if (fav) R.drawable.ph_bookmark_simple_fill else R.drawable.ph_bookmark_simple,
        ) {
            AppPreferences.toggleFav("live:${stream.streamId}")
            tick++
        }
    }
}

private val GenreSports = GenreTones.sports
private val GenreNews = GenreTones.news
private val GenreMovies = GenreTones.movies
private val GenreNature = GenreTones.nature
private val GenreKids = GenreTones.kids

private val SPORT_WORDS =
    listOf(
        " vs ",
        " vs. ",
        " v ",
        "nfl",
        "nba",
        "nhl",
        "mlb",
        "ufc",
        "football",
        "soccer",
        "basketball",
        "hockey",
        "baseball",
        "golf",
        "tennis",
        "racing",
        "formula",
        "boxing",
        "wrestling",
        "sport",
        "league",
        "premier",
        "fifa",
        "espn",
    )
private val NEWS_WORDS = listOf("news", "weather", "politic", "headline", "the brief", "world today")
private val MOVIE_WORDS = listOf("movie", "film", "cinema")
private val NATURE_WORDS = listOf("documentary", "nature", "wildlife", "planet", "ocean", "history", "science", "earth")
private val KIDS_WORDS = listOf("kids", "cartoon", "animation", "toon", "children", "junior")

private enum class GuideGenre { KIDS, SPORTS, NEWS, MOVIES, NATURE }

/** The genre of a programme, read from its title and blurb; null when nothing says what it is. */
private fun genreOf(
    title: String,
    description: String?,
): GuideGenre? {
    val text = " ${title.lowercase()} ${description.orEmpty().lowercase().take(120)} "
    return when {
        KIDS_WORDS.any { it in text } -> GuideGenre.KIDS
        SPORT_WORDS.any { it in text } -> GuideGenre.SPORTS
        NEWS_WORDS.any { it in text } -> GuideGenre.NEWS
        MOVIE_WORDS.any { it in text } -> GuideGenre.MOVIES
        NATURE_WORDS.any { it in text } -> GuideGenre.NATURE
        else -> null
    }
}

/** The colour family of a programme; labels are derived from the genre, never from equal colour values. */
internal fun genreTint(
    title: String,
    description: String?,
): Color? =
    when (genreOf(title, description)) {
        GuideGenre.KIDS -> GenreKids
        GuideGenre.SPORTS -> GenreSports
        GuideGenre.NEWS -> GenreNews
        GuideGenre.MOVIES -> GenreMovies
        GuideGenre.NATURE -> GenreNature
        null -> null
    }

internal fun categoryLabel(
    title: String,
    description: String?,
): String? = genreOf(title, description)?.name

@Composable
internal fun GuideContent() {
    val ctx = LocalContext.current
    val currentClient by ProviderSession.currentFlow.collectAsStateWithLifecycle()
    val client = currentClient
    ScreenPage {
        StagedScreen(section = com.carteltv.tv.nav.AppSection.GUIDE) {
            // The frame draws the masthead; with no provider the notice is the one thing on the page.
            if (client == null) {
                NeedProviderNotice()
                return@StagedScreen
            }
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { GuideData.cancelLoads() } }

            var all by remember { mutableStateOf(listOf<XtreamClient.LiveStream>()) }
            var channelsLoaded by remember { mutableStateOf(false) }
            var groups by remember { mutableStateOf(listOf<XtreamClient.Category>()) }
            var pick by remember { mutableStateOf("all") }
            var sheet by remember { mutableStateOf<Pair<XtreamClient.LiveStream, GuideModel.Slot>?>(null) }
            var selectedChannelId by remember { mutableStateOf<Long?>(null) }
            var guideRetryTick by remember { mutableIntStateOf(0) }
            val started =
                com.carteltv.tv.ui.components
                    .rememberAppStarted()
            LaunchedEffect(started, client, guideRetryTick) {
                all = emptyList()
                channelsLoaded = false
                groups = emptyList()
                pick = "all"
                sheet = null
                selectedChannelId = null
                if (!started) return@LaunchedEffect
                groups = LiveChannels.groups(client)
                var first = true
                while (true) {
                    // The screen is composed for one provider; when the viewer switches, stop
                    // refreshing the old one rather than mixing lists from two accounts.
                    if (com.carteltv.tv.data.xtream.ProviderSession.current != client) break
                    val fresh = LiveChannels.all(client, force = !first || guideRetryTick > 0)
                    if (ProviderSession.current !== client) break
                    all = fresh
                    channelsLoaded = true
                    first = false
                    if (all.isNotEmpty()) {
                        val snapshot = all
                        AppScope.io.launch(com.carteltv.tv.engine.stability.BackgroundWorkScheduler.quiet) {
                            runCatching { EpgIndexDatabase.get(ctx).upsertChannels(snapshot) }
                            runCatching { EpgIndexDatabase.get(ctx).replaceEventSlots(snapshot) }
                            suspendRunCatching {
                                com.carteltv.tv.data.xtream.EpgSync
                                    .refreshIfDue(ctx, client)
                            }
                        }
                    }
                    delay(10 * 60_000L)
                }
            }

            val groupsUnlocked =
                com.carteltv.tv.ui.settings.PinLatch
                    .isOpen("groups")
            val shown = remember(all, pick, groups, groupsUnlocked) { channelsFor(pick, all, groups, groupsUnlocked) }
            LaunchedEffect(shown, selectedChannelId) {
                if (shown.isEmpty()) {
                    selectedChannelId = null
                    return@LaunchedEffect
                }
                if (selectedChannelId == null || shown.none { it.streamId == selectedChannelId }) {
                    selectedChannelId = shown.first().streamId
                }
            }

            val selected =
                remember(shown, selectedChannelId) {
                    shown.firstOrNull { it.streamId == selectedChannelId }
                        ?: shown.firstOrNull()
                }
            val now by produceState(System.currentTimeMillis()) {
                while (true) {
                    delay(30_000L)
                    value = System.currentTimeMillis()
                }
            }

            fun watch(s: XtreamClient.LiveStream) {
                AppLauncher.player(ctx, client.liveUrl(s.streamId), s.name, "live:${s.streamId}")
            }

            // Live TV opens as a television does: on the last channel, full screen. The guide and channel
            // list come up over the picture; Back from the picture lands on this page, which stays put.
            var autoTuned by androidx.compose.runtime.saveable.rememberSaveable(client) { mutableStateOf(false) }
            LaunchedEffect(channelsLoaded) {
                if (autoTuned || !channelsLoaded) return@LaunchedEffect
                autoTuned = true
                val channel =
                    AppPreferences.recentLive().firstNotNullOfOrNull { id -> all.firstOrNull { it.streamId == id } }
                        ?: shown.firstOrNull()
                channel?.let(::watch)
            }

            fun openMultiview() {
                val s = selected ?: shown.firstOrNull() ?: return
                ctx.startActivity(
                    Intent(ctx, MultiViewActivity::class.java)
                        .putExtra("url", client.liveUrl(s.streamId))
                        .putExtra("title", s.name)
                        .putExtra("refId", "live:${s.streamId}")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            Column(Modifier.fillMaxSize()) {
                ProviderStatusBanner()
                GroupChips(groups, pick, trailing = {
                    FilterChip("Multiview", selected = false, icon = R.drawable.ph_monitor_play) { openMultiview() }
                }) { newPick ->
                    FocusMemoryStore.remember("live-guide:$pick", selectedChannelId?.toString())
                    pick = newPick
                    selectedChannelId = FocusMemoryStore.peek("live-guide:$newPick")?.toLongOrNull()
                }

                if (shown.isEmpty()) {
                    if (!channelsLoaded) {
                        PageLoading()
                    } else if (all.isEmpty() && LiveChannels.refreshFailed) {
                        // A dead provider must not read as an empty playlist; the loop retries on its own.
                        val providerName = AppPreferences.activeProvider()?.name ?: "Your provider"
                        PageError(
                            "$providerName could not be reached.",
                            retryLabel = "Retry",
                            onRetry = { guideRetryTick++ },
                        )
                    } else {
                        when (pick) {
                            "fav" -> {
                                EmptyState(
                                    stringResource(R.string.empty_no_favourites),
                                    hint = stringResource(R.string.guide_fav_hint),
                                    icon = R.drawable.ph_bookmark_simple,
                                )
                            }

                            "events" -> {
                                EmptyState("No live events on right now", icon = R.drawable.ph_basketball)
                            }

                            "recent" -> {
                                EmptyState(
                                    stringResource(R.string.empty_nothing_watched),
                                    hint = stringResource(R.string.empty_nothing_watched_hint),
                                    icon = R.drawable.ph_clock_counter_clockwise,
                                )
                            }

                            else -> {
                                EmptyState(
                                    stringResource(R.string.live_no_group),
                                    hint = stringResource(R.string.live_no_group_hint),
                                    icon = R.drawable.ph_broadcast,
                                )
                            }
                        }
                    }
                } else {
                    TimelineGuide(
                        channels = shown,
                        selectedId = selectedChannelId,
                        zone = "live-guide:$pick",
                        now = now,
                        client = client,
                        ctx = ctx,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(
                                    start = ThemeSpec.Space.margin,
                                    end = ThemeSpec.Safe.horizontal,
                                    top = ThemeSpec.Space.sm,
                                    bottom = ThemeSpec.Space.sm,
                                ),
                        onSelect = { selectedChannelId = it.streamId },
                        onWatch = { watch(it) },
                        onTune = { s, slot -> if (GuideModel.phase(slot, now) == GuideModel.Phase.PAST) sheet = s to slot else watch(s) },
                        onDetails = { s, slot -> sheet = s to slot },
                    )
                }
            }
            sheet?.let { (s, slot) -> ProgramSheet(s, slot, onDismiss = { sheet = null }) }
        }
    }
}

private const val GUIDE_WINDOW_BEFORE_MS = 30L * 60_000L
private const val GUIDE_WINDOW_AFTER_MS = 330L * 60_000L
private const val GUIDE_HOUR_MS = 60L * 60_000L
private val GUIDE_MINUTE_WIDTH = 1.7.dp
private val GUIDE_CHANNEL_WIDTH = ThemeSpec.Layout.guideChannelWidth
private val GUIDE_ROW_HEIGHT = ThemeSpec.Layout.guideRowHeight
private val GUIDE_TIMELINE_WIDTH = GUIDE_MINUTE_WIDTH * 360f

private fun guideOffset(
    ms: Long,
    windowStart: Long,
): Dp = GUIDE_MINUTE_WIDTH * ((ms - windowStart) / 60_000f)

private fun guideWidth(durationMs: Long): Dp = GUIDE_MINUTE_WIDTH * (durationMs / 60_000f)

/** A conventional EPG: fixed channels, one shared horizontal clock and independently loaded rows. */
@Composable
internal fun TimelineGuide(
    channels: List<XtreamClient.LiveStream>,
    selectedId: Long?,
    zone: String,
    now: Long,
    client: XtreamClient,
    ctx: android.content.Context,
    modifier: Modifier,
    onSelect: (XtreamClient.LiveStream) -> Unit,
    onWatch: (XtreamClient.LiveStream) -> Unit,
    onTune: (XtreamClient.LiveStream, GuideModel.Slot) -> Unit,
    onDetails: (XtreamClient.LiveStream, GuideModel.Slot) -> Unit,
) {
    val timelineState = rememberScrollState()
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val focusRegistry = rememberKeyFocusRegistry()
    val channelKeys = remember(channels) { channels.map { it.streamId.toString() } }
    val hasRestorableFocus = FocusMemoryStore.peek(zone) in channelKeys
    LaunchedEffect(zone, channelKeys) {
        restoreKeyFocus(zone, channelKeys, focusRegistry) { listState.scrollToItem(it) }
    }
    val windowStart = now - GUIDE_WINDOW_BEFORE_MS
    val windowEnd = now + GUIDE_WINDOW_AFTER_MS
    var focusedProgram by remember { mutableStateOf<Pair<Long, GuideModel.Slot>?>(null) }
    val selected = channels.firstOrNull { it.streamId == selectedId } ?: channels.first()
    val selectedSlots = GuideData.slots[selected.streamId].orEmpty()
    val summarySlot =
        focusedProgram?.takeIf { it.first == selected.streamId }?.second
            ?: selectedSlots.firstOrNull { GuideModel.phase(it, now) == GuideModel.Phase.LIVE }
            ?: selectedSlots.firstOrNull { it.stopMs > now }

    Column(modifier) {
        GuideFocusSummary(selected, summarySlot, now, programFocused = focusedProgram?.first == selected.streamId)
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        GuideTimelineHeader(windowStart, windowEnd, now, timelineState)
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                    .background(ThemeSemantics.surfaceRest)
                    .focusRestorer(firstFocus)
                    .focusGroup(),
        ) {
            itemsIndexed(channels, key = { _, stream -> stream.streamId }) { index, stream ->
                GuideTimelineRow(
                    stream = stream,
                    rowNumber = index + 1,
                    selected = stream.streamId == selected.streamId,
                    now = now,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    client = client,
                    ctx = ctx,
                    timelineState = timelineState,
                    modifier =
                        Modifier
                            .keyFocusTarget(focusRegistry, zone, stream.streamId.toString())
                            .then(
                                if (index == 0) {
                                    Modifier.entryFocus(key = zone, enabled = !hasRestorableFocus, requester = firstFocus)
                                } else {
                                    Modifier
                                },
                            ),
                    onChannelFocus = {
                        focusedProgram = null
                        onSelect(stream)
                    },
                    onProgramFocus = { slot ->
                        focusedProgram = stream.streamId to slot
                        onSelect(stream)
                    },
                    onWatch = { onWatch(stream) },
                    onTune = { slot -> onTune(stream, slot) },
                    onDetails = { slot -> onDetails(stream, slot) },
                )
            }
        }
    }
}

@Composable
private fun GuideFocusSummary(
    stream: XtreamClient.LiveStream,
    slot: GuideModel.Slot?,
    now: Long,
    programFocused: Boolean,
) {
    val phase = slot?.let { GuideModel.phase(it, now) }
    val shape = RoundedCornerShape(ThemeSpec.Card.cornerSm)
    Row(
        Modifier
            .fillMaxWidth()
            .height(ThemeSpec.Layout.guideSummaryHeight)
            .clip(shape)
            .background(ThemeSemantics.surfaceRaised)
            .border(ThemeSpec.Focus.ringWidth, ThemeSemantics.hairline, shape)
            .padding(horizontal = ThemeSpec.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(stream, 36.dp)
        Spacer(Modifier.width(ThemeSpec.Space.cozy))
        Column(Modifier.width(GUIDE_CHANNEL_WIDTH - 56.dp)) {
            Text(
                GuideModel.displayName(stream.name),
                fontSize = ThemeSpec.Type.meta,
                fontFamily = ThemeSpec.Font.broadcast,
                fontWeight = FontWeight.SemiBold,
                color = ThemeSemantics.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QualityBadge(stream.name)
        }
        Spacer(Modifier.width(ThemeSpec.Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                slot?.title ?: "Guide data unavailable",
                fontSize = ThemeSpec.Type.title,
                fontFamily = ThemeSpec.Font.broadcast,
                fontWeight = FontWeight.SemiBold,
                color = if (slot == null) ThemeSemantics.textSecondary else ThemeSemantics.textEmphatic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(ThemeSpec.Space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (phase == GuideModel.Phase.LIVE) {
                    PhosphorIcon(R.drawable.ph_broadcast, ThemeSemantics.live, 13.dp)
                    Spacer(Modifier.width(ThemeSpec.Space.xs))
                    Text(
                        "LIVE",
                        fontSize = ThemeSpec.Type.label,
                        fontFamily = ThemeSpec.Font.broadcast,
                        fontWeight = FontWeight.SemiBold,
                        color = ThemeSemantics.textEmphatic,
                        letterSpacing = ThemeSpec.Type.trackCaps,
                    )
                    Spacer(Modifier.width(ThemeSpec.Space.sm))
                }
                Text(
                    when {
                        slot == null -> "Select a channel to tune in"

                        phase == GuideModel.Phase.LIVE -> "${hhmm(
                            slot.startMs,
                        )} – ${hhmm(slot.stopMs)}  ·  ${GuideModel.leftLabel(slot, now)}"

                        else -> "${hhmm(slot.startMs)} – ${hhmm(slot.stopMs)}"
                    },
                    fontSize = ThemeSpec.Type.label,
                    fontFamily = ThemeSpec.Font.mono,
                    color = if (phase == GuideModel.Phase.LIVE) ThemeSemantics.accent else ThemeSemantics.textSecondary,
                    maxLines = 1,
                )
            }
            slot?.description?.takeIf { programFocused && it.isNotBlank() }?.let { description ->
                Spacer(Modifier.height(ThemeSpec.Space.xs))
                Text(
                    description,
                    fontSize = ThemeSpec.Type.micro,
                    color = ThemeSemantics.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (programFocused) {
            PhosphorIcon(R.drawable.ph_info, ThemeSemantics.textTertiary, 14.dp)
            Spacer(Modifier.width(ThemeSpec.Space.xs))
            Text(
                "OK  Details",
                fontSize = ThemeSpec.Type.label,
                fontFamily = ThemeSpec.Font.broadcast,
                color = ThemeSemantics.textTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.width(ThemeSpec.Space.sm))
            PhosphorIcon(R.drawable.ph_play, ThemeSemantics.textTertiary, 14.dp)
            Spacer(Modifier.width(ThemeSpec.Space.xs))
            Text(
                "Hold OK  Watch",
                fontSize = ThemeSpec.Type.label,
                fontFamily = ThemeSpec.Font.broadcast,
                color = ThemeSemantics.textTertiary,
                maxLines = 1,
            )
        } else {
            PhosphorIcon(R.drawable.ph_play, ThemeSemantics.textTertiary, 14.dp)
            Spacer(Modifier.width(ThemeSpec.Space.xs))
            Text(
                "OK  Watch channel",
                fontSize = ThemeSpec.Type.label,
                fontFamily = ThemeSpec.Font.broadcast,
                color = ThemeSemantics.textTertiary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun GuideTimelineHeader(
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    timelineState: androidx.compose.foundation.ScrollState,
) {
    val hourMarks =
        remember(windowStart, windowEnd) {
            buildList {
                var mark = ((windowStart + GUIDE_HOUR_MS - 1) / GUIDE_HOUR_MS) * GUIDE_HOUR_MS
                while (mark <= windowEnd) {
                    add(mark)
                    mark += GUIDE_HOUR_MS
                }
            }
        }
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(GUIDE_CHANNEL_WIDTH).padding(start = ThemeSpec.Space.cozy),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhosphorIcon(R.drawable.ph_broadcast, ThemeSemantics.textTertiary, 12.dp)
            Spacer(Modifier.width(ThemeSpec.Space.xs))
            Text(
                "CHANNEL",
                fontSize = ThemeSpec.Type.micro,
                fontFamily = ThemeSpec.Font.broadcast,
                fontWeight = FontWeight.Bold,
                color = ThemeSemantics.textTertiary,
                letterSpacing = ThemeSpec.Type.trackCaps,
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(timelineState, enabled = false)) {
            Box(Modifier.width(GUIDE_TIMELINE_WIDTH).fillMaxHeight()) {
                hourMarks.forEach { mark ->
                    Text(
                        hhmm(mark),
                        modifier = Modifier.offset(x = guideOffset(mark, windowStart) + ThemeSpec.Space.xs),
                        fontSize = ThemeSpec.Type.label,
                        fontFamily = ThemeSpec.Font.mono,
                        color = ThemeSemantics.textSecondary,
                        maxLines = 1,
                    )
                }
                Box(
                    Modifier
                        .offset(x = guideOffset(now, windowStart))
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(ThemeSemantics.live),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideTimelineRow(
    stream: XtreamClient.LiveStream,
    rowNumber: Int,
    selected: Boolean,
    now: Long,
    windowStart: Long,
    windowEnd: Long,
    client: XtreamClient,
    ctx: android.content.Context,
    timelineState: androidx.compose.foundation.ScrollState,
    modifier: Modifier,
    onChannelFocus: () -> Unit,
    onProgramFocus: (GuideModel.Slot) -> Unit,
    onWatch: () -> Unit,
    onTune: (GuideModel.Slot) -> Unit,
    onDetails: (GuideModel.Slot) -> Unit,
) {
    GuideData.loadDetached(ctx, client, stream.streamId)
    val slots = GuideData.slots[stream.streamId].orEmpty()
    val visibleSlots =
        remember(slots, windowStart, windowEnd) {
            slots.filter { it.stopMs > windowStart && it.startMs < windowEnd }.sortedBy { it.startMs }
        }
    Row(Modifier.fillMaxWidth().height(GUIDE_ROW_HEIGHT)) {
        GuideChannelCell(
            stream = stream,
            rowNumber = rowNumber,
            selected = selected,
            modifier = modifier.width(GUIDE_CHANNEL_WIDTH),
            onFocused = onChannelFocus,
            onWatch = onWatch,
        )
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(timelineState)) {
            Box(Modifier.width(GUIDE_TIMELINE_WIDTH).fillMaxHeight()) {
                visibleSlots.forEach { slot ->
                    val visibleStart = slot.startMs.coerceAtLeast(windowStart)
                    val visibleEnd = slot.stopMs.coerceAtMost(windowEnd)
                    Box(
                        Modifier
                            .offset(x = guideOffset(visibleStart, windowStart))
                            .width(guideWidth(visibleEnd - visibleStart))
                            .fillMaxHeight()
                            .padding(horizontal = 1.dp, vertical = 3.dp),
                    ) {
                        GuideProgramCell(
                            slot = slot,
                            phase = GuideModel.phase(slot, now),
                            now = now,
                            onFocused = { onProgramFocus(slot) },
                            onTune = { onTune(slot) },
                            onDetails = { onDetails(slot) },
                        )
                    }
                }
                if (visibleSlots.isEmpty()) {
                    Text(
                        if (GuideData.slots.containsKey(stream.streamId)) "No programme information" else "Loading guide…",
                        modifier = Modifier.padding(start = ThemeSpec.Space.cozy, top = ThemeSpec.Space.lg),
                        fontSize = ThemeSpec.Type.label,
                        fontFamily = ThemeSpec.Font.broadcast,
                        color = ThemeSemantics.textTertiary,
                    )
                }
                Box(
                    Modifier
                        .offset(x = guideOffset(now, windowStart))
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(ThemeSemantics.live.copy(alpha = 0.8f)),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideChannelCell(
    stream: XtreamClient.LiveStream,
    rowNumber: Int,
    selected: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onWatch: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ThemeSpec.Card.cornerSm)
    val scale = focusScaleState(focused, lift = ThemeSpec.Focus.liftCompact)
    Row(
        modifier
            .fillMaxHeight()
            .padding(horizontal = 3.dp, vertical = 3.dp)
            .focusLiftRow(scale)
            .clip(shape)
            .background(
                when {
                    focused -> ThemeSemantics.surfaceFocus
                    selected -> ThemeSemantics.surfaceSelected
                    else -> ThemeSemantics.surfaceRaised
                },
            ).focusRing(focused, shape, idle = ThemeSemantics.hairline)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }.combinedClickableNoIndication(onClick = onWatch)
            .padding(horizontal = ThemeSpec.Space.cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val number = stream.num.takeIf { it > 0 } ?: rowNumber
        Text(
            number.toString(),
            modifier = Modifier.width(26.dp),
            fontSize = ThemeSpec.Type.label,
            fontFamily = ThemeSpec.Font.mono,
            color = ThemeSemantics.textTertiary,
            maxLines = 1,
        )
        ChannelLogo(stream, 28.dp)
        Spacer(Modifier.width(ThemeSpec.Space.sm))
        Text(
            GuideModel.displayName(stream.name),
            modifier = Modifier.weight(1f),
            fontSize = ThemeSpec.Type.meta,
            fontFamily = ThemeSpec.Font.broadcast,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected || focused) ThemeSemantics.textEmphatic else ThemeSemantics.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (focused) {
            Spacer(Modifier.width(ThemeSpec.Space.xs))
            PhosphorIcon(R.drawable.ph_play, ThemeSemantics.textEmphatic, 13.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GuideProgramCell(
    slot: GuideModel.Slot,
    phase: GuideModel.Phase,
    now: Long,
    onFocused: () -> Unit,
    onTune: () -> Unit,
    onDetails: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView =
        remember {
            androidx.compose.foundation.relocation
                .BringIntoViewRequester()
        }
    val shape = RoundedCornerShape(ThemeSpec.Card.cornerSm)
    val scale = focusScaleState(focused, lift = ThemeSpec.Focus.liftCompact)
    val tint = remember(slot) { genreTint(slot.title, slot.description) }
    val isCurrent = phase == GuideModel.Phase.LIVE
    LaunchedEffect(focused) { if (focused) bringIntoView.bringIntoView() }
    Box(
        Modifier
            .fillMaxSize()
            .focusLiftRow(scale)
            .clip(shape)
            .background(
                when {
                    focused -> ThemeSemantics.surfaceFocus
                    isCurrent -> ThemeSemantics.accent.copy(alpha = 0.16f)
                    phase == GuideModel.Phase.PAST -> ThemeSemantics.surfaceRest.scaleAlpha(0.5f)
                    else -> ThemeSemantics.surfaceRaised
                },
            ).focusRing(focused, shape, idle = if (isCurrent) ThemeSemantics.live.copy(alpha = 0.55f) else tint ?: ThemeSemantics.hairline)
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }.combinedClickableNoIndication(onClick = onDetails, onLongClick = onTune)
            .padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.xs),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                slot.title,
                fontSize = ThemeSpec.Type.meta,
                fontFamily = ThemeSpec.Font.broadcast,
                fontWeight = if (isCurrent || focused) FontWeight.Bold else FontWeight.Medium,
                color = if (phase == GuideModel.Phase.PAST) ThemeSemantics.textTertiary else ThemeSemantics.textEmphatic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(ThemeSpec.Space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    PhosphorIcon(R.drawable.ph_broadcast, ThemeSemantics.accent, 11.dp)
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    if (isCurrent) "LIVE  ·  ${GuideModel.leftLabel(slot, now)}" else "${hhmm(slot.startMs)} – ${hhmm(slot.stopMs)}",
                    fontSize = ThemeSpec.Type.micro,
                    fontFamily = ThemeSpec.Font.mono,
                    color = if (isCurrent) ThemeSemantics.accent else ThemeSemantics.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isCurrent) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(ThemeSemantics.progressTrack),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(GuideModel.progress(slot, now)).background(ThemeSemantics.accent))
            }
        }
    }
}
