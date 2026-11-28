package com.carteltv.tv.ui.weather

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.weather.Confidence
import com.carteltv.tv.data.weather.ConvRisk
import com.carteltv.tv.data.weather.Forecast
import com.carteltv.tv.data.weather.Lightning
import com.carteltv.tv.data.weather.MarineService
import com.carteltv.tv.data.weather.MarineWeather
import com.carteltv.tv.data.weather.OfficialAlert
import com.carteltv.tv.data.weather.OfficialAlerts
import com.carteltv.tv.data.weather.Place
import com.carteltv.tv.data.weather.Sky
import com.carteltv.tv.data.weather.StationObs
import com.carteltv.tv.data.weather.StormReport
import com.carteltv.tv.data.weather.StormTracker
import com.carteltv.tv.data.weather.Threat
import com.carteltv.tv.data.weather.WeatherObservations
import com.carteltv.tv.data.weather.WeatherService
import com.carteltv.tv.data.weather.WeatherUnits
import com.carteltv.tv.ui.components.Chip
import com.carteltv.tv.ui.components.FilterChip
import com.carteltv.tv.ui.components.PageError
import com.carteltv.tv.ui.components.PageLoading
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.components.rememberAppStarted
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.nav.PageChipRow
import com.carteltv.tv.ui.nav.PagePadding
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.theme.ThemePrimitives
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class Mode(
    val label: String,
) {
    FORECAST("Forecast"),
    RADAR("Radar"),
    STORMS("Storms"),
    SKYSEA("Sky & Sea"),
}

/** Lets a `goto=radar` launch land straight on the radar page. */
object WeatherDeepLink {
    @Volatile var radar = false

    fun consumeRadar(): Boolean = radar.also { radar = false }
}

private enum class Sheet { LOCATION, OPTIONS }

/**
 * Weather, radar, storms, sun/moon and sea. One screen, four pages: the chip row switches pages, the
 * Location sheet picks or auto-detects the place, the Options sheet owns units, refresh and radar behaviour.
 */
