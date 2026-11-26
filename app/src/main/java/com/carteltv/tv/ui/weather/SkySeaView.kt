package com.carteltv.tv.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carteltv.tv.data.weather.SunMoonTimes
import com.carteltv.tv.data.weather.Forecast
import com.carteltv.tv.data.weather.MarineWeather
import com.carteltv.tv.data.weather.Place
import com.carteltv.tv.data.weather.WeatherUnits
import com.carteltv.tv.ui.components.ClockFormat
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Sun, moon and sea in one calm page: two half-width cards for the sky, then the water tiles if the place has any. */
fun LazyGridScope.skySeaItems(f: Forecast, place: Place, marine: MarineWeather?, marineLoading: Boolean) {
    weatherSection("sun-moon-title", "Sun & moon")
    item(key = "sun", span = { GridItemSpan(2) }, contentType = "sky-card") { SunCardItem(f, place) }
    item(key = "moon", span = { GridItemSpan(2) }, contentType = "sky-card") { MoonCardItem(f, place) }
    weatherSection("sea-title", "Sea")
    when {
        marine != null -> {
            val next = marine.nextTide(high = marine.rising)
            item(key = "tide", contentType = "tile") {
                WeatherTile(
                    "Tide", if (marine.rising) "Rising" else "Falling", valueColor = ThemeSemantics.info,
                    sub = next?.let { "${if (it.high) "High" else "Low"} ${minuteText(it.minute)}" } ?: "—",
                )
            }
            item(key = "waves", contentType = "tile") { WeatherTile("Waves", WeatherUnits.length(marine.now.waveM), sub = "${marine.now.wavePeriodS.roundToInt()} s · from ${WeatherUnits.compass(marine.now.waveDir)}") }
            item(key = "swell", contentType = "tile") { WeatherTile("Swell", WeatherUnits.length(marine.now.swellM), sub = "${marine.now.swellPeriodS.roundToInt()} s · from ${WeatherUnits.compass(marine.now.swellDir)}") }
            item(key = "sea-temp", contentType = "tile") { WeatherTile("Sea temperature", marine.now.seaTempC?.let { WeatherUnits.temp(it) } ?: "—", sub = marine.seaState) }
            item(key = "tide-chart", span = weatherFullSpan, contentType = "chart") { TideChart(marine, Modifier.padding(top = ThemeSpec.Space.xs)) }
            item(key = "sea-credits", span = weatherFullSpan, contentType = "footer") {
                Text(
                    (if (marine.tideStation != null) "Tides: NOAA harmonic predictions for ${marine.tideStation}, ${WeatherUnits.distance(marine.tideStationKm)} away, heights above MLLW. "
                    else "Tides are a model estimate on a coarse ocean grid (about 8 km), so timing and height may be inaccurate near the coast. ") +
                        "Not for navigation. Waves, swell and sea temperature: Open-Meteo Marine.",
                    fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, modifier = Modifier.padding(vertical = ThemeSpec.Space.sm),
                )
            }
        }
        else -> item(key = "sea-empty", span = weatherFullSpan, contentType = "notice") {
            WeatherFocusCard(Modifier.fillMaxWidth()) {
                Text(
                    if (marineLoading) "Loading sea data…" else "No sea data for this location. Choose a coastal place for tides, waves and sea temperature.",
                    fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, modifier = Modifier.padding(ThemeSpec.Space.md), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
    item(key = "end-pad", span = weatherFullSpan, contentType = "spacer") { Spacer(Modifier.height(ThemeSpec.Space.lg)) }
}

@Composable
private fun SunCardItem(f: Forecast, place: Place) {
    val midnight = f.localMidnightMs()
    val sun = remember(midnight, place.lat, place.lon) { SunMoonTimes.sunDay(midnight, place.lat, place.lon) }
    val nowMs by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val nowMin = (((nowMs - midnight) / 60_000L).toInt()).coerceIn(0, 1439)
    WeatherFocusCard(Modifier.fillMaxWidth()) { SunCard(sun, nowMin, place) }
}

@Composable
private fun MoonCardItem(f: Forecast, place: Place) {
    val midnight = f.localMidnightMs()
    val nowMs by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val moon = remember(midnight, place.lat, place.lon, nowMs / 600_000) { SunMoonTimes.moonDay(nowMs, midnight, place.lat, place.lon) }
    WeatherFocusCard(Modifier.fillMaxWidth()) { MoonCard(moon, place.lat < 0) }
}

@Composable
private fun SunCard(s: SunMoonTimes.SunDay, nowMin: Int, place: Place) {
    Column(Modifier.padding(ThemeSpec.Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.sky_sun), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            s.dayLengthMin?.let {
                Text(durationText(it), fontSize = ThemeSpec.Type.body, fontWeight = FontWeight.SemiBold, color = ThemeSemantics.textEmphatic, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                s.dayDeltaSec?.takeIf { d -> d != 0 }?.let { d ->
                    Spacer(Modifier.width(ThemeSpec.Space.sm))
                    WeatherPill("${if (d > 0) "+" else "−"}${abs(d) / 60}m ${abs(d) % 60}s", if (d > 0) ThemeSemantics.accentBright else ThemeSemantics.textTertiary)
                }
            }
        }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        when {
            s.polarNight -> Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.sky_polar_night), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, modifier = Modifier.height(70.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            s.midnightSun -> Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.sky_midnight_sun), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, modifier = Modifier.height(70.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            else -> SunArc(s.sunrise, s.sunset, nowMin, Modifier.fillMaxWidth().height(70.dp))
        }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Row {
            TimeCell("Dawn", s.dawn, Modifier.weight(1f))
            TimeCell("Sunrise", s.sunrise, Modifier.weight(1f))
            TimeCell("Solar noon", s.noon, Modifier.weight(1f))
        }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Row {
            TimeCell("Golden hour", s.goldenPm, Modifier.weight(1f))
            TimeCell("Sunset", s.sunset, Modifier.weight(1f))
            TimeCell("Dusk", s.dusk, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TimeCell(label: String, minute: Int?, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, maxLines = 1)
        Text(minute?.let(::minuteText) ?: "—", fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textEmphatic, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SunArc(rise: Int?, set: Int?, nowMin: Int, modifier: Modifier) {
    if (rise == null || set == null) return
    val track = ThemeSemantics.hairline
    val line = ThemeSemantics.textTertiary
    val lit = ThemeSemantics.accent
    val dot = ThemeSemantics.accentBright
    val stage = ThemeSemantics.surfaceRest
    Canvas(modifier) {
        val pad = 10.dp.toPx()
        val base = size.height - 6.dp.toPx()
        val arcH = base - 8.dp.toPx()
        fun at(frac: Float) = Offset(pad + (size.width - 2 * pad) * frac, base - sin(PI * frac).toFloat() * arcH)
        val full = Path().apply { moveTo(at(0f).x, at(0f).y); for (i in 1..60) at(i / 60f).let { lineTo(it.x, it.y) } }
        drawLine(line.copy(alpha = 0.5f), Offset(0f, base), Offset(size.width, base), strokeWidth = 1.dp.toPx())
        drawPath(full, track, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 7.dp.toPx()))))
        val frac = ((nowMin - rise).toFloat() / (set - rise)).coerceIn(0f, 1f)
        if (nowMin in rise..set) {
            val done = Path().apply { moveTo(at(0f).x, at(0f).y); for (i in 1..(frac * 60).roundToInt().coerceAtLeast(1)) at(i / 60f).let { lineTo(it.x, it.y) } }
            drawPath(done, lit, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            val p = at(frac)
            drawCircle(dot.copy(alpha = 0.28f), 12.dp.toPx(), p)
            drawCircle(stage, 7.dp.toPx(), p)
            drawCircle(dot, 5.dp.toPx(), p)
        }
    }
}

@Composable
private fun MoonCard(m: SunMoonTimes.MoonDay, southern: Boolean) {
    Column(Modifier.padding(ThemeSpec.Space.md)) {
        Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.sky_moon), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoonDisc(m.phaseDeg, southern, Modifier.size(76.dp))
            Spacer(Modifier.width(ThemeSpec.Space.md))
            Column {
                Text(m.name, fontSize = ThemeSpec.Type.title, fontWeight = FontWeight.SemiBold, color = ThemeSemantics.textEmphatic, maxLines = 1)
                Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.sky_moon_lit, (m.illumination * 100).roundToInt(), m.ageDays.roundToInt()), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1)
                Text(if (m.altNowDeg > 0) "Above the horizon" else "Below the horizon", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textTertiary, maxLines = 1)
            }
        }
        Spacer(Modifier.height(ThemeSpec.Space.cozy))
        Row {
            TimeCell("Moonrise", m.moonrise, Modifier.weight(1f))
            TimeCell("Moonset", m.moonset, Modifier.weight(1f))
        }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Row {
            Column(Modifier.weight(1f)) {
                Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.sky_full_moon), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(ClockFormat.dateMedium(m.nextFullMs), fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textEmphatic, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Column(Modifier.weight(1f)) {
                Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.sky_new_moon), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(ClockFormat.dateMedium(m.nextNewMs), fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textEmphatic, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        if (m.springTides) {
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            WeatherPill("Spring tides: larger range", ThemeSemantics.info)
        }
    }
}

/** Lit part of the disc from the real elongation. The lit limb flips in the southern hemisphere. */
@Composable
fun MoonDisc(phaseDeg: Double, southern: Boolean, modifier: Modifier = Modifier.size(76.dp)) {
    val lit = ThemeSemantics.textPrimary
    val dark = ThemeSemantics.surfaceModal
    val rim = ThemeSemantics.hairline
    Canvas(modifier) {
        val r = size.minDimension / 2 - 2.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(dark, r, c)
        val waxing = phaseDeg < 180
        val litRight = waxing != southern
        val k = cos(Math.toRadians(phaseDeg)).toFloat()               // +1 new … -1 full
        val half = Path().apply {
            arcTo(Rect(c, r), if (litRight) -90f else 90f, 180f, true)
            close()
        }
        val terminator = Path().apply { addOval(Rect(c.x - r * abs(k), c.y - r, c.x + r * abs(k), c.y + r)) }
        val crescent = k > 0
        val shape = if (crescent) Path.combine(PathOperation.Difference, half, terminator) else Path.combine(PathOperation.Union, half, terminator)
        if (phaseDeg in 8.0..352.0) drawPath(shape, lit, style = Fill)
        if (phaseDeg !in 8.0..352.0) drawCircle(rim, r, c, style = Stroke(1.5.dp.toPx()))
        else drawCircle(rim, r, c, style = Stroke(1.dp.toPx()))
    }
}

/** 48 hours of tide: filled curve, turning points labelled, a marker for now. */
@Composable
private fun TideChart(m: MarineWeather, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val water = ThemeSemantics.info
    val now = ThemeSemantics.accentBright
    val axis = ThemeSemantics.textTertiary
    val label = TextStyle(fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textEmphatic, fontWeight = FontWeight.SemiBold)
    val tick = TextStyle(fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary)
    val startIdx = (m.nowMinute / 60 - 3).coerceAtLeast(0)
    val endIdx = min(m.levels.lastIndex, startIdx + 48)
    WeatherFocusCard(modifier.fillMaxWidth().height(190.dp)) {
        Box(Modifier.fillMaxSize().padding(ThemeSpec.Space.cozy)) {
            Text(if (m.tideStation != null) "TIDE · ${m.tideStation.uppercase()}" else "TIDE · MODEL ESTIMATE", fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, letterSpacing = ThemeSpec.Type.trackCaps, modifier = Modifier.align(Alignment.TopStart), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.sky_range, WeatherUnits.length(m.rangeM)), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, modifier = Modifier.align(Alignment.TopEnd), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Canvas(Modifier.fillMaxSize().padding(top = ThemeSpec.Space.lg)) {
                val lo = (startIdx..endIdx).minOf { m.levels[it] }
                val hi = (startIdx..endIdx).maxOf { m.levels[it] }
                val span = max(hi - lo, 0.2)
                val padTop = 26.dp.toPx(); val padBottom = 26.dp.toPx()
                val h = size.height - padTop - padBottom
                fun x(i: Double) = ((i - startIdx) / (endIdx - startIdx) * size.width).toFloat()
                fun y(v: Double) = (padTop + (1 - (v - lo) / span) * h).toFloat()

                val curve = Path().apply {
                    moveTo(x(startIdx.toDouble()), y(m.levels[startIdx]))
                    for (i in startIdx + 1..endIdx) {
                        val mx = (x(i - 1.0) + x(i.toDouble())) / 2
                        cubicTo(mx, y(m.levels[i - 1]), mx, y(m.levels[i]), x(i.toDouble()), y(m.levels[i]))
                    }
                }
                val area = Path().apply {
                    addPath(curve); lineTo(x(endIdx.toDouble()), size.height - padBottom + 6.dp.toPx()); lineTo(x(startIdx.toDouble()), size.height - padBottom + 6.dp.toPx()); close()
                }
                drawPath(area, Brush.verticalGradient(listOf(water.copy(alpha = 0.38f), Color.Transparent), startY = padTop, endY = size.height))
                drawPath(curve, water, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))

                for (i in startIdx..endIdx) if (i % 6 == 0) {
                    val t = measurer.measure(minuteText(i * 60), tick)
                    drawText(t, topLeft = Offset((x(i.toDouble()) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), size.height - t.size.height))
                    drawLine(axis.copy(alpha = 0.25f), Offset(x(i.toDouble()), padTop), Offset(x(i.toDouble()), size.height - padBottom), strokeWidth = 1.dp.toPx())
                }

                m.tides.filter { it.minute in startIdx * 60..endIdx * 60 }.forEach { e ->
                    val px = x(e.minute / 60.0); val py = y(e.levelM)
                    drawCircle(water, 4.dp.toPx(), Offset(px, py))
                    val t = measurer.measure("${minuteText(e.minute)} · ${WeatherUnits.length(e.levelM)}", label)
                    val ty = if (e.high) py - t.size.height - 6.dp.toPx() else py + 6.dp.toPx()
                    drawText(t, topLeft = Offset((px - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), ty))
                }

                val nx = x(m.nowMinute / 60.0)
                val level = m.levels[min(m.nowMinute / 60, m.levels.lastIndex)]
                drawLine(now, Offset(nx, padTop - 8.dp.toPx()), Offset(nx, size.height - padBottom), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                drawCircle(now, 5.dp.toPx(), Offset(nx, y(level)))
            }
        }
    }
}
