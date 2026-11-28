@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.carteltv.tv.ui.home
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.carteltv.tv.alert.KickoffAlertScheduler
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.apps.InstalledAppsCache
import com.carteltv.tv.data.discovery.DiscoveryFeeds
import com.carteltv.tv.data.espn.EspnScoreboardClient
import com.carteltv.tv.data.espn.GameChannelFinder
import com.carteltv.tv.data.espn.LeagueCatalog
import com.carteltv.tv.data.espn.ScoreboardRepository
import com.carteltv.tv.data.events.Holidays
import com.carteltv.tv.data.events.MajorEvent
import com.carteltv.tv.data.events.MajorEvents
import com.carteltv.tv.data.finance.ExchangeRateService
import com.carteltv.tv.data.finance.ExchangeRates
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.stremio.StremioClient
import com.carteltv.tv.data.watch.WatchNextItem
import com.carteltv.tv.data.watch.WatchNextRepository
import com.carteltv.tv.data.weather.AirQuality
import com.carteltv.tv.data.weather.AirQualityService
import com.carteltv.tv.data.weather.Forecast
import com.carteltv.tv.data.weather.Place
import com.carteltv.tv.data.weather.VenueWeather
import com.carteltv.tv.data.weather.WeatherUnits
import com.carteltv.tv.data.weather.WeatherClient
import com.carteltv.tv.data.weather.WeatherService
import com.carteltv.tv.data.xtream.GuideModel
import com.carteltv.tv.data.xtream.LiveChannels
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.data.xtream.VodCatalogCache
import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.data.youtube.YouTubeApiClient
import com.carteltv.tv.engine.stability.NotificationMirror
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.nav.SectionNav
import com.carteltv.tv.ui.catalog.CatalogRow
import com.carteltv.tv.ui.catalog.loadCatalogRows
import com.carteltv.tv.ui.catalog.toHero
import com.carteltv.tv.ui.components.LocalTitleFocus
import com.carteltv.tv.ui.components.TitleFocus
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.rememberAppStarted
import com.carteltv.tv.ui.components.rememberClock
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.hero.BigEvent
import com.carteltv.tv.ui.hero.EventReason
import com.carteltv.tv.ui.hero.HERO_ROTATE_MS
import com.carteltv.tv.ui.hero.HeroItem
import com.carteltv.tv.ui.hero.HeroSlide
import com.carteltv.tv.ui.hero.HeroSlider
import com.carteltv.tv.ui.hero.HeroWeather
import com.carteltv.tv.ui.hero.bigEvents
import com.carteltv.tv.ui.hero.buildHeroItems
import com.carteltv.tv.ui.live.GuideMetadata
import com.carteltv.tv.ui.nav.AppOverlayHost
import com.carteltv.tv.ui.nav.HomeMenuSpec
import com.carteltv.tv.ui.nav.HomePinSpec
import com.carteltv.tv.ui.nav.ShellChrome
import com.carteltv.tv.ui.sports.GameSheet
import com.carteltv.tv.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private const val FOCUS_SETTLE_MS = 350L

/** How often Home re-reads the published big-events file. */
private const val GUIDE_EVENTS_REFRESH_MS = 30 * 60_000L

/** Listings turn over on the half hour; "on now" is re-read this often, so the shelf stays honest without a request storm. */
private const val GUIDE_SLOT_MS = 10 * 60_000L

private const val HOUR_MS = 3_600_000L

