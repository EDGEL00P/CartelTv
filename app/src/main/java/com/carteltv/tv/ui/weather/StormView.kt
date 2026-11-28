package com.carteltv.tv.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.carteltv.tv.data.weather.Forecast
import com.carteltv.tv.data.weather.Lightning
import com.carteltv.tv.data.weather.OfficialAlert
import com.carteltv.tv.data.weather.Place
import com.carteltv.tv.data.weather.StormReport
import com.carteltv.tv.data.weather.StormTracker
import com.carteltv.tv.data.weather.Threat
import com.carteltv.tv.data.weather.WeatherUnits
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.ClockFormat
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlin.math.roundToInt

private fun capeWord(c: Double) =
    when {
        c < 300 -> "Weak"
        c < 1000 -> "Moderate"
        c < 2500 -> "Strong"
        c < 4000 -> "Very strong"
        else -> "Extreme"
    }

private fun liftedWord(li: Double) =
    when {
        li > 0 -> "Stable"
        li > -3 -> "Marginal"
        li > -6 -> "Unstable"
        else -> "Very unstable"
    }

private fun untilText(ms: Long?): String? =
    ms?.let {
        "Until " + ClockFormat.dayTime(it, com.carteltv.tv.data.preferences.AppPreferences.clock24)
    }

