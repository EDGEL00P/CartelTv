package com.carteltv.tv.ui.weather

import androidx.compose.animation.Crossfade
import com.carteltv.tv.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.CornerRadius
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.weather.Sky
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.scaleAlpha
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusRing
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.theme.glassSurface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import com.carteltv.tv.ui.components.Chip
import com.carteltv.tv.ui.components.ClockFormat

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
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, m / 60)
        set(Calendar.MINUTE, m % 60)
    }
    return ClockFormat.time(cal.timeInMillis, AppPreferences.clock24)
}

fun durationText(minutes: Double): String = "${(minutes / 60).toInt()} h ${(minutes % 60).toInt()} m"

private val utcDate = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
private val dayNameFmt = ThreadLocal.withInitial { SimpleDateFormat("EEEE", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") } }
private val dayShortFmt = ThreadLocal.withInitial { SimpleDateFormat("d MMM", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") } }

fun dayName(date: String, index: Int): String {
    if (index == 0) return "Today"
    val d = runCatching { utcDate.get()?.parse(date) }.getOrNull() ?: return date
    return dayNameFmt.get().format(d)
}

fun dayShort(date: String): String {
    val d = runCatching { utcDate.get()?.parse(date) }.getOrNull() ?: return date
    return dayShortFmt.get().format(Date(d.time))
}

fun agoText(sec: Long): String = when {
    sec < 60 -> "${sec}s ago"
    sec < 3600 -> "${sec / 60} min ago"
    else -> "${sec / 3600} h ${sec % 3600 / 60} min ago"
}

/** Liquid-glass card: translucent surface, accent edge on focus, white ring + glow, and the compact lift. */
@Composable
fun Modifier.weatherCard(focused: Boolean, lift: Float = ThemeSpec.Focus.liftCompact, pressed: Boolean = false): Modifier {
    val shape = RoundedCornerShape(ThemeSpec.Card.cornerLg)
    return this
        .focusLift(focusScaleState(focused, lift, pressed))
        .glassSurface(focused, shape)
}

/** A card-shaped D-pad stop. Content-only tiles need one so the list can scroll to them. */
@Composable
fun WeatherFocusCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
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
fun WeatherChip(label: String, selected: Boolean, modifier: Modifier = Modifier, dot: Color? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.cornerSm)
    Box(
        modifier
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .then(
                if (selected) {
                    Modifier.clip(shape).background(ThemeSemantics.accent)
                        .focusRing(focused, shape, idle = Color.Transparent)
                } else Modifier.glassSurface(focused, shape)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickableNoIndication(interaction, onClick)
            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.uppercase(), fontSize = ThemeSpec.Type.label, maxLines = 1,
                fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.Bold,
                letterSpacing = ThemeSpec.Type.trackCaps,
                color = if (selected) com.carteltv.tv.ui.components.onAccent(ThemeSemantics.accent) else ThemeSemantics.textEmphatic,
            )
            if (dot != null) {
                Box(Modifier.padding(start = ThemeSpec.Space.sm).size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot))
            }
        }
    }
}

@Composable
fun WeatherButton(label: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.cornerSm)
    Text(
        label.uppercase(), fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold, maxLines = 1,
        fontFamily = ThemeSpec.Font.broadcast, letterSpacing = ThemeSpec.Type.trackCaps,
        color = if (active) com.carteltv.tv.ui.components.onAccent(ThemeSemantics.accent) else ThemeSemantics.textEmphatic,
        modifier = modifier
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .then(
                if (active) Modifier.clip(shape).background(ThemeSemantics.accent).focusRing(focused, shape, idle = Color.Transparent)
                else Modifier.glassSurface(focused, shape)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickableNoIndication(interaction, onClick)
            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
    )
}

/** The condition as an official Meteocons SVG, converted losslessly to an Android vector (MIT, see NOTICE). */
@Composable
fun WeatherIcon(sky: Sky, modifier: Modifier = Modifier.size(48.dp)) {
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
fun BoltGlyph(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Image(
        painterResource(R.drawable.ph_lightning),
        contentDescription = null,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color),
        modifier = modifier,
    )
}

/** The photograph behind each sky (CC0, see NOTICE). */
private fun weatherPhoto(sky: Sky): Int =
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
fun WeatherBackdrop(sky: Sky, modifier: Modifier = Modifier.fillMaxSize(), dark: Boolean = false) {
    Box(modifier) {
        Crossfade(targetState = weatherPhoto(sky), animationSpec = tween(700), modifier = Modifier.fillMaxSize(), label = "weatherPhoto") { res ->
            Image(painterResource(res), contentDescription = "Weather sky photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
