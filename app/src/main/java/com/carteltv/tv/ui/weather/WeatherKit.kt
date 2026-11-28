package com.carteltv.tv.ui.weather

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.R
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.weather.Sky
import com.carteltv.tv.ui.components.Chip
import com.carteltv.tv.ui.components.ClockFormat
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.theme.ThemeAccessibility
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusRing
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.theme.glassSurface
import com.carteltv.tv.ui.theme.scaleAlpha
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Local-naive ISO ("2026-11-21T15:00") read as a local time, so weather clocks word exactly like every other clock. */
private val isoLocal = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()) }

/** "2026-11-21T15:00" → "3 PM" / "15:00", honouring the house clock setting. */
fun hourLabel(iso: String): String =
    runCatching { ClockFormat.hour(isoLocal.get()?.parse(iso)?.time ?: return iso, AppPreferences.clock24) }
        .getOrDefault(iso.substring(11, 16))

/** "2026-11-21T04:46" → "4:46 AM" / "04:46". */
fun clockText(iso: String): String =
    runCatching { ClockFormat.time(isoLocal.get()?.parse(iso)?.time ?: return iso, AppPreferences.clock24) }
        .getOrDefault(iso.substring(11, 16))

/** Epoch milliseconds → "4:46 AM" / "16:46", honouring the house clock setting. */
fun clockText(ms: Long): String = ClockFormat.time(ms, AppPreferences.clock24)

/** Minutes since local midnight (may exceed 1440 for tomorrow) → "6:03 PM" / "18:03". */
fun minuteText(minute: Int): String {
    val m = ((minute % 1440) + 1440) % 1440
    val cal =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m / 60)
            set(Calendar.MINUTE, m % 60)
        }
    return ClockFormat.time(cal.timeInMillis, AppPreferences.clock24)
}

fun durationText(minutes: Double): String = "${(minutes / 60).toInt()} h ${(minutes % 60).toInt()} m"

private val utcDate = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
private val dayNameFmt =
    ThreadLocal.withInitial {
        SimpleDateFormat("EEEE", Locale.getDefault()).apply {
            timeZone =
                TimeZone.getTimeZone("UTC")
        }
    }
private val dayShortFmt =
    ThreadLocal.withInitial {
        SimpleDateFormat("d MMM", Locale.getDefault()).apply {
            timeZone =
                TimeZone.getTimeZone("UTC")
        }
    }

fun dayName(
    date: String,
    index: Int,
): String {
    if (index == 0) return "Today"
    val d = runCatching { utcDate.get()?.parse(date) }.getOrNull() ?: return date
    return dayNameFmt.get().format(d)
}

fun dayShort(date: String): String {
    val d = runCatching { utcDate.get()?.parse(date) }.getOrNull() ?: return date
    return dayShortFmt.get().format(Date(d.time))
}

fun agoText(sec: Long): String =
    when {
        sec < 60 -> "${sec}s ago"
        sec < 3600 -> "${sec / 60} min ago"
        else -> "${sec / 3600} h ${sec % 3600 / 60} min ago"
    }

/** Liquid-glass card: the house card surface (glass fill, accent edge on focus, white ring + glow, compact lift). */
@Composable
fun Modifier.weatherCard(
    focused: Boolean,
    lift: Float = ThemeSpec.Focus.liftCompact,
    pressed: Boolean = false,
): Modifier = this.cardSurface(focused, lift, pressed = pressed)

/** A card-shaped D-pad stop. Content-only tiles need one so the list can scroll to them. */
@Composable
fun WeatherFocusCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .weatherCard(focused, pressed = pressed)
            .onFocusChanged { focused = it.isFocused }
            .let { if (onClick != null) it.clickableNoIndication(interaction, onClick) else it.focusable() },
    ) { content() }
}

