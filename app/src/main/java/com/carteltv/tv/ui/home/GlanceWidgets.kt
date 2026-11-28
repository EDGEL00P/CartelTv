package com.carteltv.tv.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.carteltv.tv.data.espn.EspnScoreboardClient
import com.carteltv.tv.data.finance.ExchangeRateService
import com.carteltv.tv.data.finance.ExchangeRates
import com.carteltv.tv.data.weather.AirLevel
import com.carteltv.tv.data.weather.AirQuality
import com.carteltv.tv.data.weather.Sky
import com.carteltv.tv.data.weather.WeatherUnits
import com.carteltv.tv.ui.components.AutoSizeText
import com.carteltv.tv.ui.components.TABULAR
import com.carteltv.tv.ui.sports.parseTeamColor
import com.carteltv.tv.ui.sports.stateLabel
import com.carteltv.tv.ui.theme.ThemeAccessibility
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.weather.SkyGlyph
import com.carteltv.tv.ui.weather.SkyScene
import com.carteltv.tv.ui.weather.rememberAmbientPhase
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a Today widget shows. Each kind carries its typed reading, not pre-baked strings, so the widget
 * draws its own art from the real data: the sky behind the temperature, the teams' colours behind the
 * scores, the day's direction behind the rates, the band on the air-quality dial.
 */
internal sealed interface Glance {
    /** Games in progress; the widget lists up to three and counts the rest. */
    data class Live(
        val games: List<EspnScoreboardClient.Score>,
        val label: String,
        val nowMs: Long,
    ) : Glance

    /** Current conditions where the box is. [detail] is the one extra line worth reading (nowcast, humidity). */
    data class Weather(
        val tempC: Double,
        val code: Int,
        val isDay: Boolean,
        val place: String,
        val localTime: String,
        val highC: Double?,
        val lowC: Double?,
        val detail: String?,
    ) : Glance {
        val sky: Sky get() = Sky.of(code)
    }

    data class Fx(
        val rates: ExchangeRates,
    ) : Glance

    data class Air(
        val reading: AirQuality,
    ) : Glance
}

/** Every Today widget is this tall; the width is the shelf's card width, so the row lines up with the rails. */
internal val GlanceH = 148.dp

private val GlanceShape = RoundedCornerShape(ThemeSpec.Card.corner)
private val GlancePad = ThemeSpec.Space.lg

/** Readable over photographs: a soft dark halo under light ink. */
private val InkShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1.5f), 8f)

/**
 * One Today widget. [modifier] carries the card's size and glass surface; the widget fills it with its art
 * and copy. Ambient motion runs only while the widget holds focus, so a resting row costs no frames.
 */
@Composable
internal fun GlanceWidget(
    glance: Glance,
    focused: Boolean,
    modifier: Modifier,
) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().clip(GlanceShape)) {
            when (glance) {
                is Glance.Weather -> WeatherGlance(glance, focused)
                is Glance.Live -> LiveGlance(glance, focused)
                is Glance.Fx -> FxGlance(glance)
                is Glance.Air -> AirGlance(glance, focused)
            }
        }
    }
}

/* ─────────────────────────── shared pieces ─────────────────────────── */

/** The widget's tracked caps label, with an optional right-hand note in the same voice. */
@Composable
private fun GlanceHeader(
    label: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    lead: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
        lead?.invoke()
        Text(
            label.uppercase(Locale.getDefault()),
            fontSize = ThemeSpec.Type.micro,
            fontFamily = ThemeSpec.Font.broadcast,
            fontWeight = FontWeight.Bold,
            letterSpacing = ThemeSpec.Type.trackCaps,
            color = ThemeSemantics.onMedia.copy(alpha = 0.86f),
            style = TextStyle(shadow = InkShadow),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        note?.let {
            Text(
                it,
                fontSize = ThemeSpec.Type.micro,
                fontFamily = ThemeSpec.Font.mono,
                fontWeight = FontWeight.Medium,
                color = ThemeSemantics.onMediaMuted,
                style = TextStyle(shadow = InkShadow, fontFeatureSettings = TABULAR),
                maxLines = 1,
            )
        }
    }
}

/** A soft pool of [color] centred at a fraction of the box, the light every dark widget is lit by. */
private fun Modifier.glow(
    color: Color,
    at: Offset,
    radiusFraction: Float,
    alpha: Float,
): Modifier =
    drawBehind {
        val c = Offset(size.width * at.x, size.height * at.y)
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = c, radius = size.maxDimension * radiusFraction),
            radius = size.maxDimension * radiusFraction,
            center = c,
        )
    }