/** The storm tracker, as sections of the screen-owned tile grid so the whole weather screen scrolls as one. */
fun LazyGridScope.stormItems(
    radar: RadarState,
    place: Place,
    f: Forecast,
    report: StormReport,
    link: Lightning.Link,
    alerts: List<OfficialAlert>?,
    alertsCovered: Boolean,
    feedOn: Boolean,
    onEnableFeed: () -> Unit,
    alertsFailed: Boolean = false,
) {
    val outlook = StormTracker.outlook(f)
    val peak = outlook.peak ?: f.hours.firstOrNull()

    item(key = "map", span = weatherFullSpan, contentType = "hero") {
        Row(
            Modifier.fillMaxWidth().padding(top = ThemeSpec.Space.xs).height(250.dp),
            horizontalArrangement = Arrangement.spacedBy(WX_GAP),
        ) {
            Box(
                Modifier
                    .weight(1.35f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                    .border(1.dp, ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.cornerLg)),
            ) {
                RadarMap(
                    radar,
                    place,
                    Modifier.fillMaxSize(),
                    lightning = feedOn,
                    lightningWindowMin = 60,
                    rings = feedOn,
                    animate = false,
                    zoomOverride = 7,
                    staticLatest = true,
                )
                MapBadge(
                    if (feedOn) "Strikes · last 60 min" else "Latest radar",
                    Modifier.align(Alignment.TopStart).padding(ThemeSpec.Space.sm),
                    dot = if (feedOn) ThemeSemantics.accentBright else ThemeSemantics.live,
                )
                Text(
                    if (feedOn) "Lightning © Blitzortung.org · Radar © RainViewer" else "Radar © RainViewer",
                    fontSize = ThemeSpec.Type.label,
                    color = ThemeSemantics.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomStart).padding(ThemeSpec.Space.sm),
                )
            }
            if (feedOn) {
                WeatherFocusCard(Modifier.weight(1f).fillMaxHeight()) { ThreatCard(report, link) }
            } else {
                WeatherFocusCard(Modifier.weight(1f).fillMaxHeight()) { FeedOffCard(onEnableFeed) }
            }
        }
    }

    if (feedOn) weatherSection("rings-title", "Strikes by distance", note = "last 15 minutes")
    if (feedOn) {
        StormTracker.rings.forEach { km ->
            val n = report.within[km] ?: 0
            item(key = "ring-$km", contentType = "tile") {
                WeatherTile(
                    "Within ${WeatherUnits.distance(km.toDouble())}",
                    n.toString(),
                    sub =
                        if (n ==
                            0
                        ) {
                            "None detected"
                        } else if (n == 1) {
                            "1 strike"
                        } else {
                            "$n strikes"
                        },
                    valueColor = if (n > 0) ThemeSemantics.accentBright else ThemeSemantics.textEmphatic,
                )
            }
        }
    }

    weatherSection("tracker-title", "Storm tracker")
    val mo = report.motion
    if (feedOn) {
        item(key = "motion", contentType = "tile") {
            WeatherTile(
                "Storm motion",
                mo?.let { WeatherUnits.wind(it.speedKmh.toDouble()) } ?: "—",
                sub =
                    when {
                        mo == null -> "Not enough data"
                        mo.etaMin != null -> "Overhead in ~${mo.etaMin} min"
                        else -> "Not approaching"
                    },
                visual = mo?.let { { CompassArrow(it.bearingDeg.toFloat(), ThemeSemantics.accentBright) } },
            )
        }
    }
    item(key = "potential", contentType = "tile") {
        WeatherTile(
            "Storm potential · 24 h",
            outlook.peakRisk.label,
            valueColor = outlook.peakRisk.color(),
            sub = if (peak != null && outlook.peakRisk.level > 0) "Peaks at ${hourLabel(peak.time)}" else "No instability expected",
        )
    }
    item(key = "gusts", contentType = "tile") { WeatherTile("Peak gusts", WeatherUnits.wind(outlook.maxGustKmh), sub = "Next 24 h") }
    item(key = "rain24", contentType = "tile") {
        WeatherTile("Rain · 24 h", WeatherUnits.precip(outlook.totalMm), sub = "Up to ${outlook.maxPop}% chance")
    }

    weatherSection("outlook-title", "Convective outlook", note = "CAPE by hour")
    // The CAPE-by-hour strip is a time selector, so it stays a rail.
    item(key = "outlook", span = weatherFullSpan, contentType = "rail") {
        LazyRow(
            Modifier.restoreRowFocus(),
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    start = ThemeSpec.Safe.horizontal,
                    end = ThemeSpec.Safe.horizontal,
                ),
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
        ) {
            itemsIndexed(f.hours.take(24)) { _, h ->
                val risk = StormTracker.risk(h)
                WeatherFocusCard(Modifier.width(60.dp)) {
                    Column(
                        Modifier.padding(vertical = ThemeSpec.Space.sm).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(hourLabel(h.time), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1)
                        Spacer(Modifier.height(ThemeSpec.Space.sm))
                        Box(Modifier.height(64.dp).width(16.dp), contentAlignment = Alignment.BottomCenter) {
                            // The CAPE bar is a thin data bar: straight ends.
                            Box(Modifier.fillMaxWidth().height((64 * (h.capeJ / 3500.0).coerceIn(0.05, 1.0)).dp).background(risk.color()))
                        }
                        Spacer(Modifier.height(ThemeSpec.Space.sm))
                        Text(
                            if (h.capeJ >=
                                1000
                            ) {
                                "%.1fk".format(h.capeJ / 1000)
                            } else {
                                h.capeJ.roundToInt().toString()
                            },
                            fontSize = ThemeSpec.Type.meta,
                            color = ThemeSemantics.textEmphatic,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            WeatherUnits.windValue(h.gustKmh),
                            fontSize = ThemeSpec.Type.label,
                            color = ThemeSemantics.textTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    if (peak != null) {
        weatherSection(
            "peak-title",
            if (outlook.peakRisk.level >
                0
            ) {
                "Conditions at peak"
            } else {
                "Conditions now"
            },
            note = hourLabel(peak.time),
        )
        item(
            key = "cape",
            contentType = "tile",
        ) { WeatherTile("CAPE", "%,d J/kg".format(peak.capeJ.roundToInt()), sub = capeWord(peak.capeJ)) }
        item(key = "lifted", contentType = "tile") {
            WeatherTile("Lifted index", "%.1f".format(peak.liftedIndex), sub = liftedWord(peak.liftedIndex))
        }
        item(key = "cin", contentType = "tile") {
            WeatherTile(
                "Inhibition",
                "%d J/kg".format(peak.cinJ.roundToInt()),
                sub =
                    if (peak.cinJ <=
                        -200
                    ) {
                        "Strong"
                    } else if (peak.cinJ <= -50) {
                        "Moderate"
                    } else {
                        "Minimal"
                    },
            )
        }
        item(key = "freezing", contentType = "tile") {
            WeatherTile(
                "Freezing level",
                WeatherUnits.altitude(peak.freezingLevelM),
                sub =
                    if (peak.freezingLevelM <
                        2500
                    ) {
                        "Low · hail melts less"
                    } else {
                        "High · hail melts more"
                    },
            )
        }
    }

    weatherSection("warnings-title", "Official warnings")
    when {
        !alertsCovered -> {
            notice(
                "warnings-none",
                "Official warnings aren't available for this region. Check your national weather service for watches and warnings.",
            )
        }

        alertsFailed && alerts.isNullOrEmpty() -> {
            notice(
                "warnings-failed",
                "Official warnings couldn't be checked just now. This does not mean there are none. Check weather.gov or your local alerts.",
            )
        }

        alerts == null -> {
            notice("warnings-loading", "Checking National Weather Service warnings…")
        }

        alerts.isEmpty() -> {
            notice("warnings-clear", "No active warnings for this location. Source: National Weather Service")
        }

        else -> {
            itemsIndexed(alerts.take(5), key = {
                i,
                a,
                ->
                "alert-$i-${a.event}"
            }, span = { _, _ -> GridItemSpan(2) }, contentType = { _, _ -> "alert" }) { _, a ->
                WeatherFocusCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(ThemeSpec.Space.cozy)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                a.event,
                                fontSize = ThemeSpec.Type.body,
                                fontWeight = FontWeight.Bold,
                                color = ThemeSemantics.textEmphatic,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(ThemeSpec.Space.sm))
                            WeatherPill(
                                a.severity.ifBlank { "Alert" },
                                if (a.severity == "Extreme" ||
                                    a.severity == "Severe"
                                ) {
                                    ThemeSemantics.danger
                                } else {
                                    ThemeSemantics.accent
                                },
                            )
                        }
                        Spacer(Modifier.height(ThemeSpec.Space.xs))
                        Text(
                            a.headline,
                            fontSize = ThemeSpec.Type.meta,
                            color = ThemeSemantics.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        untilText(a.endsMs)?.let {
                            Text(
                                it,
                                fontSize = ThemeSpec.Type.label,
                                color = ThemeSemantics.textTertiary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    item(key = "disclaimer", span = weatherFullSpan, contentType = "footer") {
        Text(
            (if (feedOn) "Lightning comes from the unofficial Blitzortung.org community feed, a partial sample that may miss strikes. " else "") +
                "Storm potential is model output from Open-Meteo. This is a guide, not a safety system.",
            fontSize = ThemeSpec.Type.label,
            color = ThemeSemantics.textTertiary,
            modifier = Modifier.padding(vertical = ThemeSpec.Space.md),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private fun LazyGridScope.notice(
    key: String,
    text: String,
) {
    item(key = key, span = weatherFullSpan, contentType = "notice") { NoticeCard(text) }
}

@Composable
private fun NoticeCard(text: String) {
    WeatherFocusCard(Modifier.fillMaxWidth()) {
        Text(text, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, modifier = Modifier.padding(ThemeSpec.Space.cozy))
    }
}

@Composable
private fun ThreatCard(
    report: StormReport,
    link: Lightning.Link,
) {
    val c = report.threat.color()
    Column(Modifier.fillMaxSize().padding(ThemeSpec.Space.md), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoltGlyph(c, Modifier.size(20.dp))
                Spacer(Modifier.width(ThemeSpec.Space.sm))
                Text(
                    androidx.compose.ui.res
                        .stringResource(com.carteltv.tv.R.string.storm_lightning_threat),
                    fontSize = ThemeSpec.Type.label,
                    color = ThemeSemantics.textTertiary,
                    letterSpacing = ThemeSpec.Type.trackCaps,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Text(
                report.threat.label,
                fontSize = ThemeSpec.Type.titleL,
                fontWeight = FontWeight.Bold,
                fontFamily = ThemeSpec.Font.display,
                color = c,
                maxLines = 1,
            )
            Spacer(Modifier.height(ThemeSpec.Space.xs))
            Text(
                report.threat.advice,
                fontSize = ThemeSpec.Type.meta,
                color = ThemeSemantics.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
            StatLine(
                "Nearest",
                report.nearestKm?.let { "${WeatherUnits.distance(it)} ${WeatherUnits.compass(report.nearestBearing ?: 0)}" }
                    ?: "None in 30 min",
            )
            StatLine("Last strike", report.lastStrikeAgoSec?.let(::agoText) ?: "None in 60 min")
            StatLine(
                "Rate",
                if (report.perMinute >
                    0
                ) {
                    "%.1f / min within %s".format(report.perMinute, WeatherUnits.distance(100.0))
                } else {
                    "0 / min"
                },
            )
            StatLine("Last hour", "${report.total60} strikes")
            Spacer(Modifier.height(ThemeSpec.Space.xs))
            val rate = Lightning.feedPerMin()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape).background(
                        if (link ==
                            Lightning.Link.LIVE
                        ) {
                            ThemeSemantics.success
                        } else {
                            ThemeSemantics.textTertiary
                        },
                    ),
                )
                Spacer(Modifier.width(ThemeSpec.Space.sm))
                Text(
                    when (link) {
                        Lightning.Link.LIVE -> if (rate < 60) "Live · $rate/min worldwide · partial data" else "Live · $rate/min worldwide"
                        Lightning.Link.CONNECTING -> "Connecting…"
                        Lightning.Link.RECONNECTING -> "Reconnecting…"
                        Lightning.Link.OFF -> "Not connected"
                    },
                    fontSize = ThemeSpec.Type.label,
                    color = ThemeSemantics.textTertiary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FeedOffCard(onEnable: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(ThemeSpec.Space.md), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoltGlyph(ThemeSemantics.textTertiary, Modifier.size(20.dp))
                Spacer(Modifier.width(ThemeSpec.Space.sm))
                Text(
                    androidx.compose.ui.res
                        .stringResource(com.carteltv.tv.R.string.storm_live_lightning),
                    fontSize = ThemeSpec.Type.label,
                    color = ThemeSemantics.textTertiary,
                    letterSpacing = ThemeSpec.Type.trackCaps,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Text(
                androidx.compose.ui.res
                    .stringResource(com.carteltv.tv.R.string.storm_off),
                fontSize = ThemeSpec.Type.titleL,
                fontWeight = FontWeight.Bold,
                fontFamily = ThemeSpec.Font.display,
                fontStyle = FontStyle.Italic,
                color = ThemeSemantics.textSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Text(
                "The only free live source is the Blitzortung.org community stream. It is unofficial, covers a sample of strikes, " +
                    "and its operators restrict use in apps, so it stays off unless you choose to turn it on.",
                fontSize = ThemeSpec.Type.meta,
                color = ThemeSemantics.textSecondary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ActionButton("Turn on", filled = false, onClick = onEnable)
    }
}

@Composable
private fun StatLine(
    label: String,
    value: String,
) {
    Row {
        Text(label, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textTertiary, modifier = Modifier.width(78.dp), maxLines = 1)
        Text(
            value,
            fontSize = ThemeSpec.Type.meta,
            color = ThemeSemantics.textEmphatic,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