@Composable
fun WeatherChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.cornerSm)
    Box(
        modifier
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .then(
                if (selected) {
                    Modifier
                        .clip(shape)
                        .background(ThemeSemantics.accent)
                        .focusRing(focused, shape, idle = Color.Transparent)
                } else {
                    Modifier.glassSurface(focused, shape)
                },
            ).onFocusChanged { focused = it.isFocused }
            .clickableNoIndication(interaction, onClick)
            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.uppercase(),
                fontSize = ThemeSpec.Type.label,
                maxLines = 1,
                fontFamily = ThemeSpec.Font.broadcast,
                fontWeight = FontWeight.Bold,
                letterSpacing = ThemeSpec.Type.trackCaps,
                color =
                    if (selected) {
                        com.carteltv.tv.ui.components
                            .onAccent(ThemeSemantics.accent)
                    } else {
                        ThemeSemantics.textEmphatic
                    },
            )
            if (dot != null) {
                Box(
                    Modifier
                        .padding(
                            start = ThemeSpec.Space.sm,
                        ).size(7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(dot),
                )
            }
        }
    }
}

@Composable
fun WeatherButton(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.cornerSm)
    Text(
        label.uppercase(),
        fontSize = ThemeSpec.Type.label,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        fontFamily = ThemeSpec.Font.broadcast,
        letterSpacing = ThemeSpec.Type.trackCaps,
        color =
            if (active) {
                com.carteltv.tv.ui.components
                    .onAccent(ThemeSemantics.accent)
            } else {
                ThemeSemantics.textEmphatic
            },
        modifier =
            modifier
                .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
                .then(
                    if (active) {
                        Modifier.clip(shape).background(ThemeSemantics.accent).focusRing(focused, shape, idle = Color.Transparent)
                    } else {
                        Modifier.glassSurface(focused, shape)
                    },
                ).onFocusChanged { focused = it.isFocused }
                .clickableNoIndication(interaction, onClick)
                .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
    )
}

/** The condition as an official Meteocons SVG, converted losslessly to an Android vector (MIT, see NOTICE). */
@Composable
fun WeatherIcon(
    sky: Sky,
    modifier: Modifier = Modifier.size(48.dp),
) {
    Image(painterResource(weatherIcon(sky)), contentDescription = sky.label, contentScale = ContentScale.Fit, modifier = modifier)
}

private fun weatherIcon(sky: Sky): Int =
    when (sky) {
        Sky.CLEAR -> R.drawable.mc_clear_day_svg
        Sky.PARTLY -> R.drawable.mc_partly_cloudy_day_svg
        Sky.CLOUDY -> R.drawable.mc_overcast_day_svg
        Sky.FOG -> R.drawable.mc_fog_day_svg
        Sky.DRIZZLE -> R.drawable.mc_partly_cloudy_day_drizzle_svg
        Sky.RAIN -> R.drawable.mc_partly_cloudy_day_rain_svg
        Sky.SNOW -> R.drawable.mc_partly_cloudy_day_snow_svg
        Sky.STORM -> R.drawable.mc_thunderstorms_day_rain_svg
    }

@Composable
fun BoltGlyph(
    color: Color,
    modifier: Modifier = Modifier.size(18.dp),
) {
    Image(
        painterResource(R.drawable.ph_lightning),
        contentDescription = null,
        colorFilter =
            androidx.compose.ui.graphics.ColorFilter
                .tint(color),
        modifier = modifier,
    )
}

/** The photograph behind each sky (CC0, see NOTICE). */
internal fun weatherPhoto(sky: Sky): Int =
    when (sky) {
        Sky.CLEAR -> R.drawable.bg_stage
        Sky.PARTLY -> R.drawable.wx_partly
        Sky.CLOUDY -> R.drawable.wx_cloudy
        Sky.FOG -> R.drawable.wx_fog
        Sky.DRIZZLE, Sky.RAIN -> R.drawable.wx_rain
        Sky.SNOW -> R.drawable.wx_snow
        Sky.STORM -> R.drawable.wx_storm
    }

/**
 * A real photograph of the current sky, crossfading when it changes. A plain white scrim, heavier toward
 * the bottom where the text sits, keeps the page's dark ink readable; nothing
 * coloured is laid over it. On a dark stage ([dark]) the scrim turns to night instead, so the photo
 * reads as a dark sky behind light ink.
 */
@Composable
fun WeatherBackdrop(
    sky: Sky,
    modifier: Modifier = Modifier.fillMaxSize(),
    dark: Boolean = false,
) {
    Box(modifier) {
        Crossfade(
            targetState = weatherPhoto(sky),
            animationSpec = tween(700),
            modifier = Modifier.fillMaxSize(),
            label = "weatherPhoto",
        ) { res ->
            Image(
                painterResource(res),
                contentDescription = "Weather sky photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                if (dark) {
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.40f), Color.Black.copy(alpha = 0.65f)))
                } else {
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.6f)))
                },
            ),
        )
    }
}