@Composable
fun HomeScreen(
    homeResetTick: Int = 0,
    onPlay: (url: String, title: String, refId: String) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val heroFocus = remember { FocusRequester() }
    val homeScope = rememberCoroutineScope()
    // Back on Home asks for its top; the effect further down carries that out, the same way a Home-key reset does.
    var topTick by remember { mutableIntStateOf(0) }
    val now = rememberClock()
    var channels by remember { mutableStateOf(listOf<XtreamClient.LiveStream>()) }
    var movies by remember { mutableStateOf(listOf<XtreamClient.VodStream>()) }
    // The provider's film categories, which become Home's catalogue rows.
    var vodCategories by remember { mutableStateOf(listOf<XtreamClient.Category>()) }
    var scores by remember { mutableStateOf<Map<String, List<EspnScoreboardClient.Score>>>(emptyMap()) }
    var watchNext by remember { mutableStateOf(listOf<WatchNextItem>()) }
    var recentsHash by remember { mutableIntStateOf(0) }
    var activeMenu by remember { mutableStateOf<HomeMenuSpec?>(null) }
    var activePin by remember { mutableStateOf<HomePinSpec?>(null) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) recentsHash++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val recentsV2 = remember(recentsHash) { AppPreferences.recentEntries() }
    val started = rememberAppStarted()
    // Continue-watching entries the player saved without a poster get real artwork here: a
    // Cinemeta→TMDB lookup per title (GuideMetadata caches and gates), silent on a miss.
    var cwArt by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(started, recentsV2) {
        if (!started) return@LaunchedEffect
        for (e in recentsV2.filter { it.poster.isBlank() }.distinctBy { GuideMetadata.cwArtKey(it.title) }) {
            val key = GuideMetadata.cwArtKey(e.title)
            if (cwArt.containsKey(key)) continue
            val res = suspendRunCatching { GuideMetadata.lookup(GuideMetadata.cwLookupTitle(e.title)) }
            // The frame first: a card is 16:9, and a show's backdrop fills it where its poster would be one cropped slice.
            val art = res.getOrNull()?.let { it.backdrop ?: it.poster } ?: continue
            cwArt = cwArt + (key to art)
        }
    }

    val hourNow =
        remember(now) {
            java.util.Calendar
                .getInstance()
                .apply { time = now }
                .get(java.util.Calendar.HOUR_OF_DAY)
        }
    // Up Next owns what is being watched, so the banner takes none of it: the same title twice, one above the
    // other, reads as a repeat.
    val upNextKeys =
        remember(recentsV2, watchNext) {
            resumeRecents(recentsV2, Int.MAX_VALUE).map { titleKey(splitTitle(it.title).name) } + watchNext.map { titleKey(it.title) }
        }
    val heroItems =
        remember(recentsHash, channels.hashCode(), movies.hashCode(), hourNow, cwArt, upNextKeys) {
            buildHeroItems(recentsV2, channels, movies, hourNow, cwArt, shown = upNextKeys.toSet())
        }

    var heroIndex by remember { mutableIntStateOf(0) }

    // The game whose options sheet OK opened from the hero; the sheet lives at the root beside StreamPickerHost.
    var heroGame by remember { mutableStateOf<Pair<String, EspnScoreboardClient.Score>?>(null) }
    var movieRows by remember { mutableStateOf<List<CatalogRow>>(emptyList()) }
    var ytTrending by remember { mutableStateOf(listOf<YouTubeApiClient.YouTubeVideo>()) }
    var recommended by remember { mutableStateOf(listOf<StremioClient.Metum>()) }
    // The shelves hold a skeleton until every load that can put a card on Home has answered once, so
    // "nothing to show yet" is only ever a true empty.
    var catalogSettled by remember { mutableStateOf(false) }
    var librarySettled by remember { mutableStateOf(false) }
    var scoresSettled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (ProviderSession.current == null) movieRows = loadCatalogRows("movie", 1) + loadCatalogRows("series", 1)
        catalogSettled = true
    }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        ytTrending =
            suspendRunCatching {
                YouTubeApiClient
                    .homeFeed()
                    .firstOrNull()
                    ?.videos
                    .orEmpty()
            }.getOrDefault(emptyList())
    }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        val key = AppPreferences.tmdbKey
        recommended =
            if (key.isBlank()) {
                emptyList()
            } else {
                suspendRunCatching {
                    DiscoveryFeeds.row(key, DiscoveryFeeds.Feed.NOW_PLAYING, mixTv = false).map { c ->
                        StremioClient.Metum(
                            id = "tmdb:${c.media}:${c.id}",
                            type = c.media,
                            name = c.title,
                            poster = DiscoveryFeeds.posterUrl(c.poster),
                            background = null,
                            releaseInfo = c.year,
                            imdbRating = c.rating?.let { "%.1f".format(it) },
                        )
                    }
                }.getOrDefault(emptyList())
            }
    }
    val movieItems = remember(movieRows) { movieRows.flatMap { it.items } }
    // With nothing of its own to open, the greeting's OK goes where its text points: to setup, or to the provider's own sections.
    val greetingLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val greetingDate = SimpleDateFormat("EEEE d MMMM", greetingLocale).format(now).uppercase(greetingLocale)
    val fallbackHero =
        movieItems.firstOrNull { !(it.background ?: it.poster).isNullOrBlank() }?.toHero() ?: HeroItem(
            title = cartelGreeting(hourNow),
            art = null,
            artRes = cartelGreetingArt(hourNow),
            description =
                if (ProviderSession.current == null) {
                    "Connect a TV provider in Settings to fill this screen."
                } else {
                    cartelGreetingTagline(hourNow)
                },
            badge = greetingDate,
            refId = "",
            url = "",
            opens =
                when {
                    ProviderSession.current == null -> AppSection.SETUP
                    channels.isNotEmpty() -> AppSection.LIVE
                    else -> AppSection.MOVIES
                },
        )
    val place = remember(AppPreferences.weatherLat, AppPreferences.weatherLon) { Place.current() }
    var forecast by remember { mutableStateOf<Forecast?>(null) }
    var fallbackWeather by remember { mutableStateOf<WeatherClient.Conditions?>(null) }
    LaunchedEffect(place.lat, place.lon, forecast == null, started) {
        if (!started || forecast != null) {
            fallbackWeather = null
            return@LaunchedEffect
        }
        // Give the richer forecast a head start; only pay for the lightweight fallback when it fails.
        delay(3_000)
        if (forecast == null) fallbackWeather = WeatherClient.cachedCurrent(place.lat, place.lon)
    }
    var air by remember { mutableStateOf<AirQuality?>(null) }
    var rates by remember { mutableStateOf<ExchangeRates?>(null) }
    LaunchedEffect(place.lat, place.lon, started, AppPreferences.weatherAqi) {
        if (!started) return@LaunchedEffect
        air = null
        while (true) {
            coroutineScope {
                val f = async { WeatherService.forecast(place.lat, place.lon) }
                val a = async { if (AppPreferences.weatherAqi) AirQualityService.current(place.lat, place.lon) else null }
                f.await()?.let { forecast = it }
                a.await()?.let { air = it }
            }
            delay(AppPreferences.weatherRefreshMin * 60_000L)
        }
    }
    // Holidays follow where the box is: the country from the IP lookup picks the calendar.
    var country by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(started) {
        if (started) country = suspendRunCatching { WeatherService.locateByIp() }.getOrNull()?.country
    }
    LaunchedEffect(started, AppPreferences.currencyBase, AppPreferences.currencyWatch.joinToString(","), country) {
        if (!started) return@LaunchedEffect
        rates = null
        while (true) {
            val base = AppPreferences.currencyBase.ifBlank { ExchangeRateService.currencyFor(country) ?: ExchangeRateService.DEFAULT_BASE }
            ExchangeRateService.latest(base, AppPreferences.currencyWatch)?.let { rates = it }
            delay(6 * 3_600_000L)
        }
    }
    var guideEvents by remember { mutableStateOf<List<MajorEvent>>(emptyList()) }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (true) {
            suspendRunCatching { MajorEvents.fetch() }.onSuccess { guideEvents = it }
            delay(GUIDE_EVENTS_REFRESH_MS)
        }
    }
    val crewTick = KickoffAlertScheduler.version
    val minuteMs = now.time / 60_000L * 60_000L
    val bigGames =
        remember(scores, crewTick, minuteMs) {
            bigEvents(
                scores,
                AppPreferences.sportsFavoriteLeague,
                minuteMs,
                involvesCrew = { league, game -> KickoffAlertScheduler.involvesCrew(ctx, league, game) },
            )
        }
    val eventPicks = remember(guideEvents, minuteMs) { MajorEvents.picks(guideEvents, minuteMs) }
    // Each league's own mark, from the feeds on show, for an event that belongs to one of them.
    val leagueLogos =
        remember(scores) {
            scores.entries
                .mapNotNull { (league, games) ->
                    games.firstNotNullOfOrNull { it.leagueLogo }?.let {
                        league to
                            it
                    }
                }.toMap()
        }
    val holiday = remember(minuteMs / HOUR_MS, country) { Holidays.next(java.time.LocalDate.now(), Holidays.regionOf(country)) }
    val mediaHeroes = heroItems.ifEmpty { listOf(fallbackHero) }
    // What you must not miss leads the carousel: live games first, then the soonest kickoffs, then event
    // picks, then media; the holiday countdown trails — it may be weeks out, so it never fronts what to
    // watch tonight. Games never sit in the media carousel.
    val slides =
        remember(eventPicks, leagueLogos, holiday, mediaHeroes, forecast != null, bigGames) {
            // A plain live game must never sit behind a favourite that has not started: live first, then by kickoff.
            val orderedGames = bigGames.sortedWith(compareBy({ !it.live }, { if (it.live) 0L else it.game.startMs }))
            orderedGames.take(2).map { HeroSlide.Game(it) } +
                eventPicks.map { HeroSlide.Event(it, leagueMark(leagueLogos, it.sport)) } +
                mediaHeroes.map { HeroSlide.Media(it) } +
                listOfNotNull(holiday?.let { HeroSlide.HolidayCountdown(it) }) +
                listOfNotNull(HeroSlide.Outlook.takeIf { forecast != null })
        }

    /* ── Focus-settle: the hero follows a focused game card once focus rests. A title card never doubles up
       there, because its card right beneath already shows it. ── */
    var focusedTitle by remember { mutableStateOf<TitleFocus?>(null) }

    var settledGame by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedTitle?.gameKey) {
        delay(FOCUS_SETTLE_MS)
        settledGame = focusedTitle?.gameKey
    }
    val focusedGame =
        settledGame?.let { key ->
            scores.firstNotNullOfOrNull { (league, list) ->
                list.firstOrNull { "$league:${it.name}" == key }?.let { BigEvent(league, it, EventReason.LIVE) }
            }
        }
    var rotateTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(bigGames.firstOrNull()?.key) {
        heroIndex = 0
        rotateTick++
    }
    // Hold the slide while a shelf card has the floor, so nothing moves above the remote while it browses.
    LaunchedEffect(slides.size, rotateTick, focusedTitle != null) {
        if (slides.size < 2 || focusedTitle != null) return@LaunchedEffect
        delay(HERO_ROTATE_MS)
        heroIndex = (heroIndex + 1) % slides.size
        rotateTick++
    }
    // On resume (recentsHash bumps) refresh the cached home content so returning to the launcher is fresh.
    LaunchedEffect(recentsHash) {
        watchNext = if (AppPreferences.showWatchNext) WatchNextRepository.load(ctx) else emptyList()
        val c = ProviderSession.current
        if (c != null) {
            val freshChannels = suspendRunCatching { LiveChannels.all(c) }.getOrDefault(emptyList())
            if (ProviderSession.current !== c) return@LaunchedEffect
            channels = freshChannels
            val freshMovies = suspendRunCatching { VodCatalogCache.all(c) }.getOrDefault(emptyList())
            if (ProviderSession.current !== c) return@LaunchedEffect
            movies = freshMovies
        }
        librarySettled = true
    }
    // One small read per session: the names of the provider's film categories. It settles on its own, so a slow
    // answer never holds the shelves back — without it Home falls back to a single Movies row.
    val provider by ProviderSession.currentFlow.collectAsStateWithLifecycle()
    LaunchedEffect(provider) {
        channels = emptyList()
        movies = emptyList()
        vodCategories = emptyList()
        provider?.let { c ->
            val freshChannels = suspendRunCatching { LiveChannels.all(c) }.getOrDefault(emptyList())
            if (ProviderSession.current !== c) return@LaunchedEffect
            channels = freshChannels
            val freshMovies = suspendRunCatching { VodCatalogCache.all(c) }.getOrDefault(emptyList())
            if (ProviderSession.current !== c) return@LaunchedEffect
            movies = freshMovies
            val freshCategories = suspendRunCatching { c.movieCategories() }.getOrDefault(emptyList())
            if (ProviderSession.current !== c) return@LaunchedEffect
            vodCategories = freshCategories
        }
    }
    // The launcher stays composed behind other apps, so polling is tied to the Activity being started.
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        var maxAge = 45_000L
        while (true) {
            scores =
                coroutineScope {
                    LeagueCatalog.DEFAULT_LEAGUES
                        .map { (l, p) ->
                            async { l to ScoreboardRepository.scoreboard(p, maxAgeMs = maxAge) }
                        }.awaitAll()
                        .toMap()
                }
            scoresSettled = true
            maxAge = com.carteltv.tv.data.espn.ScoreRefreshPolicy.SHARE_MS
            delay(
                com.carteltv.tv.data.espn.ScoreRefreshPolicy
                    .nextDelayMs(scores.values.flatten(), System.currentTimeMillis()),
            )
        }
    }
    // Initial load: channels go through the shared cached copy, movies through the provider.
    LaunchedEffect(Unit) {
        ProviderSession.current?.let { c ->
            val freshChannels = suspendRunCatching { LiveChannels.all(c) }.getOrDefault(emptyList())
            if (ProviderSession.current === c) channels = freshChannels
            val freshMovies = suspendRunCatching { VodCatalogCache.all(c) }.getOrDefault(emptyList())
            if (ProviderSession.current === c) movies = freshMovies
        }
    }
    // Keep home content fresh while the launcher is on screen: re-read the cached channel list and
    // re-fetch movies on a slow cadence, so a long-lived launcher never shows stale data.
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (true) {
            delay(10 * 60_000L)
            val c = ProviderSession.current ?: continue
            val freshChannels = suspendRunCatching { LiveChannels.all(c) }.getOrDefault(emptyList())
            if (ProviderSession.current === c) channels = freshChannels
            val freshMovies = suspendRunCatching { VodCatalogCache.all(c) }.getOrDefault(emptyList())
            if (ProviderSession.current === c) movies = freshMovies
        }
    }
    // What each of the first channels is airing now, for the Live now shelf.
    var guide by remember { mutableStateOf<Map<Long, NowOn>>(emptyMap()) }
    val guideSlot = now.time / GUIDE_SLOT_MS
    LaunchedEffect(channels, guideSlot) {
        val c = ProviderSession.current ?: return@LaunchedEffect
        val freshGuide =
            coroutineScope {
                channels
                    .take(LIVE_SHELF_CHANNELS)
                    .map { s ->
                        async {
                            val e = suspendRunCatching { c.shortEpg(s.streamId, 2).firstOrNull() }.getOrNull()
                            val title = e?.title?.takeIf { it.isNotBlank() } ?: return@async null
                            val from = e.startTs?.toLongOrNull()
                            val to = e.stopTs?.toLongOrNull()
                            val nowS = System.currentTimeMillis() / 1000
                            val p =
                                if (from != null && to != null &&
                                    to > from
                                ) {
                                    ((nowS - from).toFloat() / (to - from)).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            s.streamId to NowOn(GuideModel.cleanTitle(title), p)
                        }
                    }.awaitAll()
                    .filterNotNull()
                    .toMap()
            }
        if (ProviderSession.current === c) guide = freshGuide
    }

    var onboard by remember { mutableStateOf(if (AppPreferences.onboardingDone()) -1 else 0) }
    val overlayActive = onboard >= 0 || activeMenu != null || activePin != null
    // Back, and the Home key's reset, return to the top of Home: the list home and the hero holding the floor.
    BackHandler(enabled = !overlayActive) { topTick++ }
    LaunchedEffect(homeResetTick, topTick) {
        if (homeResetTick > 0 || topTick > 0) {
            heroIndex = 0
            heroFocus.awaitFocus()
        }
    }

    val apps = InstalledAppsCache.apps
    // The live games the glance row leads with, read once so the card and its label stay in step.
    val liveNow = remember(scores) { scores.values.flatten().filter { it.state == "in" } }
    val liveLabel = stringResource(com.carteltv.tv.R.string.live_action, liveNow.size)
    val glance =
        remember(air, rates, place.name, forecast, fallbackWeather, liveNow, liveLabel, now) {
            buildList {
                // Sports leads the row's live data whenever a game is on.
                if (liveNow.isNotEmpty()) {
                    add(ShelfEntry.Info("glance:sports", "Sports", { SectionNav.go(AppSection.SPORTS) }, Glance.Live(liveNow, liveLabel, now.time)))
                }
                // Weather from the full forecast, or from the lightweight fallback when the forecast failed.
                val placeName = place.name.ifBlank { "Your location" }
                val weather =
                    forecast?.let { f ->
                        val today = f.days.firstOrNull()
                        Glance.Weather(
                            tempC = f.now.tempC,
                            code = f.now.code,
                            isDay = f.now.isDay,
                            place = placeName,
                            localTime = VenueWeather.localTime(now.time, f.utcOffsetSec, AppPreferences.clock24),
                            highC = today?.maxC,
                            lowC = today?.minC,
                            detail = f.nowcastLine() ?: "Feels like ${WeatherUnits.temp(f.now.feelsC)}",
                        )
                    } ?: fallbackWeather?.let { w ->
                        Glance.Weather(
                            tempC = w.tempC,
                            code = w.code,
                            isDay = w.isDay,
                            place = placeName,
                            localTime = VenueWeather.localTime(now.time, w.utcOffsetSec, AppPreferences.clock24),
                            highC = w.highC,
                            lowC = w.lowC,
                            detail = "Humidity ${w.humidity}%",
                        )
                    }
                weather?.let { add(ShelfEntry.Info("glance:weather", "Weather", { SectionNav.go(AppSection.WEATHER) }, it)) }
                rates?.let { add(ShelfEntry.Info("glance:fx", "Exchange rates", { SectionNav.go(AppSection.WEATHER) }, Glance.Fx(it))) }
                air?.takeIf { AppPreferences.weatherAqi }?.let {
                    add(ShelfEntry.Info("glance:aqi", "Air quality", { SectionNav.go(AppSection.WEATHER) }, Glance.Air(it)))
                }
            }
        }
    val shelves =
        remember(
            recentsHash,
            recentsV2,
            scores,
            bigGames,
            channels,
            movies,
            vodCategories,
            guide,
            watchNext,
            movieRows,
            cwArt,
            apps,
            ytTrending,
            recommended,
            glance,
        ) {
            buildHomeShelves(
                ctx = ctx,
                recents = recentsV2,
                watchNext = watchNext,
                scores = scores,
                bigGames = bigGames,
                channels = channels,
                onNow = guide,
                vodCategories = vodCategories,
                movies = movies,
                cine = movieRows,
                apps = apps,
                cwArt = cwArt,
                ytTrending = ytTrending,
                recommended = recommended,
                glance = glance,
                onPlay = onPlay,
                onCine = { m ->
                    Nav.push(Route.Meta(m.type ?: "movie", m.id, m.name, m.poster))
                },
                onChanged = { recentsHash++ },
                onAppMenu = { activeMenu = it },
                onAppPin = { activePin = it },
            )
        }

    Box(Modifier.fillMaxSize().background(ThemeSemantics.stage)) {
        // Nothing of Home composes behind the first-run tour, so no app row shows until it is finished.
        if (onboard < 0) {
            CompositionLocalProvider(LocalTitleFocus provides { focusedTitle = it }) {
                HomeStage(
                    hero = { m, inset ->
                        HeroSlider(
                            slides = slides,
                            index = heroIndex.coerceIn(0, (slides.size - 1).coerceAtLeast(0)),
                            onIndexChange = {
                                heroIndex = it
                                rotateTick++
                            },
                            onOpen = { slide ->
                                when (slide) {
                                    // An add-on title has no stream URL of its own: OK opens its detail page.
                                    is HeroSlide.Media -> {
                                        if (slide.item.url.isNotBlank()) {
                                            onPlay(slide.item.url, slide.item.title, slide.item.refId)
                                        } else {
                                            movieItems.firstOrNull { it.name == slide.item.title }?.let { m ->
                                                Nav.push(Route.Meta(m.type ?: "movie", m.id, m.name, m.poster))
                                            }
                                                // Failing that it is the greeting, which opens the section it points to.
                                                ?: slide.item.opens?.let(SectionNav::go)
                                        }
                                    }

                                    is HeroSlide.Event -> {
                                        SectionNav.go(AppSection.SPORTS)
                                    }

                                    // A game plays on the viewer's own channel that carries it; only with no match does OK open Sports.
                                    is HeroSlide.Game -> {
                                        if (slide.game.live) {
                                            homeScope.launch {
                                                val match = GameChannelFinder.best(ctx, slide.game.game, slide.game.league)
                                                val client = ProviderSession.current
                                                if (match != null && client != null) {
                                                    onPlay(
                                                        client.liveUrl(match.stream.streamId),
                                                        match.stream.name,
                                                        "live:${match.stream.streamId}",
                                                    )
                                                } else {
                                                    SectionNav.go(AppSection.SPORTS)
                                                }
                                            }
                                        } else {
                                            // An upcoming game has nothing to play yet: OK opens its options sheet, where a
                                            // kickoff alert and the channel pick live.
                                            heroGame = slide.game.league to slide.game.game
                                        }
                                    }

                                    is HeroSlide.HolidayCountdown -> {
                                        Unit
                                    }

                                    else -> {
                                        SectionNav.go(AppSection.WEATHER)
                                    }
                                }
                            },
                            // Up uses Compose's native 2D search to reach the top navigation. Left and Right
                            // turn the hero pages when there is more than one slide.
                            modifier =
                                m.focusProperties {
                                    if (slides.size < 2) {
                                        left = FocusRequester.Cancel
                                        right = FocusRequester.Cancel
                                    }
                                },
                            contentInset = inset,
                            focusedGame = focusedGame,
                            weather = HeroWeather(forecast, place),
                        )
                    },
                    heroFocus = heroFocus,
                    shelves = shelves,
                    entryEnabled = !overlayActive,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Follow and alert changes from the sheet land with the next scores poll; nothing on Home needs them sooner.
        heroGame?.let { (lg, g) ->
            GameSheet(lg, g, onDismiss = { heroGame = null }, onChanged = {})
        }

        val pulseMs = NotificationMirror.lastPulseMs
        var showNotice by remember(pulseMs) {
            mutableStateOf(pulseMs > 0 && System.currentTimeMillis() - pulseMs < 5_000L)
        }
        LaunchedEffect(pulseMs) {
            if (pulseMs > 0) {
                val remaining = 5_000L - (System.currentTimeMillis() - pulseMs)
                if (remaining > 0) {
                    showNotice = true
                    delay(remaining)
                }
                showNotice = false
            }
        }
        // The notice this pulse raised. It outlives the mirror list, so the banner still has one to fade out on.
        val notice = remember(pulseMs) { NotificationMirror.items.firstOrNull() }
        notice?.let { n ->
            // Fades and rises together on the house mid curve instead of popping in and out.
            AnimatedVisibility(
                visible = showNotice,
                enter = fadeIn(ThemeSpec.Motion.tweenMid()) + slideInVertically(ThemeSpec.Motion.tweenMid()) { it / 2 },
                exit = fadeOut(ThemeSpec.Motion.tweenMid()) + slideOutVertically(ThemeSpec.Motion.tweenMid()) { -it / 2 },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Row(
                        Modifier
                            .padding(top = ThemeSpec.Layout.onboardingTopInset)
                            .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                            .background(
                                Brush.verticalGradient(
                                    listOf(ThemeSemantics.surfaceModal.copy(alpha = 0.98f), ThemeSemantics.surfaceRaised.scaleAlpha(0.96f)),
                                ),
                            ).border(ThemeSpec.Glass.rimWidth, ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.corner))
                            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                n.title.ifBlank { n.pkg },
                                fontSize = ThemeSpec.Type.meta,
                                color = ThemeSemantics.textEmphatic,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 520.dp),
                            )
                            if (n.text.isNotBlank()) {
                                Text(
                                    n.text,
                                    fontSize = ThemeSpec.Type.label,
                                    color = ThemeSemantics.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 520.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        AppOverlayHost(
            menu = activeMenu,
            onMenuDismiss = { activeMenu = null },
            pin = activePin,
            onPinClose = { activePin = null },
            onChanged = { recentsHash++ },
        )

        if (onboard >= 0) {
            OnboardingVeil(step = onboard, onNext = { next -> onboard = next }) {
                AppPreferences.setOnboardingDone()
                onboard = -1
            }
        }
    }
}

@Composable
private fun OnboardingVeil(
    step: Int,
    onNext: (Int) -> Unit,
    onFinish: () -> Unit,
) {
    val lessons =
        listOf(
            Triple(
                "01",
                "Quick options",
                "Press and hold OK on any app or show for quick options such as App info, PIN and Hide app.",
            ),
            Triple(
                "02",
                "Navigation",
                "Press Up from the top of the screen to reach the navigation bar. Press Menu anywhere to go back to Home.",
            ),
            Triple(
                "03",
                "Settings & accounts",
                "Sign in to your TV provider, set up profiles, and customize playback at any time in Settings.",
            ),
        )
    val (glyph, head, body) = lessons[step.coerceIn(0, lessons.lastIndex)]
    val ctaReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { suspendRunCatching { ctaReq.awaitFocus() } }
    androidx.activity.compose.BackHandler(enabled = true) { onFinish() }

    Box(
        Modifier
            .fillMaxSize()
            .trapFocus()
            .background(ThemeSemantics.scrimModal)
            .enterScene(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(ThemeSpec.Layout.onboardingWidth).padding(horizontal = ThemeSpec.Space.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                lessons.indices.forEach { i ->
                    Box(
                        Modifier
                            .width(if (i == step) 34.dp else 18.dp)
                            .height(5.dp)
                            .background(
                                if (i ==
                                    step
                                ) {
                                    ThemeSemantics.accent
                                } else {
                                    ThemeSemantics.hairline
                                },
                                RoundedCornerShape(ThemeSpec.Card.cornerSm),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(42.dp))
            // A deco numeral, not a selection: accent stays for the active step marker above it.
            Text(
                glyph,
                fontSize = ThemeSpec.Type.display,
                color = ThemeSemantics.textSecondary,
                fontWeight = FontWeight.Thin,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(ThemeSpec.Space.lg))
            Text(
                head,
                fontSize = ThemeSpec.Type.display,
                fontWeight = FontWeight.Bold,
                color = ThemeSemantics.textEmphatic,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(ThemeSpec.Space.cozy))
            Text(body, fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textPrimary, textAlign = TextAlign.Center, lineHeight = 28.sp)
            Spacer(Modifier.height(46.dp))
            var ctaFocused by remember { mutableStateOf(false) }
            val isLast = step == lessons.lastIndex
            Text(
                if (isLast) "Get started" else "Continue",
                fontSize = ThemeSpec.Type.meta,
                fontWeight = FontWeight.Bold,
                color = if (ctaFocused) ThemeSemantics.textInverse else ThemeSemantics.accent,
                modifier =
                    Modifier
                        .focusRequester(ctaReq)
                        .focusLift(focusScaleState(ctaFocused, ThemeSpec.Focus.liftCompact))
                        .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                        .background(if (ctaFocused) ThemeSemantics.accent else Color.Transparent)
                        .focusRing(ctaFocused, RoundedCornerShape(ThemeSpec.Card.cornerLg))
                        .onFocusChanged { ctaFocused = it.isFocused }
                        .clickableNoIndication { if (isLast) onFinish() else onNext(step + 1) }
                        .padding(horizontal = ThemeSpec.Layout.onboardingButtonPadding, vertical = ThemeSpec.Space.cozy),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Text(
                stringResource(com.carteltv.tv.R.string.home_continue_skip),
                fontSize = ThemeSpec.Type.label,
                color = ThemeSemantics.textSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