@Composable
fun WeatherScreen() {
    // The page stands on a photograph of the sky, so it reads in light ink whatever the weather is.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var place by remember { mutableStateOf(Place.current()) }
    var forecast by remember { mutableStateOf<Forecast?>(null) }
    var failed by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var autoTick by remember { mutableIntStateOf(0) }
    var mode by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(if (WeatherDeepLink.consumeRadar()) Mode.RADAR else Mode.FORECAST)
    }
    var sheet by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf<Sheet?>(null) }
    var ambient by remember { mutableStateOf(AppPreferences.weatherAmbient) }
    var showBoltPref by remember { mutableStateOf(AppPreferences.radarLightning) }
    var feedOn by remember { mutableStateOf(AppPreferences.lightningFeed) }
    var station by remember { mutableStateOf<StationObs?>(null) }
    var stationTick by remember { mutableIntStateOf(0) }
    val showBolt = showBoltPref && feedOn
    var marine by remember { mutableStateOf<MarineWeather?>(null) }
    var marineLoading by remember { mutableStateOf(false) }
    val started =
        com.carteltv.tv.ui.components
            .rememberAppStarted()
    var alerts by remember { mutableStateOf<List<OfficialAlert>?>(null) }
    var alertsFailed by remember { mutableStateOf(false) }
    val radar = remember { RadarState().also { it.recenter(place) } }

    val chipFocus = remember { FocusRequester() }
    val locationFocus = remember { FocusRequester() }
    val optionsFocus = remember { FocusRequester() }
    var opener by remember { mutableStateOf<FocusRequester?>(null) }

    LaunchedEffect(autoTick) {
        if (!AppPreferences.weatherAuto) return@LaunchedEffect
        WeatherService.locateAuto(ctx)?.let {
            if (!it.sameSpot(place)) {
                WeatherService.activate(it)
                place = it
            }
        }
    }
    LaunchedEffect(place.lat, place.lon) {
        radar.recenter(place)
        Lightning.watch(place.lat, place.lon)
        alerts = null
        alertsFailed = false
        station = null
        marine = MarineService.cached(place.lat, place.lon)
    }
    LaunchedEffect(place.lat, place.lon, refreshTick) {
        val f = WeatherService.forecast(place.lat, place.lon, force = refreshTick > 0)
        failed = f == null
        if (f != null) forecast = f
    }
    LaunchedEffect(place.lat, place.lon, refreshTick, mode == Mode.SKYSEA) {
        if (mode != Mode.SKYSEA) return@LaunchedEffect
        marineLoading = marine == null
        marine = MarineService.get(place.lat, place.lon, force = refreshTick > 0) ?: marine
        marineLoading = false
    }
    LaunchedEffect(place.lat, place.lon, refreshTick, mode == Mode.STORMS) {
        if (mode == Mode.STORMS) {
            val fetched = OfficialAlerts.fetch(place.lat, place.lon)
            alertsFailed = fetched == null && OfficialAlerts.covered(place.lat, place.lon)
            alerts = fetched ?: alerts
        }
    }
    LaunchedEffect(started) {
        if (started) {
            while (true) {
                delay(AppPreferences.weatherRefreshMin * 60_000L)
                refreshTick++
            }
        }
    }
    LaunchedEffect(started, mode == Mode.RADAR) {
        if (started && mode == Mode.RADAR) {
            radar.load()
            while (true) {
                delay(4 * 60_000L)
                radar.load(force = true)
            }
        }
    }
    LaunchedEffect(place.lat, place.lon, refreshTick, stationTick) {
        station = if (AppPreferences.weatherUseStation) WeatherObservations.nearest(place.lat, place.lon, force = refreshTick > 0) else null
    }
    DisposableEffect(feedOn, started) {
        val live = feedOn && started
        if (live) Lightning.start(place.lat, place.lon)
        onDispose { if (live) Lightning.stop() }
    }

    val nowMs by produceState(System.currentTimeMillis(), started) {
        if (started) {
            while (true) {
                delay(10_000)
                value =
                    System.currentTimeMillis()
            }
        }
    }
    val report =
        remember(Lightning.version, nowMs, place.lat, place.lon, feedOn) {
            StormTracker.analyze(if (feedOn) Lightning.snapshot() else emptyList(), place.lat, place.lon, nowMs)
        }

    val modelForecast = forecast?.takeIf { abs(it.lat - place.lat) < 0.02 && abs(it.lon - place.lon) < 0.02 }
    val blended = modelForecast?.let { m -> station?.let { m.copy(now = WeatherObservations.blend(m.now, it)) } ?: m }
    val f = blended
    val stationUsed = if (modelForecast != null && blended != null && blended.now != modelForecast.now) station else null
    val outlook = f?.let { StormTracker.outlook(it) }
    val stormAttention =
        if (feedOn && report.threat.ordinal >= Threat.WATCH.ordinal) {
            report.threat.color()
        } else if ((outlook?.peakRisk?.level ?: 0) >= ConvRisk.HIGH.level) {
            outlook?.peakRisk?.color()
        } else {
            null
        }

    BackHandler(enabled = mode != Mode.FORECAST) { mode = Mode.FORECAST }
    LaunchedEffect(sheet) { if (sheet == null) opener?.awaitFocus() }

    Box(Modifier.fillMaxSize()) {
        ScreenPage {
            if (mode == Mode.RADAR) {
                RadarPage(radar, place, showBolt, feedOn, onToggleLightning = {
                    showBoltPref = !showBoltPref
                    AppPreferences.radarLightning =
                        showBoltPref
                })
            } else {
                Column(Modifier.fillMaxSize()) {
                    PageChipRow {
                        Mode.entries.forEach { m ->
                            item(key = m.name) {
                                // Forecast is the entry chip: it takes focus on entry and whenever you come back to that page.
                                FilterChip(
                                    m.label,
                                    mode == m,
                                    icon =
                                        if (m == Mode.STORMS &&
                                            stormAttention != null
                                        ) {
                                            com.carteltv.tv.R.drawable.ph_lightning
                                        } else {
                                            null
                                        },
                                    modifier =
                                        if (m ==
                                            Mode.FORECAST
                                        ) {
                                            Modifier.entryFocus(enabled = mode == Mode.FORECAST, requester = chipFocus)
                                        } else {
                                            Modifier
                                        },
                                ) { mode = m }
                            }
                        }
                        item(key = "loc") {
                            FilterChip("Location", false, modifier = Modifier.focusRequester(locationFocus)) {
                                opener =
                                    locationFocus
                                ; sheet = Sheet.LOCATION
                            }
                        }
                        item(key = "opt") {
                            FilterChip("Options", false, modifier = Modifier.focusRequester(optionsFocus)) {
                                opener = optionsFocus
                                sheet =
                                    Sheet.OPTIONS
                            }
                        }
                        item(key = "ref") {
                            FilterChip("Refresh", false) {
                                refreshTick++
                                autoTick++
                                radar.index = radar.latestIndex
                            }
                        }
                    }

                    when {
                        f == null && failed -> {
                            PageError(
                                androidx.compose.ui.res
                                    .stringResource(com.carteltv.tv.R.string.weather_lost_desk) + ". " +
                                    androidx.compose.ui.res
                                        .stringResource(com.carteltv.tv.R.string.weather_lost_desk_hint),
                            ) { refreshTick++ }
                        }

                        f == null -> {
                            PageLoading()
                        }

                        else -> {
                            LazyVerticalGrid(
                                GridCells.Fixed(WX_COLUMNS),
                                Modifier.fillMaxSize().restoreRowFocus().focusGroup(),
                                contentPadding = PagePadding,
                                verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                            ) {
                                when (mode) {
                                    Mode.FORECAST -> {
                                        forecastItems(
                                            f,
                                            place,
                                            radar,
                                            showBolt,
                                            if (feedOn) report else null,
                                            outlook?.peakRisk ?: ConvRisk.NONE,
                                            stationUsed,
                                            onRadar = { mode = Mode.RADAR },
                                            onStorms = {
                                                mode =
                                                    Mode.STORMS
                                            },
                                        )
                                    }

                                    Mode.STORMS -> {
                                        stormItems(
                                            radar,
                                            place,
                                            f,
                                            report,
                                            Lightning.link,
                                            alerts,
                                            OfficialAlerts.covered(place.lat, place.lon),
                                            feedOn,
                                            onEnableFeed = {
                                                AppPreferences.lightningFeed = true
                                                feedOn = true
                                            },
                                            alertsFailed = alertsFailed,
                                        )
                                    }

                                    Mode.SKYSEA -> {
                                        skySeaItems(f, place, marine, marineLoading)
                                    }

                                    Mode.RADAR -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
        when (sheet) {
            Sheet.LOCATION -> {
                LocationSheet(place, f?.elevationM, onPlace = { place = it }, onAutoChanged = { autoTick++ }, onDismiss = {
                    sheet =
                        null
                })
            }

            Sheet.OPTIONS -> {
                OptionsSheet(
                    radar,
                    onAmbient = { ambient = it },
                    onLightning = { showBoltPref = it },
                    onFeed = { feedOn = it },
                    onStation = { stationTick++ },
                    onDismiss = { sheet = null },
                )
            }

            null -> {}
        }
    }
}

private fun outlookLine(f: Forecast): String {
    val next = f.hours.drop(1).take(12)
    val wetNow = f.now.sky.wet
    if (wetNow) {
        val clear = next.firstOrNull { it.popPct < 30 && !Sky.of(it.code).wet }
        return if (clear != null) "Expected to ease around ${hourLabel(clear.time)}" else "Wet for the next 12 hours"
    }
    val wet = next.firstOrNull { it.popPct >= 50 || Sky.of(it.code).wet }
    return if (wet !=
        null
    ) {
        "${if (Sky.of(wet.code) == Sky.SNOW) "Snow" else "Rain"} likely from ${hourLabel(wet.time)}"
    } else {
        "Dry for the next 12 hours"
    }
}

/** Weather pages are a four-column tile grid; headings, rails and wide cards span the full row. */
internal const val WX_COLUMNS = 4
internal val WX_GAP = ThemeSpec.Space.cozy

internal val weatherFullSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/** Full-span heading. The grid already insets by the gutter, so this adds no horizontal padding. */
internal fun LazyGridScope.weatherSection(
    key: String,
    title: String,
    note: String? = null,
) {
    item(key = key, span = weatherFullSpan, contentType = "section") {
        SectionTitle(title, Modifier.fillMaxWidth().padding(top = ThemeSpec.Space.cozy), note = note)
    }
}

/** Lets a rail inside the gutter-inset grid run edge to edge, so its own content padding lines up with the tiles. */
internal fun Modifier.weatherFullBleed(): Modifier =
    layout { measurable, constraints ->
        val bleed = ThemeSpec.Space.margin.roundToPx()
        val width = constraints.maxWidth + 2 * bleed
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
    }

private fun LazyGridScope.forecastItems(
    f: Forecast,
    place: Place,
    radar: RadarState,
    showBolt: Boolean,
    liveReport: StormReport?,
    risk: ConvRisk,
    station: StationObs?,
    onRadar: () -> Unit,
    onStorms: () -> Unit,
) {
    val today = f.days.firstOrNull() ?: return
    val report = liveReport ?: StormTracker.analyze(emptyList(), 0.0, 0.0)
    val nowcast = f.nowcastLine()?.takeIf { !it.startsWith("No rain") }
    item(key = "now", span = weatherFullSpan, contentType = "hero") {
        Row(Modifier.fillMaxWidth().padding(top = ThemeSpec.Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                WeatherIcon(f.now.sky, Modifier.size(96.dp))
                Spacer(Modifier.width(ThemeSpec.Space.md))
                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            WeatherUnits.tempExact(f.now.tempC).removeSuffix("°"),
                            fontSize = ThemeSpec.Type.temperature,
                            fontWeight = FontWeight.Bold,
                            fontFamily = ThemeSpec.Font.display,
                            color = ThemeSemantics.textEmphatic,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            WeatherUnits.tempSymbol,
                            fontSize = ThemeSpec.Type.title,
                            color = ThemeSemantics.accentBright,
                            modifier = Modifier.padding(top = ThemeSpec.Space.cozy, start = ThemeSpec.Space.xs),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        Sky.describe(f.now.code),
                        fontSize = ThemeSpec.Type.title,
                        color = ThemeSemantics.textEmphatic,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        "Feels ${WeatherUnits.tempExact(
                            f.now.feelsC,
                        )} · H ${WeatherUnits.temp(today.maxC)}  L ${WeatherUnits.temp(today.minC)}",
                        fontSize = ThemeSpec.Type.meta,
                        color = ThemeSemantics.textSecondary,
                        maxLines = 1,
                    )
                    Text(nowcast ?: outlookLine(f), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.accentBright, maxLines = 1)
                    Text(
                        if (station !=
                            null
                        ) {
                            "Measured at ${station.name} · ${WeatherUnits.distance(
                                station.distanceKm,
                            )} away · ${station.ageMin} min ago"
                        } else {
                            "Model estimate for this location"
                        },
                        fontSize = ThemeSpec.Type.label,
                        color = ThemeSemantics.textTertiary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(ThemeSpec.Space.md))
            WeatherFocusCard(Modifier.width(320.dp).height(186.dp), onClick = onRadar) {
                RadarMap(radar, place, Modifier.fillMaxSize(), lightning = showBolt, lightningWindowMin = 30)
                MapBadge("Open radar", Modifier.align(Alignment.BottomStart).padding(ThemeSpec.Space.sm), dot = ThemeSemantics.live)
            }
        }
    }

    if (report.threat.ordinal >= Threat.WATCH.ordinal ||
        risk.level >= ConvRisk.HIGH.level
    ) {
        item(key = "storm-banner", span = weatherFullSpan, contentType = "banner") {
            val tint = if (report.threat.ordinal >= Threat.WATCH.ordinal) report.threat.color() else risk.color()
            WeatherFocusCard(Modifier.padding(top = ThemeSpec.Space.xs).fillMaxWidth(), onClick = onStorms) {
                Row(
                    Modifier.padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BoltGlyph(tint, Modifier.size(22.dp))
                    Spacer(Modifier.width(ThemeSpec.Space.cozy))
                    Column(Modifier.weight(1f)) {
                        // The threat colour lives on the bolt, not the words: at body size the accent is about 3.3:1 on this panel.
                        if (report.threat.ordinal >= Threat.WATCH.ordinal) {
                            Text(
                                report.threat.label,
                                fontSize = ThemeSpec.Type.body,
                                fontWeight = FontWeight.Bold,
                                color = ThemeSemantics.textEmphatic,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            val near =
                                report.nearestKm?.let {
                                    "Lightning ${WeatherUnits.distance(
                                        it,
                                    )} ${WeatherUnits.compass(report.nearestBearing ?: 0)}"
                                }
                                    ?: ""
                            val eta = report.motion?.etaMin?.let { " · overhead in ~$it min" } ?: ""
                            Text(near + eta, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1)
                        } else {
                            Text(
                                androidx.compose.ui.res
                                    .stringResource(com.carteltv.tv.R.string.weather_storm_potential),
                                fontSize = ThemeSpec.Type.body,
                                fontWeight = FontWeight.Bold,
                                color = ThemeSemantics.textEmphatic,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                androidx.compose.ui.res
                                    .stringResource(com.carteltv.tv.R.string.weather_storm_conditions),
                                fontSize = ThemeSpec.Type.meta,
                                color = ThemeSemantics.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        androidx.compose.ui.res
                            .stringResource(com.carteltv.tv.R.string.weather_storms_link),
                        fontSize = ThemeSpec.Type.meta,
                        color = ThemeSemantics.textSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    weatherSection("hourly-title", "Hourly")
    // The hourly strip is a time selector, so it stays a rail.
    item(key = "hourly", span = weatherFullSpan, contentType = "rail") {
        LazyRow(
            Modifier.restoreRowFocus(),
            contentPadding = PaddingValues(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal),
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
        ) {
            itemsIndexed(f.hours.take(30)) { i, h ->
                WeatherFocusCard(Modifier.width(76.dp)) {
                    Column(
                        Modifier.padding(vertical = ThemeSpec.Space.cozy).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (i == 0) "Now" else hourLabel(h.time),
                            fontSize = ThemeSpec.Type.label,
                            fontFamily = ThemeSpec.Font.broadcast,
                            fontWeight = FontWeight.Bold,
                            color = if (i == 0) ThemeSemantics.accentBright else ThemeSemantics.textSecondary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(ThemeSpec.Space.sm))
                        WeatherIcon(Sky.of(h.code), Modifier.size(34.dp))
                        Spacer(Modifier.height(ThemeSpec.Space.sm))
                        Text(
                            WeatherUnits.temp(h.tempC),
                            fontSize = ThemeSpec.Type.body,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = ThemeSpec.Font.mono,
                            color = ThemeSemantics.textEmphatic,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (h.popPct >= 20) "${h.popPct}%" else " ",
                            fontSize = ThemeSpec.Type.label,
                            fontFamily = ThemeSpec.Font.mono,
                            color = ThemeSemantics.info,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    weatherSection("days-title", "Next ${f.days.size} days", note = "Dot shows how closely 4 weather models agree")
    val wMin = f.days.minOf { it.minC }
    val wMax = f.days.maxOf { it.maxC }
    val span = max(wMax - wMin, 1.0)
    item(key = "days", span = weatherFullSpan, contentType = "days") {
        Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
            f.days.forEachIndexed { i, d ->
                WeatherFocusCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.width(120.dp)) {
                            Text(
                                dayName(d.date, i),
                                fontSize = ThemeSpec.Type.body,
                                fontWeight = FontWeight.SemiBold,
                                color = ThemeSemantics.textEmphatic,
                                maxLines = 1,
                            )
                            Text(
                                if (d.modelCount >= 2 &&
                                    i > 0
                                ) {
                                    "${dayShort(d.date)} · ±${"%.1f".format(WeatherUnits.tempDelta(d.maxSpreadC / 2))}°"
                                } else {
                                    dayShort(d.date)
                                },
                                fontSize = ThemeSpec.Type.label,
                                color = ThemeSemantics.textTertiary,
                                maxLines = 1,
                            )
                        }
                        WeatherIcon(Sky.of(d.code), Modifier.size(30.dp))
                        Text(
                            if (d.popMax >=
                                20
                            ) {
                                "${d.popMax}%"
                            } else {
                                ""
                            },
                            fontSize = ThemeSpec.Type.meta,
                            color = ThemeSemantics.info,
                            modifier = Modifier.width(56.dp).padding(start = ThemeSpec.Space.sm),
                            maxLines = 1,
                        )
                        Text(
                            WeatherUnits.temp(d.minC),
                            fontSize = ThemeSpec.Type.body,
                            fontFamily = ThemeSpec.Font.mono,
                            color = ThemeSemantics.textTertiary,
                            modifier = Modifier.width(48.dp),
                            maxLines = 1,
                        )
                        val start = ((d.minC - wMin) / span).toFloat().coerceIn(0f, 1f)
                        val len = ((d.maxC - d.minC) / span).toFloat().coerceIn(0.03f, 1f - start)
                        // The range bar is a thin data bar: straight ends.
                        Row(
                            Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(ThemeSemantics.hairline),
                        ) {
                            if (start > 0.001f) Spacer(Modifier.weight(start))
                            Box(
                                Modifier
                                    .weight(
                                        len,
                                    ).fillMaxHeight()
                                    .clip(
                                        RoundedCornerShape(2.dp),
                                    ).background(Brush.horizontalGradient(listOf(ThemeSemantics.info, ThemeSemantics.accent))),
                            )
                            val rest = 1f - start - len
                            if (rest > 0.001f) Spacer(Modifier.weight(rest))
                        }
                        Text(
                            WeatherUnits.temp(d.maxC),
                            fontSize = ThemeSpec.Type.body,
                            fontWeight = FontWeight.Bold,
                            fontFamily = ThemeSpec.Font.mono,
                            color = ThemeSemantics.textEmphatic,
                            modifier = Modifier.width(52.dp).padding(start = ThemeSpec.Space.sm),
                            maxLines = 1,
                        )
                        val dot =
                            when (d.confidence) {
                                Confidence.HIGH -> ThemeSemantics.success
                                Confidence.MEDIUM -> ThemeSemantics.textTertiary
                                Confidence.LOW -> ThemeSemantics.textTertiary
                                null -> Color.Transparent
                            }
                        Box(
                            Modifier
                                .padding(start = ThemeSpec.Space.sm)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dot),
                        )
                    }
                }
            }
        }
    }

    weatherSection("conditions-title", "Conditions")
    item(key = "wind", contentType = "tile") {
        WeatherTile(
            "Wind",
            WeatherUnits.wind(f.now.windKmh),
            sub = "From ${WeatherUnits.compass(f.now.windDir)} · gusts ${WeatherUnits.windValue(f.now.gustKmh)}",
            visual = { CompassArrow(((f.now.windDir + 180) % 360).toFloat(), ThemeSemantics.accentBright) },
        )
    }
    item(key = "humidity", contentType = "tile") {
        WeatherTile("Humidity", "${f.now.humidity}%", sub = "Dew point ${WeatherUnits.temp(f.now.dewC)}")
    }
    item(key = "pressure", contentType = "tile") { WeatherTile("Pressure", WeatherUnits.pressure(f.now.pressureHpa), sub = "Sea level") }
    item(key = "uv", contentType = "tile") {
        WeatherTile("UV index", "%.1f".format(f.now.uv), sub = "${WeatherUnits.uvLabel(f.now.uv)} · peak ${"%.0f".format(today.uvMax)}")
    }
    item(key = "visibility", contentType = "tile") {
        WeatherTile(
            "Visibility",
            WeatherUnits.visibility(f.now.visibilityM),
            sub =
                if (f.now.visibilityM >=
                    10_000
                ) {
                    "Clear"
                } else if (f.now.visibilityM >= 4_000) {
                    "Moderate"
                } else {
                    "Poor"
                },
        )
    }
    item(key = "cloud", contentType = "tile") { WeatherTile("Cloud cover", "${f.now.cloud}%", sub = Sky.describe(f.now.code)) }
    item(key = "rain", contentType = "tile") {
        WeatherTile("Rain", WeatherUnits.precip(f.now.precipMm), sub = "Today ${WeatherUnits.precip(today.precipMm)}")
    }
    item(
        key = "sunset",
        contentType = "tile",
    ) { WeatherTile("Sunset", clockText(today.sunset), sub = "Sunrise ${clockText(today.sunrise)}") }
    item(key = "credits", span = weatherFullSpan, contentType = "footer") {
        Text(
            "Forecast: Open-Meteo.com (CC BY 4.0), best-match model, cross-checked against ECMWF, GFS and ICON. " +
                "Observations: METAR via aviationweather.gov · Elevation ${WeatherUnits.altitude(f.elevationM)}",
            fontSize = ThemeSpec.Type.label,
            color = ThemeSemantics.textTertiary,
            modifier = Modifier.padding(vertical = ThemeSpec.Space.md),
        )
    }
}