/* ─────────────────────────── sky art, shared by the Today widget and the hero outlook ─────────────────────────── */

/**
 * A 0→1 phase that loops while [active] and rests at 0 otherwise (or when system animations are off). An infinite
 * transition, so the frame clock and Compose's test idling both treat it as the ambient loop it is.
 */
@Composable
internal fun rememberAmbientPhase(
    active: Boolean,
    periodMs: Int,
): State<Float> {
    if (!active || ThemeAccessibility.systemAnimationsRemoved) return AtRest
    return rememberInfiniteTransition(label = "ambient").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "phase",
    )
}

private val AtRest: State<Float> = mutableFloatStateOf(0f)

/**
 * The sky behind a reading: the condition's photograph, dimmed toward night after dark, or for a clear sky a drawn
 * day (deep blue with the sun's bloom) or night (stars that twinkle with [phase]). No scrims: the caller lays its own.
 */
@Composable
internal fun SkyScene(
    sky: Sky,
    isDay: Boolean,
    phase: State<Float>,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Box(modifier) {
        if (sky == Sky.CLEAR) {
            ClearSky(isDay, phase)
        } else {
            Image(
                painterResource(weatherPhoto(sky)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (!isDay) Box(Modifier.fillMaxSize().background(NightTint))
        }
    }
}

/** The condition's glyph, except that a clear night shows the moon: the icon set has only day faces. */
@Composable
internal fun SkyGlyph(
    sky: Sky,
    isDay: Boolean,
    modifier: Modifier,
) {
    if (sky == Sky.CLEAR && !isDay) Moon(modifier.padding(ThemeSpec.Space.sm)) else WeatherIcon(sky, modifier)
}

private val NightTint = Color(0x8C0A1236)
private val DaySkyTop = Color(0xFF2F7DE1)
private val DaySkyBottom = Color(0xFF0D2E63)
private val NightSkyTop = Color(0xFF0B1238)
private val NightSkyBottom = Color(0xFF02040C)
private val SunGlow = Color(0xFFFFC85A)
private val MoonGlow = Color(0xFFBFD4FF)
private val MoonFace = Color(0xFFF4F1E4)

/** Star positions and sizes as fractions of the box, fixed so the sky is the same every time it is drawn. */
private val Stars: List<Triple<Float, Float, Float>> =
    Random(20261128).let { r -> List(28) { Triple(r.nextFloat(), r.nextFloat() * 0.8f, 0.6f + r.nextFloat() * 1.1f) } }

@Composable
private fun ClearSky(
    day: Boolean,
    phase: State<Float>,
) {
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val sky = Brush.verticalGradient(if (day) listOf(DaySkyTop, DaySkyBottom) else listOf(NightSkyTop, NightSkyBottom))
            val bloomCenter = Offset(size.width * 0.84f, size.height * 0.24f)
            val bloom =
                Brush.radialGradient(
                    listOf((if (day) SunGlow else MoonGlow).copy(alpha = if (day) 0.55f else 0.22f), Color.Transparent),
                    center = bloomCenter,
                    radius = size.maxDimension * 0.55f,
                )
            onDrawBehind {
                drawRect(sky)
                drawRect(bloom)
                if (!day) {
                    val t = phase.value * 2f * PI.toFloat()
                    Stars.forEachIndexed { i, (x, y, r) ->
                        val twinkle = 0.55f + 0.45f * ((sin(t + i * 1.7f) + 1f) / 2f)
                        drawCircle(Color.White.copy(alpha = 0.75f * twinkle), radius = r.dp.toPx(), center = Offset(x * size.width, y * size.height))
                    }
                }
            }
        },
    )
}

/** A crescent moon: a disc with a second, offset disc cut out of it. */
@Composable
private fun Moon(modifier: Modifier) {
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val r = size.minDimension / 2f
        drawCircle(MoonFace, r, center)
        drawCircle(Color.Black, r * 0.86f, center + Offset(r * 0.48f, -r * 0.34f), blendMode = BlendMode.Clear)
    }
}
