package com.carteltv.tv.ui.settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.badges.StreamBadges
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.stremio.StremioAddons
import com.carteltv.tv.data.stremio.StremioClient
import com.carteltv.tv.data.stremio.streamMatchesRequest
import com.carteltv.tv.data.stremio.titleAliases
import com.carteltv.tv.data.stremio.yearIn
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.FilterChip
import com.carteltv.tv.ui.components.TvDialogs
import com.carteltv.tv.ui.components.TvTextField
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.glassRow
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.swallowTaps
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.theme.*
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ManageAddonsPane(focus: FocusRequester) {
    var url by remember { mutableStateOf(TextFieldValue("")) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val installed = remember { StremioAddons.defaultUrls().toMutableStateList() }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(bottom = ThemeSpec.Space.lg)) {
        Text(
            androidx.compose.ui.res
                .stringResource(com.carteltv.tv.R.string.addon_add_new_hint),
            fontSize = ThemeSpec.Type.meta,
            color = ThemeSemantics.textSecondary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(ThemeSpec.Space.md))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
            TvTextField(
                value = url,
                onValueChange = { url = it },
                hint = "https://…/manifest.json",
                modifier = Modifier.weight(1f),
                requester = focus,
            )
            ActionButton(if (busy) "Checking…" else "Add", filled = url.text.isNotBlank() && !busy) {
                // The label already says a check is running; a second OK must not start a rival one.
                if (busy) return@ActionButton
                busy = true
                err = null
                scope.launch {
                    val norm = StremioAddons.normalize(url.text)
                    suspendRunCatching { StremioClient(norm).manifest() }
                        .onSuccess {
                            AppPreferences.addAddon(norm)
                            url = TextFieldValue("")
                            installed.clear()
                            installed.addAll(StremioAddons.defaultUrls())
                        }.onFailure { err = "Could not reach the add-on. Check the address and try again." }
                    busy = false
                }
            }
        }
        err?.let {
            Text(
                it,
                color = ThemeSemantics.danger,
                fontSize = ThemeSpec.Type.meta,
                modifier = Modifier.padding(top = ThemeSpec.Space.sm),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(30.dp))
        Text(
            androidx.compose.ui.res
                .stringResource(com.carteltv.tv.R.string.addon_installed_count, installed.size),
            fontSize = ThemeSpec.Type.meta,
            color = ThemeSemantics.accent,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(ThemeSpec.Space.sm))

        // Two-column card grid. This pane sits inside the settings page's own scrolling list, and a lazy grid cannot be
        // nested in an unbounded-height parent, so the pairs are laid out as plain equal-weight rows.
        installed.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md)) {
                pair.forEach { u ->
                    key(u) {
                        var focused by remember { mutableStateOf(false) }
                        val known = remember(u) { runCatching { StremioAddons.loaded().firstOrNull { it.url == u } }.getOrNull() }
                        val unreachable = known == null && StremioAddons.lastErrors.any { it.startsWith(u) }
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(vertical = ThemeSpec.Space.xs)
                                .cardSurface(focused)
                                .onFocusChanged { focused = it.isFocused }
                                .clickableNoIndication {
                                    TvDialogs.confirm("Remove addon?", "Remove ${known?.manifest?.name ?: u}?", destructive = true) {
                                        AppPreferences.removeAddon(u)
                                        installed.clear()
                                        installed.addAll(StremioAddons.defaultUrls())
                                    }
                                }.padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val manifestName = known?.manifest?.name
                                val logo = known?.manifest?.logo
                                val fallback = u.substringAfter("//")
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(if (logo != null) Color.Transparent else ThemeSemantics.accent.copy(alpha = 0.2f))
                                        .then(
                                            if (logo ==
                                                null
                                            ) {
                                                Modifier.border(1.dp, ThemeSemantics.accent.copy(alpha = 0.5f), CircleShape)
                                            } else {
                                                Modifier
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (logo != null) {
                                        AsyncImage(
                                            model = logo,
                                            contentDescription = "${manifestName ?: "Addon"} logo",
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        // No logo: the addon's own initial, from the same name the card displays.
                                        val label = (manifestName ?: fallback).trim()
                                        Text(
                                            label.take(1).uppercase(),
                                            fontSize = ThemeSpec.Type.body,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = ThemeSpec.Font.broadcast,
                                            color = ThemeSemantics.accentBright,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(ThemeSpec.Space.md))
                                Text(
                                    manifestName ?: fallback.take(38),
                                    fontSize = ThemeSpec.Type.body,
                                    color = if (unreachable) ThemeSemantics.danger else ThemeSemantics.textEmphatic,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                if (unreachable) "Not reachable. Will retry later." else known?.manifest?.description ?: u,
                                fontSize = ThemeSpec.Type.label,
                                color = ThemeSemantics.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = ThemeSpec.Space.xs),
                            )
                            Spacer(Modifier.height(ThemeSpec.Space.sm))
                            AddonStatusPill(unreachable = unreachable, focused = focused)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A status pill for an addon card: unreachable, or a remove affordance that names the OK action on focus. */
@Composable
private fun AddonStatusPill(
    unreachable: Boolean,
    focused: Boolean,
) {
    val label =
        when {
            unreachable -> {
                "Not reachable"
            }

            focused -> {
                "Press OK to remove"
            }

            else -> {
                "Remove"
            }
        }
    val tint = ThemeSemantics.danger
    Text(
        label,
        fontSize = ThemeSpec.Type.label,
        color = tint,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .clip(RoundedCornerShape(ThemeSpec.Card.pill))
                .background(tint.copy(alpha = 0.14f))
                .padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.xs),
    )
}

@Composable
fun StreamPickerHost(
    met: StremioClient.Metum,
    onDismiss: () -> Unit,
    initialSeason: Int? = null,
    initialEpisode: Int? = null,
) {
    val isSeries = met.type.equals("series", ignoreCase = true)
    var episodes by remember(met.id) { mutableStateOf<List<StremioClient.Metum.Video>>(emptyList()) }
    var selSeason by remember(met.id) { mutableStateOf(initialSeason ?: 1) }
    var pickedEpisode by remember(met.id) { mutableStateOf<StremioClient.Metum.Video?>(null) }
    var loadingEpisodes by remember(met.id) { mutableStateOf(isSeries) }

    var streams by remember { mutableStateOf(listOf<StremioClient.StreamTuple>()) }
    var loading by remember { mutableStateOf(!isSeries) }
    var resolving by remember { mutableStateOf(false) }
    var resolveErr by remember { mutableStateOf<String?>(null) }
    var failedAddons by remember { mutableIntStateOf(0) }
    var autoPlayed by remember { mutableStateOf(false) }
    // List behaviour: persisted, because a household sets its order and filters once.
    var sort by remember { mutableStateOf(AppPreferences.streamSort) }
    var resolutions by remember { mutableStateOf(AppPreferences.streamResolutions) }
    var hdrOnly by remember { mutableStateOf(AppPreferences.streamHdrOnly) }
    var cachedOnly by remember { mutableStateOf(AppPreferences.streamCachedOnly) }
    var hiddenSources by remember { mutableStateOf(AppPreferences.hiddenStreamSources()) }
    var autoPlay by remember { mutableStateOf(AppPreferences.streamAutoPlay) }
    val filters =
        remember(resolutions, hdrOnly, cachedOnly, hiddenSources) {
            com.carteltv.tv.data.stremio.StreamOrdering.Filters(
                resolutions = resolutions,
                hdrOnly = hdrOnly,
                cachedOnly = cachedOnly,
                hiddenSources = hiddenSources,
            )
        }
    val ordered =
        remember(streams, sort, filters) {
            com.carteltv.tv.data.stremio.StreamOrdering
                .apply(
                    streams,
                    com.carteltv.tv.data.stremio.StreamOrdering
                        .sortOf(sort),
                    filters,
                )
        }
    val firstFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var badgesTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        StreamBadges.ensureLoaded()
        badgesTick++
    }

    androidx.activity.compose.BackHandler {
        if (isSeries && pickedEpisode != null) {
            pickedEpisode = null
            streams = emptyList()
            resolveErr = null
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(met.id, isSeries) {
        if (!isSeries) return@LaunchedEffect
        loadingEpisodes = true
        val fetched = StremioAddons.meta("series", met.id)?.videos.orEmpty()
        episodes =
            fetched
                .filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        selSeason = initialSeason ?: episodes.firstOrNull()?.season ?: 1
        if (initialEpisode != null) {
            pickedEpisode = episodes.firstOrNull { it.season == selSeason && it.episode == initialEpisode }
                ?: StremioClient.Metum.Video(season = selSeason, episode = initialEpisode)
        }
        loadingEpisodes = false
    }

    val targetStreamId =
        remember(met.id, isSeries, pickedEpisode) {
            when {
                !isSeries -> met.id
                pickedEpisode == null -> null
                else -> pickedEpisode?.let { it.id.ifBlank { "${met.id}:${it.season}:${it.episode}" } }
            }
        }
    val playRef =
        remember(met.id, pickedEpisode) {
            pickedEpisode?.let { "series:${met.id}/${it.season}:${it.episode}" } ?: "stremio:${met.id}"
        }

    // The names TMDB shows: the title, and for a series each episode's own name instead of "Episode 3".
    val tmdbShow by produceState<com.carteltv.tv.data.discovery.TmdbTitles.Title?>(null, met.id) {
        value =
            suspendRunCatching {
                com.carteltv.tv.data.discovery.TmdbTitles
                    .forImdb(met.id.substringBefore(':'), isSeries)
            }.getOrNull()
    }
    val showName = tmdbShow?.name ?: met.name
    val episodeSeason = pickedEpisode?.season ?: selSeason
    val episodeNames by produceState(emptyMap<Int, String>(), met.id, episodeSeason, isSeries) {
        value =
            if (!isSeries) {
                emptyMap()
            } else {
                suspendRunCatching {
                    com.carteltv.tv.data.discovery.TmdbTitles
                        .seasonEpisodes(met.id.substringBefore(':'), episodeSeason)
                }.getOrDefault(emptyMap())
            }
    }
    val epName: (Int?) -> String? = { n -> n?.let { episodeNames[it] } }

    /** Resolve and launch one row; alternates stay as fallbacks if the link dies mid-play. */
    fun play(s: StremioClient.StreamTuple) {
        if (resolving) return
        resolving = true
        resolveErr = null
        scope.launch {
            val url = s.url ?: s.infoHash?.let { ih -> StremioAddons.resolveTorrent(ih, s.sources) }
            resolving = false
            if (url != null) {
                val ep = pickedEpisode
                val playTitle =
                    ep?.let { e ->
                        "$showName S${e.season}E${e.episode}" + (epName(e.episode) ?: e.title)?.let { " · $it" }.orEmpty()
                    } ?: (tmdbShow?.let { t -> t.year?.let { y -> "${t.name} ($y)" } ?: t.name } ?: s.title ?: met.name)
                AppPreferences.rememberLastLink(playRef, url, s.infoHash, s.title)
                val alternates =
                    ordered
                        .dropWhile { it !== s }
                        .drop(1)
                        .take(4)
                        .map {
                            com.carteltv.tv.engine.playback
                                .AlternateSource(it.url, it.infoHash, it.sources)
                        }
                AppLauncher.player(ctx, url, playTitle, playRef, alternates)
                onDismiss()
            } else {
                resolveErr = "This source could not be resolved. Check your debrid account in Settings."
            }
        }
    }

    LaunchedEffect(targetStreamId) {
        val sid = targetStreamId ?: return@LaunchedEffect
        loading = true
        resolveErr = null
        // TMDB's name for the title, when a key is set; short wait so a slow lookup never holds the sources up.
        val tmdb =
            kotlinx.coroutines.withTimeoutOrNull(2_500L) {
                suspendRunCatching {
                    com.carteltv.tv.data.discovery.TmdbTitles
                        .forImdb(sid.substringBefore(':'), isSeries)
                }.getOrNull()
            }
        val otherNames = listOfNotNull(met.name.takeIf { tmdb != null && it != tmdb.name }, tmdb?.originalName).distinct()
        val builtIn =
            async {
                if (!com.carteltv.tv.data.preferences.AppPreferences.builtInSources) return@async emptyList<StremioClient.StreamTuple>()
                suspendRunCatching {
                    com.carteltv.tv.data.scrape.BuiltInSources.search(
                        com.carteltv.tv.data.scrape.ScrapeRequest(
                            imdbId = sid.substringBefore(':'),
                            type = met.type ?: "movie",
                            title = tmdb?.name ?: met.name,
                            year = tmdb?.year ?: yearIn(met.releaseInfo),
                            season = pickedEpisode?.season,
                            episode = pickedEpisode?.episode,
                            aliases = otherNames,
                        ),
                    )
                }.getOrDefault(emptyList())
            }
        // Every add-on is asked at once, so one slow server no longer holds up the rest; the ones that fail are counted.
        val answers =
            coroutineScope {
                StremioAddons
                    .serving("stream", met.type ?: "movie", sid)
                    .map { addon ->
                        async {
                            suspendRunCatching { addon.client.streams(met.type ?: "movie", sid) }
                                .map { rows -> rows.map { it.copy(addon = addon.manifest.name) } }
                        }
                    }.map { it.await() }
            }
        failedAddons = answers.count { it.isFailure }
        val out = answers.flatMap { it.getOrDefault(emptyList()) } + builtIn.await()
        // Release validation: a torrent stream whose release name can be read must
        // belong to this title/episode, or the addon surfaced unrelated junk. Streams
        // with no readable name and direct streams pass untouched (ReleaseTitleMatch).
        // Full addon meta carries alternate titles the catalog entry lacks, which
        // localized release names need ("Il Padrino" for The Godfather).
        val aliases = StremioAddons.meta(met.type ?: "movie", sid.substringBefore(':'))?.titleAliases().orEmpty()
        val matched =
            out.filter {
                streamMatchesRequest(
                    it,
                    type = met.type,
                    requested = met.name,
                    year = yearIn(met.releaseInfo),
                    season = pickedEpisode?.season,
                    episode = pickedEpisode?.episode,
                    aliases = aliases + otherNames,
                )
            }
        // Debrid rows stay only when a linked provider already holds the torrent —
        // instantly available or finished in the user's own cloud. A row that would
        // first have to download can never resolve from the picker, so it is not offered.
        val hashes = matched.mapNotNull { it.infoHash?.lowercase() }.distinct()
        val tbReady =
            AppPreferences.torboxToken.takeIf { it.isNotBlank() && hashes.isNotEmpty() }?.let { tok ->
                suspendRunCatching {
                    com.carteltv.tv.data.debrid
                        .TorboxClient(tok)
                        .cachedHashes(hashes)
                }.getOrNull()
            }
        com.carteltv.tv.data.debrid.DebridAuth
            .ensureFreshOrKeep()
        val rdReady =
            AppPreferences.debridToken.takeIf { it.isNotBlank() && hashes.isNotEmpty() }?.let { tok ->
                suspendRunCatching {
                    val c =
                        com.carteltv.tv.data.debrid
                            .DebridClient(tok)
                    // A finished torrent in the user's own cloud plays just as immediately as a fresh instant hit.
                    c.instantHashes(hashes).orEmpty() +
                        c.torrents().filter { it.status == "downloaded" }.mapNotNull { it.hash?.lowercase() }
                }.getOrNull()
            }
        // Both checks failed → unknown, keep the rows rather than wrongly empty the list.
        streams =
            if (hashes.isEmpty() || (tbReady == null && rdReady == null)) {
                matched
            } else {
                matched
                    .filter { s ->
                        val h = s.infoHash?.lowercase()
                        h == null || tbReady?.contains(h) == true || rdReady?.contains(h) == true
                    }.map { s ->
                        val h = s.infoHash?.lowercase()
                        if (h != null && (tbReady?.contains(h) == true || rdReady?.contains(h) == true)) s.copy(cached = true) else s
                    }
            }
        loading = false
    }

    LaunchedEffect(targetStreamId) {
        autoPlayed = false
    }

    // Auto-play is opt-in, and only after the list settled: first best source, or the first name
    // matching the configured expression, falling back to the best when nothing matches.
    LaunchedEffect(targetStreamId, loading, ordered, autoPlay) {
        if (loading || resolving || autoPlayed) return@LaunchedEffect
        if (autoPlay == com.carteltv.tv.data.preferences.StreamPreferences.AutoPlay.MANUAL) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        val regex =
            if (autoPlay == com.carteltv.tv.data.preferences.StreamPreferences.AutoPlay.REGEX) {
                AppPreferences.streamAutoPlayRegex.ifBlank { null }
            } else {
                null
            }
        val pick =
            if (regex == null) {
                ordered.firstOrNull()
            } else {
                com.carteltv.tv.data.stremio.StreamOrdering
                    .apply(
                        streams,
                        com.carteltv.tv.data.stremio.StreamOrdering.Sort.BEST,
                        filters.copy(regex = regex),
                    ).firstOrNull() ?: ordered.firstOrNull()
            }
        if (pick != null) {
            autoPlayed = true
            play(pick)
        }
    }

    LaunchedEffect(loading, loadingEpisodes, streams.size, pickedEpisode, selSeason) {
        if (!loading && !loadingEpisodes) {
            suspendRunCatching {
                val hasRows =
                    if (isSeries && pickedEpisode == null) {
                        episodes.any { it.season == selSeason }
                    } else {
                        streams.isNotEmpty()
                    }
                // Same entry law as TvDialog: wait frame by frame, since the lazy rows attach after this effect starts.
                if (hasRows) firstFocus.awaitFocus() else closeFocus.awaitFocus()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(ThemeSemantics.scrimModal).dismissScrim { onDismiss() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .swallowTaps()
                .trapFocus()
                .fillMaxWidth()
                .widthIn(
                    max = 960.dp - 48.dp * 2,
                ).clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            ThemeSemantics.accentShadow.copy(alpha = 0.92f),
                            ThemeSemantics.surfaceModal,
                        ),
                    ),
                ).glassLight()
                .border(1.dp, ThemeSemantics.accent.copy(alpha = 0.35f), RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .padding(30.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (met.poster !=
                    null
                ) {
                    AsyncImage(
                        model = met.poster,
                        contentDescription = "${met.name} poster",
                        modifier = Modifier.size(74.dp, 106.dp).clip(RoundedCornerShape(ThemeSpec.Card.corner)),
                    )
                }
                Spacer(Modifier.width(ThemeSpec.Space.md))
                Column {
                    val headerTitle =
                        pickedEpisode?.let { ep ->
                            "$showName · S${ep.season}E${ep.episode}" +
                                (epName(ep.episode)?.let { " · $it" } ?: ep.title?.let { " · $it" }.orEmpty())
                        } ?: showName
                    Text(
                        headerTitle,
                        fontSize = ThemeSpec.Type.title,
                        color = ThemeSemantics.textEmphatic,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = ThemeSpec.Space.xs)) {
                        met.imdbRating?.let {
                            Text(
                                it,
                                fontSize = ThemeSpec.Type.meta,
                                color = ThemeSemantics.accentBright,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(ThemeSpec.Space.cozy))
                        }
                        met.releaseInfo?.let {
                            Text(
                                it,
                                fontSize = ThemeSpec.Type.meta,
                                color = ThemeSemantics.textSecondary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        met.runtime?.let {
                            Text(
                                "  ·  $it",
                                fontSize = ThemeSpec.Type.meta,
                                color = ThemeSemantics.textSecondary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                var closeFocused by remember { mutableStateOf(false) }
                Text(
                    "CLOSE",
                    fontSize = ThemeSpec.Type.label,
                    fontWeight = FontWeight.Black,
                    color = if (closeFocused) ThemeSemantics.textEmphatic else ThemeSemantics.textSecondary,
                    fontFamily = ThemeSpec.Font.broadcast,
                    letterSpacing = ThemeSpec.Type.trackCaps,
                    modifier =
                        Modifier
                            .focusRequester(closeFocus)
                            .cardSurface(closeFocused, ThemeSpec.Focus.liftCompact)
                            .onFocusChanged { closeFocused = it.isFocused }
                            .clickableNoIndication { onDismiss() }
                            .padding(ThemeSpec.Space.sm),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(ThemeSpec.Space.md))
            Box(Modifier.fillMaxWidth().height(1.dp).background(ThemeSemantics.hairline))

            // List controls: order, resolution, visual/cache filters, source scope and auto-play.
            // Every pick persists, so the next picker opens the way this one was left.
            LazyRow(
                Modifier.restoreRowFocus().padding(vertical = ThemeSpec.Space.sm),
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            ) {
                item(key = "sort") {
                    FilterChip(
                        "SORT ${sort.name.replace('_', ' ')}",
                        sort != com.carteltv.tv.data.preferences.StreamPreferences.Sort.BEST,
                    ) {
                        val next =
                            com.carteltv.tv.data.preferences.StreamPreferences.Sort.entries.let { values ->
                                values[(values.indexOf(sort) + 1) % values.size]
                            }
                        sort = next
                        AppPreferences.streamSort = next
                    }
                }
                items(listOf("2160p", "1080p", "720p", "SD"), key = { "res:$it" }) { res ->
                    FilterChip(res, res in resolutions) {
                        resolutions = if (res in resolutions) resolutions - res else resolutions + res
                        AppPreferences.streamResolutions = resolutions
                    }
                }
                item(key = "hdr") {
                    FilterChip("HDR/DV", hdrOnly) {
                        hdrOnly = !hdrOnly
                        AppPreferences.streamHdrOnly = hdrOnly
                    }
                }
                item(key = "cached") {
                    FilterChip("CACHED", cachedOnly) {
                        cachedOnly = !cachedOnly
                        AppPreferences.streamCachedOnly =
                            cachedOnly
                    }
                }
                item(key = "sources") {
                    val sources = remember(streams) { streams.mapNotNull { it.addon }.distinct().sorted() }
                    FilterChip("SOURCES", hiddenSources.isNotEmpty()) {
                        TvDialogs.menu(
                            "Sources",
                            listOf("All sources") + sources.map { if (it in hiddenSources) "✓ $it" else it },
                            onPick = { i ->
                                if (i == 0) {
                                    hiddenSources = emptySet()
                                } else {
                                    sources.getOrNull(i - 1)?.let { s ->
                                        hiddenSources = if (s in hiddenSources) hiddenSources - s else hiddenSources + s
                                    }
                                }
                                AppPreferences.setHiddenStreamSources(hiddenSources)
                            },
                        )
                    }
                }
                item(key = "auto") {
                    FilterChip(
                        "AUTO " +
                            when (autoPlay) {
                                com.carteltv.tv.data.preferences.StreamPreferences.AutoPlay.MANUAL -> "OFF"
                                com.carteltv.tv.data.preferences.StreamPreferences.AutoPlay.FIRST -> "FIRST"
                                com.carteltv.tv.data.preferences.StreamPreferences.AutoPlay.REGEX -> "MATCH"
                            },
                        autoPlay != com.carteltv.tv.data.preferences.StreamPreferences.AutoPlay.MANUAL,
                    ) {
                        autoPlay =
                            com.carteltv.tv.data.preferences.StreamPreferences.AutoPlay.entries.let { values ->
                                values[(values.indexOf(autoPlay) + 1) % values.size]
                            }
                        AppPreferences.streamAutoPlay = autoPlay
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(ThemeSemantics.hairline))

            if (resolving) {
                Text(
                    androidx.compose.ui.res
                        .stringResource(com.carteltv.tv.R.string.addon_resolving_debrid),
                    fontSize = ThemeSpec.Type.meta,
                    color = ThemeSemantics.accent,
                    modifier = Modifier.padding(top = ThemeSpec.Space.sm),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            resolveErr?.let {
                Text(
                    it,
                    fontSize = ThemeSpec.Type.meta,
                    color = ThemeSemantics.danger,
                    modifier = Modifier.padding(top = ThemeSpec.Space.sm),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(ThemeSpec.Space.cozy))
            val last =
                remember(playRef, streams.size, badgesTick) {
                    AppPreferences.lastLink(playRef)?.takeIf { AppPreferences.streamReuseLastLink }
                }
            when {
                // Loading stays a one-line loader: the picker keeps its own frame, and a page skeleton would not fit under its header.
                isSeries && pickedEpisode == null && loadingEpisodes -> {
                    Text(
                        androidx.compose.ui.res
                            .stringResource(com.carteltv.tv.R.string.addon_loading_episodes),
                        fontSize = ThemeSpec.Type.meta,
                        color = ThemeSemantics.textSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }

                isSeries && pickedEpisode == null && episodes.isEmpty() -> {
                    EmptyState(
                        androidx.compose.ui.res
                            .stringResource(com.carteltv.tv.R.string.addon_no_episodes),
                    )
                }

                isSeries && pickedEpisode == null -> {
                    val seasonNums = remember(episodes) { episodes.mapNotNull { it.season }.distinct().sorted() }
                    LazyRow(Modifier.restoreRowFocus(), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                        items(seasonNums, key = { it }) { sn ->
                            FilterChip("S$sn", selSeason == sn) { selSeason = sn }
                        }
                    }
                    Spacer(Modifier.height(ThemeSpec.Space.cozy))
                    val seasonEps = remember(episodes, selSeason) { episodes.filter { it.season == selSeason } }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.heightIn(min = 330.dp),
                        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
                        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
                    ) {
                        items(seasonEps.size, key = { seasonEps[it].id.ifBlank { "$selSeason:$it" } }) { idx ->
                            val ep = seasonEps[idx]
                            var focused by remember { mutableStateOf(false) }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                    .cardSurface(focused)
                                    .onFocusChanged { focused = it.isFocused }
                                    .clickableNoIndication { pickedEpisode = ep }
                                    .padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.cozy),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "E${ep.episode}",
                                    fontSize = ThemeSpec.Type.meta,
                                    color = ThemeSemantics.accent,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(46.dp),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Text(
                                    epName(ep.episode) ?: ep.title ?: "Episode ${ep.episode}",
                                    fontSize = ThemeSpec.Type.meta,
                                    color = ThemeSemantics.textEmphatic,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (focused) {
                                    Text(
                                        "›",
                                        fontSize = ThemeSpec.Type.meta,
                                        color = ThemeSemantics.accent,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                loading -> {
                    Text(
                        androidx.compose.ui.res
                            .stringResource(com.carteltv.tv.R.string.addon_resolving_sources),
                        fontSize = ThemeSpec.Type.meta,
                        color = ThemeSemantics.textSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }

                streams.isEmpty() -> {
                    EmptyState(
                        androidx.compose.ui.res
                            .stringResource(com.carteltv.tv.R.string.addon_no_sources),
                        hint =
                            if (failedAddons >
                                0
                            ) {
                                "$failedAddons add-on${if (failedAddons == 1) " did" else "s did"} not respond."
                            } else {
                                null
                            },
                    )
                }

                ordered.isEmpty() -> {
                    EmptyState("No source matches these filters.", hint = "Widen the resolution, HDR or cached pick, or show every source.")
                }

                else -> {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(min = 380.dp)) {
                        last?.let { l ->
                            item(key = "last-link") {
                                StreamRow(
                                    stream =
                                        StremioClient.StreamTuple(
                                            title = l.title ?: "Last used source",
                                            url = l.url,
                                            infoHash = l.infoHash,
                                            addon = "Last used",
                                            badges = listOf("↺ LAST LINK"),
                                        ),
                                    focusedModifier = if (ordered.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                                    onPlay = { play(it) },
                                    badgesTick = badgesTick,
                                )
                            }
                        }
                        items(ordered.size, key = { ordered[it] }) { i ->
                            val s = ordered[i]
                            StreamRow(
                                stream = s,
                                focusedModifier = if (i == 0 && last == null) Modifier.focusRequester(firstFocus) else Modifier,
                                onPlay = { play(it) },
                                badgesTick = badgesTick,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One resolvable source row: kind, name, parsed tags and the community badge set, all on one focusable line. */
@Composable
private fun StreamRow(
    stream: StremioClient.StreamTuple,
    focusedModifier: Modifier,
    onPlay: (StremioClient.StreamTuple) -> Unit,
    badgesTick: Int,
) {
    var focused by remember { mutableStateOf(false) }
    val customBadges = remember(stream.releaseText, badgesTick) { StreamBadges.forTitle(stream.releaseText) }
    val tags =
        remember(stream.releaseText, stream.badges) {
            com.carteltv.tv.data.stremio.StreamOrdering
                .tags(stream)
        }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(focusedModifier)
                .glassRow(focused)
                .onFocusChanged { focused = it.isFocused }
                .clickableNoIndication { onPlay(stream) }
                .padding(end = ThemeSpec.Space.cozy, top = ThemeSpec.Space.sm, bottom = ThemeSpec.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .background(if (focused) ThemeSemantics.accent else androidx.compose.ui.graphics.Color.Transparent),
            )
            Spacer(Modifier.width(ThemeSpec.Space.cozy))
            Text(
                if (stream.url != null) "WEB" else "DEBRID",
                fontSize = ThemeSpec.Type.label,
                fontWeight = FontWeight.Black,
                color = ThemeSemantics.accent,
                modifier =
                    Modifier
                        .background(ThemeSemantics.accent.copy(alpha = 0.14f), RoundedCornerShape(ThemeSpec.Card.pill))
                        .padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.xs),
                maxLines = 1,
            )
            Spacer(Modifier.width(ThemeSpec.Space.cozy))
            Column(Modifier.weight(1f)) {
                Text(
                    stream.title ?: stream.name ?: "Unnamed source",
                    fontSize = ThemeSpec.Type.meta,
                    color = ThemeSemantics.textEmphatic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(vertical = ThemeSpec.Space.xs), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
                    tags.take(7).forEach { tag ->
                        val hot = tag.startsWith("⚡")
                        Text(
                            tag,
                            fontSize = ThemeSpec.Type.label,
                            fontWeight = if (hot) FontWeight.Black else FontWeight.SemiBold,
                            color = if (hot) ThemeSemantics.accent else ThemeSemantics.textEmphatic,
                            modifier =
                                Modifier
                                    .background(
                                        if (hot) ThemeSemantics.accent.copy(alpha = 0.18f) else ThemeSemantics.hairline,
                                        RoundedCornerShape(ThemeSpec.Card.pill),
                                    ).padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.xs),
                            maxLines = 1,
                        )
                    }
                }
                if (customBadges.isNotEmpty()) {
                    Row(Modifier.padding(bottom = ThemeSpec.Space.xs), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
                        customBadges.forEach { CustomStreamBadge(it) }
                    }
                }
                listOfNotNull(stream.addon, stream.description?.takeIf { it != stream.title })
                    .distinct()
                    .joinToString(" · ")
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        Text(
                            it,
                            fontSize = ThemeSpec.Type.label,
                            color = ThemeSemantics.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
            }
            if (focused) Text("▶", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.accent, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(ThemeSemantics.hairline))
    }
}

/** One imported community badge, using its own artwork/colors when the set supplies them. */
@Composable
private fun CustomStreamBadge(badge: StreamBadges.Badge) {
    val shape = RoundedCornerShape(ThemeSpec.Card.pill)
    val fill = badge.tagColor?.let { Color(it).copy(alpha = 0.18f) } ?: ThemeSemantics.surfaceRaised
    val edge = badge.borderColor?.let { Color(it) } ?: ThemeSemantics.hairline
    Row(
        Modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
    ) {
        badge.image?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(18.dp)) }
        Text(
            badge.name,
            fontSize = ThemeSpec.Type.label,
            fontWeight = FontWeight.SemiBold,
            color = ThemeSemantics.textEmphatic,
            maxLines = 1,
        )
    }
}