/* ─────────────────────────── weather ─────────────────────────── */

/**
 * A window onto the sky: the condition's photograph (or, for a clear sky, a drawn day or night sky with
 * its sun glow or stars), the condition glyph floating in the corner, and the temperature set large.
 */
@Composable
private fun WeatherGlance(
    w: Glance.Weather,
    focused: Boolean,
) {
    val sky = w.sky
    val phase = rememberAmbientPhase(focused, periodMs = 3_600)
    Box(Modifier.fillMaxSize().background(ThemeSemantics.mediaInk)) {
        SkyScene(sky, w.isDay, phase)
        // Copy sits on the left and the bottom: darken those edges, leave the sky open at the top right.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.7f to Color.Black.copy(alpha = 0.05f)),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f)),
            ),
        )
        // The glyph drifts a few dp while the widget has focus.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = ThemeSpec.Space.md, end = ThemeSpec.Space.md)
                .size(60.dp)
                .graphicsLayer { translationY = sin(phase.value * 2f * PI.toFloat()) * 3.dp.toPx() },
        ) {
            SkyGlyph(sky, w.isDay, Modifier.fillMaxSize())
        }
        Column(Modifier.fillMaxSize().padding(GlancePad)) {
            GlanceHeader(w.place, note = w.localTime, modifier = Modifier.padding(end = 60.dp))
            Spacer(Modifier.weight(1f))
            Text(
                WeatherUnits.temp(w.tempC),
                fontSize = 44.sp,
                lineHeight = 44.sp,
                fontFamily = ThemeSpec.Font.display,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = ThemeSpec.Type.trackHeadline,
                color = ThemeSemantics.onMedia,
                style = TextStyle(shadow = InkShadow, fontFeatureSettings = TABULAR),
                maxLines = 1,
            )
            Text(
                Sky.describe(w.code).takeIf { it != "—" } ?: sky.label,
                fontSize = ThemeSpec.Type.meta,
                fontFamily = ThemeSpec.Font.broadcast,
                fontWeight = FontWeight.SemiBold,
                color = ThemeSemantics.onMedia,
                style = TextStyle(shadow = InkShadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val range =
                if (w.highC != null && w.lowC != null) "H ${WeatherUnits.temp(w.highC)}  L ${WeatherUnits.temp(w.lowC)}" else null
            listOfNotNull(range, w.detail).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    fontSize = ThemeSpec.Type.micro,
                    fontFamily = ThemeSpec.Font.body,
                    fontWeight = FontWeight.Medium,
                    color = ThemeSemantics.onMediaMuted,
                    style = TextStyle(shadow = InkShadow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* ─────────────────────────── live sports ─────────────────────────── */

/**
 * The live board: the lead game's two team colours wash in from opposite corners over broadcast stripes,
 * a breathing red dot heads the card, and each game reads as marks and scores with the trailing side dimmed.
 */
@Composable
private fun LiveGlance(
    live: Glance.Live,
    focused: Boolean,
) {
    val lead = live.games.firstOrNull()
    val away = parseTeamColor(lead?.colorB) ?: ThemeSemantics.cornerRed
    val home = parseTeamColor(lead?.colorA) ?: ThemeSemantics.cornerBlue
    val crowded = live.games.size > 3
    val shown = live.games.take(if (crowded) 2 else 3)
    // The dot always breathes: it is the card's live signal. Focus only quickens it.
    val pulse = rememberAmbientPhase(active = true, periodMs = if (focused) 1_000 else ThemeSpec.Motion.PULSE_MS)
    Box(
        Modifier
            .fillMaxSize()
            .background(ThemeSemantics.mediaInk)
            .drawWithCache {
                val wash =
                    Brush.linearGradient(
                        0f to away.copy(alpha = 0.55f),
                        0.42f to away.copy(alpha = 0.08f),
                        0.58f to home.copy(alpha = 0.08f),
                        1f to home.copy(alpha = 0.55f),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                val gap = 10.dp.toPx()
                val stripe = Color.White.copy(alpha = 0.035f)
                val w = 1.dp.toPx()
                onDrawBehind {
                    drawRect(wash)
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(stripe, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = w)
                        x += gap
                    }
                }
            }.padding(GlancePad),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
            GlanceHeader(
                live.label,
                lead = {
                    Box(
                        Modifier
                            .size(8.dp)
                            .graphicsLayer {
                                val p = (sin(pulse.value * 2f * PI.toFloat()) + 1f) / 2f
                                alpha = 0.45f + 0.55f * p
                                scaleX = 0.85f + 0.3f * p
                                scaleY = scaleX
                            }.background(ThemeSemantics.danger, CircleShape),
                    )
                },
            )
            Spacer(Modifier.height(ThemeSpec.Space.xs))
            shown.forEach { LiveLine(it, live.nowMs) }
            if (crowded) {
                Text(
                    "+${live.games.size - shown.size} more live",
                    fontSize = ThemeSpec.Type.micro,
                    fontFamily = ThemeSpec.Font.broadcast,
                    fontWeight = FontWeight.SemiBold,
                    color = ThemeSemantics.onMediaMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

private val LiveRowH = 26.dp
private val LiveMark = 20.dp

/** One game: away mark and score, home score and mark, then the clock. The side behind is dimmed. */
@Composable
private fun LiveLine(
    g: EspnScoreboardClient.Score,
    nowMs: Long,
) {
    val a = g.scoreA?.toIntOrNull()
    val b = g.scoreB?.toIntOrNull()
    val ink = ThemeSemantics.onMedia
    val dim = ink.copy(alpha = 0.5f)
    val inkB = if (a != null && b != null && b < a) dim else ink
    val inkA = if (a != null && b != null && a < b) dim else ink
    val status = stateLabel(g, nowMs).removePrefix("LIVE ").ifBlank { g.shortDetail.orEmpty() }
    Row(
        Modifier.fillMaxWidth().height(LiveRowH),
        verticalAlignment = Alignment.CenterVertically,
        // xs, not sm: with three-letter abbreviations at sm a "Q4 5:32" clock ellipsized to "Q4 5:…".
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
    ) {
        TeamGlyph(g.logoB, g.abbrB)
        ScoreText(g.scoreB ?: "0", inkB)
        Text("–", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.onMediaMuted)
        ScoreText(g.scoreA ?: "0", inkA)
        TeamGlyph(g.logoA, g.abbrA)
        AutoSizeText(
            status,
            fontSize = ThemeSpec.Type.micro,
            fontFamily = ThemeSpec.Font.mono,
            fontWeight = FontWeight.Bold,
            color = ThemeSemantics.onMediaMuted,
            fontFeatureSettings = TABULAR,
            textAlign = TextAlign.End,
            minScale = 0.75f,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ScoreText(
    score: String,
    color: Color,
) {
    Text(
        score,
        fontSize = 20.sp,
        lineHeight = 22.sp,
        fontFamily = ThemeSpec.Font.mono,
        fontWeight = FontWeight.Bold,
        color = color,
        style = TextStyle(fontFeatureSettings = TABULAR),
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * The team's mark, or its abbreviation when the feed has no logo. The abbreviation takes its own width: three
 * letters at the 16sp fitted-text floor do not fit the logo's square, so shrinking it only ellipsized "LAL" to "L…".
 */
@Composable
private fun TeamGlyph(
    logo: String?,
    abbr: String?,
) {
    if (logo != null) {
        AsyncImage(model = logo, contentDescription = abbr, contentScale = ContentScale.Fit, modifier = Modifier.size(LiveMark))
    } else {
        Box(Modifier.defaultMinSize(minWidth = LiveMark, minHeight = LiveMark), contentAlignment = Alignment.Center) {
            Text(
                abbr.orEmpty(),
                fontSize = ThemeSpec.Type.micro,
                fontFamily = ThemeSpec.Font.broadcast,
                fontWeight = FontWeight.Bold,
                color = ThemeSemantics.onMedia,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/* ─────────────────────────── exchange rates ─────────────────────────── */

private val isoDay = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
private val shortDay = ThreadLocal.withInitial { SimpleDateFormat("d MMM", Locale.getDefault()) }

/** "2026-11-27" → "27 Nov"; the raw text when it does not parse. */
private fun dayLabel(iso: String): String = runCatching { shortDay.get()!!.format(isoDay.get()!!.parse(iso)!!) }.getOrDefault(iso)

/**
 * The rates board: a ledger-ruled ground lit green or red by the day's overall direction, the base
 * currency's code set huge as a watermark, and each rate with its move in a tinted arrow pill. The rates
 * are ECB euro reference rates (crossed to the base when it is not the euro), fixed once a working day,
 * so the header names the source and the fixing date and each move is against the working day before.
 */
@Composable
private fun FxGlance(fx: Glance.Fx) {
    val r = fx.rates
    val rows = r.rates.take(3)
    val moves = rows.mapNotNull { it.changePct }
    val mood =
        when {
            moves.isEmpty() -> ThemeSemantics.onMedia
            moves.sum() > 0 -> ThemeSemantics.success
            moves.sum() < 0 -> ThemeSemantics.danger
            else -> ThemeSemantics.onMedia
        }
    Box(
        Modifier
            .fillMaxSize()
            .background(ThemeSemantics.mediaInk)
            .glow(mood, Offset(1f, 1f), radiusFraction = 0.75f, alpha = 0.28f)
            .drawWithCache {
                val rule = Color.White.copy(alpha = 0.04f)
                val step = 14.dp.toPx()
                onDrawBehind {
                    var y = step
                    while (y < size.height) {
                        drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += step
                    }
                }
            },
    ) {
        Text(
            r.base,
            fontSize = 64.sp,
            lineHeight = 64.sp,
            fontFamily = ThemeSpec.Font.display,
            fontWeight = FontWeight.Black,
            color = Color.White.copy(alpha = 0.06f),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = ThemeSpec.Space.sm),
        )
        Column(Modifier.fillMaxSize().padding(GlancePad), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
            GlanceHeader("ECB ref · 1 ${r.base}", note = dayLabel(r.date))
            rows.forEach { rate ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                    Text(
                        rate.code,
                        fontSize = ThemeSpec.Type.label,
                        fontFamily = ThemeSpec.Font.broadcast,
                        fontWeight = FontWeight.Bold,
                        color = ThemeSemantics.onMediaMuted,
                        modifier = Modifier.width(36.dp),
                        maxLines = 1,
                    )
                    AutoSizeText(
                        ExchangeRateService.format(rate.value),
                        fontSize = ThemeSpec.Type.cardTitle,
                        fontFamily = ThemeSpec.Font.mono,
                        fontWeight = FontWeight.Bold,
                        color = ThemeSemantics.onMedia,
                        fontFeatureSettings = TABULAR,
                        minScale = 0.7f,
                        modifier = Modifier.weight(1f),
                    )
                    rate.changePct?.let { MovePill(it) }
                }
            }
        }
    }
}

/** A move as an arrow and a percentage on a pill tinted by its direction. */
@Composable
private fun MovePill(pct: Double) {
    val flat = abs(pct) < 0.005
    val tone =
        when {
            flat -> ThemeSemantics.onMediaMuted
            pct > 0 -> ThemeSemantics.success
            else -> ThemeSemantics.danger
        }
    val arrow = if (flat) "•" else if (pct > 0) "▲" else "▼"
    Text(
        "$arrow ${ExchangeRateService.signedPercent(pct).trimStart('+', '−')}",
        fontSize = ThemeSpec.Type.micro,
        fontFamily = ThemeSpec.Font.mono,
        fontWeight = FontWeight.Bold,
        color = tone,
        style = TextStyle(fontFeatureSettings = TABULAR),
        maxLines = 1,
        modifier =
            Modifier
                .clip(RoundedCornerShape(ThemeSpec.Card.pill))
                .background(tone.copy(alpha = 0.16f))
                .padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.xs),
    )
}

/* ─────────────────────────── air quality ─────────────────────────── */

/** EPA's standard AQI colours (Technical Assistance Document, Table 2): green, yellow, orange, red, purple, maroon. */
private fun AirLevel.hue(): Color =
    when (this) {
        AirLevel.GOOD -> Color(0xFF00E400)
        AirLevel.MODERATE -> Color(0xFFFFFF00)
        AirLevel.SENSITIVE -> Color(0xFFFF7E00)
        AirLevel.UNHEALTHY -> Color(0xFFFF0000)
        AirLevel.VERY_UNHEALTHY -> Color(0xFF8F3F97)
        AirLevel.HAZARDOUS -> Color(0xFF7E0023)
    }

/** The band's colour as type on the dark ground: purple and maroon are too dark to read, so they are lifted. */
private fun AirLevel.ink(): Color =
    when (this) {
        AirLevel.VERY_UNHEALTHY, AirLevel.HAZARDOUS -> lerp(hue(), Color.White, 0.45f)
        else -> hue()
    }

/** The dial's scale: each band keeps its true width, and the hazardous band is drawn up to here. */
private const val AQI_DIAL_MAX = 350f
private const val DIAL_START = 150f
private const val DIAL_SWEEP = 240f

/**
 * The air dial: a 240° gauge segmented into the six AQI bands in EPA's colours, the reading's knob sweeping
 * into place when the widget is focused, and the number inside the arc. Beside it, what EPA asks every AQI
 * report to carry: the category descriptor in its colour and the main pollutant (the highest sub-index).
 */
@Composable
private fun AirGlance(
    air: Glance.Air,
    focused: Boolean,
) {
    val a = air.reading
    val level = a.level
    val target = (a.usAqi.coerceIn(0, AQI_DIAL_MAX.toInt()) / AQI_DIAL_MAX)
    // The knob rests on the reading; focus replays its sweep from the bottom of the scale.
    val sweep = remember { Animatable(target) }
    LaunchedEffect(focused, target) {
        if (focused && !ThemeAccessibility.systemAnimationsRemoved) {
            sweep.snapTo(0f)
            sweep.animateTo(target, tween(900, easing = ThemeSpec.Motion.easeEnter))
        } else {
            sweep.snapTo(target)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(ThemeSemantics.mediaInk)
            .glow(level.hue(), Offset(0.25f, 0.6f), radiusFraction = 0.6f, alpha = 0.3f),
    ) {
        Column(Modifier.fillMaxSize().padding(GlancePad), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
            GlanceHeader("Air Quality")
            Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
            ) {
                Box(Modifier.size(AirDialSize), contentAlignment = Alignment.Center) {
                    AqiDial(sweep, Modifier.fillMaxSize())
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${a.usAqi}",
                            fontSize = 26.sp,
                            lineHeight = 26.sp,
                            fontFamily = ThemeSpec.Font.display,
                            fontWeight = FontWeight.Bold,
                            color = ThemeSemantics.onMedia,
                            style = TextStyle(fontFeatureSettings = TABULAR),
                            maxLines = 1,
                        )
                        Text(
                            "US AQI",
                            fontSize = 10.sp,
                            fontFamily = ThemeSpec.Font.broadcast,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = ThemeSemantics.onMediaMuted,
                            maxLines = 1,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
                    Text(
                        level.label,
                        fontSize = ThemeSpec.Type.label,
                        lineHeight = 16.sp,
                        fontFamily = ThemeSpec.Font.broadcast,
                        fontWeight = FontWeight.Bold,
                        color = level.ink(),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    a.mainPollutant?.let { main ->
                        Text(
                            "Main pollutant",
                            fontSize = 10.sp,
                            fontFamily = ThemeSpec.Font.broadcast,
                            fontWeight = FontWeight.Bold,
                            color = ThemeSemantics.onMediaMuted,
                            maxLines = 1,
                        )
                        // Name and concentration on separate lines: "PM2.5 39.6 µg/m³" does not fit the column.
                        listOfNotNull(main.label, a.mainConcentration?.let { "%.1f µg/m³".format(Locale.getDefault(), it) })
                            .forEach { line ->
                                Text(
                                    line,
                                    fontSize = ThemeSpec.Type.micro,
                                    fontFamily = ThemeSpec.Font.mono,
                                    fontWeight = FontWeight.Bold,
                                    color = ThemeSemantics.onMedia,
                                    style = TextStyle(fontFeatureSettings = TABULAR),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                    }
                }
            }
        }
    }
}

private val AirDialSize = 84.dp

/** The dial itself: six band arcs with hairline gaps, and a knob at [sweep] (a 0–1 share of the scale). */
@Composable
private fun AqiDial(
    sweep: Animatable<Float, AnimationVector1D>,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val stroke = 7.dp.toPx()
        val inset = stroke / 2f + 4.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        val gapDeg = 2f
        AirLevel.entries.forEach { band ->
            val from = band.range.first.coerceAtMost(AQI_DIAL_MAX.toInt()) / AQI_DIAL_MAX
            val to = (if (band == AirLevel.HAZARDOUS) AQI_DIAL_MAX.toInt() else band.range.last + 1) / AQI_DIAL_MAX
            drawArc(
                band.hue(),
                startAngle = DIAL_START + from * DIAL_SWEEP + gapDeg / 2f,
                sweepAngle = (to - from) * DIAL_SWEEP - gapDeg,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Butt),
            )
        }
        val angle = Math.toRadians((DIAL_START + sweep.value * DIAL_SWEEP).toDouble())
        val r = arcSize.width / 2f
        val knob = center + Offset((cos(angle) * r).toFloat(), (sin(angle) * r).toFloat())
        drawCircle(Color.Black.copy(alpha = 0.5f), stroke * 0.95f, knob)
        drawCircle(Color.White, stroke * 0.7f, knob)
    }
}

