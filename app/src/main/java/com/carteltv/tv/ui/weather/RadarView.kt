package com.carteltv.tv.ui.weather

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.carteltv.tv.data.weather.Hillshade
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.weather.Lightning
import com.carteltv.tv.data.weather.Place
import com.carteltv.tv.data.weather.RadarFeed
import com.carteltv.tv.data.weather.StormTracker
import com.carteltv.tv.data.weather.TileCache
import com.carteltv.tv.data.weather.WeatherUnits
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.tryFocus
import com.carteltv.tv.R
import com.carteltv.tv.ui.theme.PhosphorIcon
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.style.TextOverflow
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.theme.focusRing
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** Everything the radar needs, hoisted so the forecast preview and the full page share one timeline and viewport. */
@Stable
class RadarState {
    var manifest by mutableStateOf<RadarFeed.Manifest?>(null)
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)
    var index by mutableIntStateOf(0)
    var playing by mutableStateOf(AppPreferences.radarAutoPlay)
    var zoom by mutableIntStateOf(AppPreferences.radarZoom)
    var centerLat by mutableDoubleStateOf(0.0)
    var centerLon by mutableDoubleStateOf(0.0)
    var panning by mutableStateOf(false)
    var opacity by mutableIntStateOf(AppPreferences.radarOpacity)
    var frameMs by mutableIntStateOf(AppPreferences.radarFrameMs)

    val frames get() = manifest?.frames.orEmpty()
    val latestIndex get() = manifest?.latestIndex ?: 0
    val hasNowcast get() = manifest?.hasNowcast ?: false
    val supportsFlow get() = manifest?.provider == RadarFeed.Provider.LIBREWXR

    fun recenter(p: Place) { centerLat = p.lat; centerLon = p.lon }

    fun zoomBy(delta: Int) {
        zoom = (zoom + delta).coerceIn(RadarFeed.MIN_ZOOM, manifest?.provider?.maxZoom ?: RadarFeed.Provider.RAINVIEWER.maxZoom)
        AppPreferences.radarZoom = zoom
    }

    fun step(delta: Int) {
        val n = frames.size
        if (n == 0) return
        playing = false
        index = ((index + delta) % n + n) % n
    }

    /** Pan by fractions of a tile; the view stays inside the world. */
    fun pan(dxTiles: Double, dyTiles: Double) {
        val n = (1 shl zoom).toDouble()
        val x = worldX(centerLon, zoom) + dxTiles
        val y = (worldY(centerLat, zoom) + dyTiles).coerceIn(0.02 * n, 0.98 * n)
        centerLon = ((lonOf(x, zoom) + 540) % 360) - 180
        centerLat = latOf(y, zoom)
    }

    suspend fun load(force: Boolean = false) {
        val first = manifest == null
        if (first) loading = true
        val m = RadarFeed.manifest(force)
        if (m != null) {
            val changedProvider = manifest?.provider != m.provider
            if (changedProvider) zoom = zoom.coerceAtMost(m.provider.maxZoom)
            manifest = m
            failed = false
            // Keep the frame the viewer chose on an ordinary refresh; otherwise open on the newest observation,
            // never on a nowcast frame (those are stamped in the future and would read as "Latest").
            if (first || changedProvider || index !in m.frames.indices) index = m.latestIndex
        } else if (first) failed = true
        loading = false
    }
}

internal fun worldX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * (1 shl z)
internal fun worldY(lat: Double, z: Int): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)
}
internal fun lonOf(x: Double, z: Int) = x / (1 shl z) * 360.0 - 180.0
internal fun latOf(y: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))

private const val TILT_OVERSCAN = 1.35f
private val radarDecode = TileCache.png(sample = 2)

private suspend fun loadShade(z: Int, t: MapTile) {
    val url = RadarFeed.terrainUrl(z, t.x, t.ty)
    TileCache.load(url, key = "hs|$url", decode = Hillshade.render(z, t.ty))
}

private class MapTile(val tx: Int, val ty: Int, val x: Int)

private fun visibleTiles(w: Float, h: Float, tilePx: Float, z: Int, cx: Double, cy: Double): List<MapTile> {
    if (w <= 0f || h <= 0f) return emptyList()
    val n = 1 shl z
    val hw = w / 2 / tilePx
    val hh = h / 2 / tilePx
    val y0 = max(0, floor(cy - hh).toInt())
    val y1 = min(n - 1, floor(cy + hh).toInt())
    val out = ArrayList<MapTile>()
    for (tx in floor(cx - hw).toInt()..floor(cx + hw).toInt()) for (ty in y0..y1) out += MapTile(tx, ty, ((tx % n) + n) % n)
    return out
}

/**
 * Slippy map: street basemap, the RainViewer composite for the current frame, optional place names, lightning
 * strikes coloured by age, range rings and the "you are here" pulse. Tiles are painted straight from [TileCache].
 */
@Composable
fun RadarMap(
    state: RadarState,
    place: Place,
    modifier: Modifier = Modifier,
    lightning: Boolean = false,
    lightningWindowMin: Int = 30,
    rings: Boolean = false,
    animate: Boolean = true,
    zoomOverride: Int? = null,
    staticLatest: Boolean = false,
    tilt: Float = 0f,
    pulseHome: Boolean = true,
    arrows: Boolean = false,
    reduceMotion: Boolean = false,
) {
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    val tilePx = 256f * density
    val m = state.manifest
    val z = (zoomOverride ?: state.zoom).coerceAtMost(m?.provider?.maxZoom ?: RadarFeed.Provider.RAINVIEWER.maxZoom)
    val cLat = if (staticLatest) place.lat else state.centerLat
    val cLon = if (staticLatest) place.lon else state.centerLon
    val tiles = remember(size, z, cLat, cLon, density) {
        visibleTiles(size.width.toFloat(), size.height.toFloat(), tilePx, z, worldX(cLon, z), worldY(cLat, z))
    }

    LaunchedEffect(tiles, m, arrows) {
        if (tiles.isEmpty()) return@LaunchedEffect
        coroutineScope {
            tiles.forEach { t -> launch { TileCache.load(RadarFeed.baseUrl(z, t.x, t.ty)) } }
            if (tilt > 0f) tiles.forEach { t -> launch { loadShade(z, t) } }
            if (m != null) (if (staticLatest) listOf(m.frames[m.latestIndex]) else m.frames.asReversed()).forEach { f ->
                tiles.forEach { t -> launch { TileCache.load(RadarFeed.radarUrl(m, f, z, t.x, t.ty, arrows), decode = radarDecode) } }
            }
        }
    }

    LaunchedEffect(state.playing, m, tiles, state.frameMs, animate, arrows, reduceMotion) {
        val frames = m?.frames ?: return@LaunchedEffect
        if (!state.playing || !animate || reduceMotion || staticLatest || frames.size < 2) return@LaunchedEffect
        fun ready(i: Int) = tiles.isNotEmpty() && tiles.all { TileCache.peek(RadarFeed.radarUrl(m, frames[i], z, it.x, it.ty, arrows)) != null }
        while (isActive && state.playing) {
            val next = RadarFeed.nextFrame(state.index, frames.size) ?: break
            var loaded = ready(next)
            var waits = 0
            while (!loaded && waits < 8) {
                delay(state.frameMs.toLong())
                loaded = ready(next)
                waits++
            }
            if (!loaded || !isActive || !state.playing) break
            state.index = next
            delay(state.frameMs.toLong())
        }
        if (isActive) state.playing = false
    }

    // Dissolve between frames instead of snapping, so the loop reads as moving weather rather than a slideshow.
    val fade = remember { Animatable(1f) }
    var shownIndex by remember { mutableIntStateOf(state.index) }
    var prevIndex by remember { mutableIntStateOf(state.index) }
    LaunchedEffect(state.index, reduceMotion) {
        if (state.index == shownIndex) return@LaunchedEffect
        prevIndex = shownIndex
        shownIndex = state.index
        if (reduceMotion) { fade.snapTo(1f); return@LaunchedEffect }
        fade.snapTo(0f)
        fade.animateTo(1f, tween((state.frameMs * 0.85f).toInt().coerceIn(120, 900), easing = LinearEasing))
    }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lightning) { while (lightning) { nowMs = System.currentTimeMillis(); delay(5_000) } }
    val strikes = remember(Lightning.version, nowMs, lightning, lightningWindowMin) {
        if (!lightning) emptyList() else Lightning.snapshot().filter { nowMs - it.tMs <= lightningWindowMin * 60_000L }
    }

    val pulse by rememberInfiniteTransition(label = "radarPulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )
    val measurer = rememberTextMeasurer()
    val ringLabel = TextStyle(fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, fontWeight = FontWeight.Medium)
    val home = ThemeSemantics.accent
    val homeGlare = ThemeSemantics.accentBright
    val ringColor = ThemeSemantics.textEmphatic.copy(alpha = 0.34f)
    val recent = ThemeSemantics.textEmphatic
    val glare = ThemeSemantics.accentBright
    val mid = ThemeSemantics.accent
    val old = ThemeSemantics.accent.copy(alpha = 0.5f)
    val stage = ThemeSemantics.stage

    val tilted = if (tilt > 0f) {
        Modifier.clipToBounds()
            .layout { measurable, c ->
                val p = measurable.measure(Constraints.fixed((c.maxWidth * TILT_OVERSCAN).roundToInt(), (c.maxHeight * TILT_OVERSCAN).roundToInt()))
                layout(c.maxWidth, c.maxHeight) { p.place((c.maxWidth - p.width) / 2, (c.maxHeight - p.height) / 2) }
            }
            .graphicsLayer {
                rotationX = tilt
                cameraDistance = 9f * density
                transformOrigin = TransformOrigin(0.5f, 0.55f)
            }
    } else Modifier
    Canvas(modifier.then(tilted).onSizeChanged { size = it }) {
        TileCache.version
        val w = this.size.width
        val h = this.size.height
        val cx = worldX(cLon, z)
        val cy = worldY(cLat, z)
        fun px(wx: Double) = ((wx - cx) * tilePx + w / 2).toFloat()
        fun py(wy: Double) = ((wy - cy) * tilePx + h / 2).toFloat()
        drawRect(stage)

        fun paint(url: String, t: MapTile, alpha: Float, filter: ColorFilter? = null) {
            val bmp = TileCache.peek(url) ?: return
            val l = px(t.tx.toDouble()).roundToInt()
            val tp = py(t.ty.toDouble()).roundToInt()
            val r = px(t.tx + 1.0).roundToInt()
            val b = py(t.ty + 1.0).roundToInt()
            drawImage(bmp, dstOffset = IntOffset(l, tp), dstSize = IntSize(r - l, b - tp), alpha = alpha, colorFilter = filter, filterQuality = FilterQuality.Medium)
        }
        tiles.forEach { paint(RadarFeed.baseUrl(z, it.x, it.ty), it, 1f) }
        if (tilt > 0f) tiles.forEach { paint("hs|" + RadarFeed.terrainUrl(z, it.x, it.ty), it, 0.8f) }
        val frame = m?.frames?.getOrNull(if (staticLatest) m.latestIndex else state.index)
        val op = state.opacity / 100f
        val b = fade.value
        val before = if (b < 1f && !staticLatest) m?.frames?.getOrNull(prevIndex) else null
        if (m != null && before != null && before !== frame) tiles.forEach { paint(RadarFeed.radarUrl(m, before, z, it.x, it.ty, arrows), it, op) }
        if (m != null && frame != null) tiles.forEach { paint(RadarFeed.radarUrl(m, frame, z, it.x, it.ty, arrows), it, op * (if (before != null) b else 1f)) }

        val hx = px(worldX(place.lon, z))
        val hy = py(worldY(place.lat, z))

        if (rings) {
            val kmPerPx = 156.54303392 * cos(Math.toRadians(place.lat)) / (1 shl z) / density
            StormTracker.rings.forEach { km ->
                val r = (km / kmPerPx).toFloat()
                if (r < 14f || r > w * 2) return@forEach
                drawCircle(ringColor, r, Offset(hx, hy), style = Stroke(1.2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 6f * density))))
                val label = measurer.measure(WeatherUnits.distance(km.toDouble()), ringLabel)
                drawText(label, topLeft = Offset(hx - label.size.width / 2f, hy - r - label.size.height - 2f * density))
            }
        }

        strikes.forEach { s ->
            val sx = px(worldX(s.lon, z))
            val sy = py(worldY(s.lat, z))
            if (sx < -20 || sy < -20 || sx > w + 20 || sy > h + 20) return@forEach
            val age = (nowMs - s.tMs) / 60_000.0
            val c = when { age < 2 -> recent; age < 10 -> glare; age < 30 -> mid; else -> old }
            val r = (if (age < 2) 4.5f else 3.2f) * density
            drawCircle(c.copy(alpha = 0.16f), r * 2.8f, Offset(sx, sy))
            drawCircle(c, r, Offset(sx, sy))
            if (age < 3) drawCircle(c.copy(alpha = (1f - pulse) * 0.7f), r * (1f + pulse * 3f), Offset(sx, sy), style = Stroke(1.5f * density))
        }

        val hc = Offset(hx, hy)
        if (pulseHome) {
            drawCircle(home.copy(alpha = (1f - pulse) * 0.55f), (8f + pulse * 22f) * density, hc, style = Stroke(2f * density))
        }
        drawCircle(Color.White.copy(alpha = 0.35f), 10f * density, hc)
        drawCircle(Color.White, 8f * density, hc)
        drawCircle(homeGlare, 5.5f * density, hc)
    }
}

private fun frameClock(timeSec: Long): String = clockText(timeSec * 1000)

/** The live HUD panels' corner: the house panel radius, not a softer one. */
private val HudShape = RoundedCornerShape(ThemeSpec.Card.cornerLg)

/** HUD glass over the map: the media scrim ladder (glass at the top, the deep anchor at the bottom) and a hairline edge. */
private fun Modifier.radarGlass(shape: Shape): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(ThemeSemantics.scrimChip, ThemeSemantics.scrimDeep)))
    .border(1.dp, ThemeSemantics.hairline, shape)

/** Control glyphs shared by the 2D radar page and the 3D storm view, so both read as one icon family. */
internal enum class Glyph { PLAY, PAUSE, PREV, NEXT, REPLAY, PLUS, MINUS, PAN, HOME, BOLT, ARROWS, TOOLS, CUBE, ROTATE_LEFT, ROTATE_RIGHT, TILT_UP, TILT_DOWN, CLOSE }

/** One control glyph from the shared radar set: its Phosphor icon, tinted from the theme. */
@Composable
internal fun RadarGlyph(g: Glyph, tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    PhosphorIcon(radarIcon(g), tint, 24.dp, modifier)
}

private fun radarIcon(g: Glyph): Int =
    when (g) {
        Glyph.PLAY -> R.drawable.ph_play
        Glyph.PAUSE -> R.drawable.ph_pause
        Glyph.PREV -> R.drawable.ph_skip_back
        Glyph.NEXT -> R.drawable.ph_skip_forward
        Glyph.REPLAY -> R.drawable.ph_arrow_counter_clockwise
        Glyph.PLUS -> R.drawable.ph_plus
        Glyph.MINUS -> R.drawable.ph_minus
        Glyph.PAN -> R.drawable.ph_hand_grabbing
        Glyph.HOME -> R.drawable.ph_house
        Glyph.BOLT -> R.drawable.ph_lightning
        Glyph.ARROWS -> R.drawable.ph_arrows_out
        Glyph.TOOLS -> R.drawable.ph_wrench
        Glyph.CUBE -> R.drawable.ph_cube
        Glyph.ROTATE_LEFT -> R.drawable.ph_arrow_arc_left
        Glyph.ROTATE_RIGHT -> R.drawable.ph_arrow_arc_right
        Glyph.TILT_UP -> R.drawable.ph_caret_double_up
        Glyph.TILT_DOWN -> R.drawable.ph_caret_double_down
        Glyph.CLOSE -> R.drawable.ph_x
    }



@Composable
private fun RadarControl(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    label: String? = null,
    primary: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.pill)
    val filled = primary || active
    val fg = if (filled) ThemeSemantics.textInverse else if (focused) ThemeSemantics.textEmphatic else ThemeSemantics.textPrimary
    Row(
        modifier
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftIcon, pressed))
            .clip(shape)
            .background(if (filled) ThemeSemantics.accent else if (focused) ThemeSemantics.onMedia else ThemeSemantics.scrimChip)
            .focusRing(focused, shape, idle = Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickableNoIndication(interaction, onClick)
            .padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.cozy)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (focused && label != null) {
            Text(label, fontSize = ThemeSpec.Type.meta, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
            Spacer(Modifier.width(ThemeSpec.Space.sm))
        }
        RadarGlyph(glyph, fg)
    }
}

/** Echo level at the home pin for every loaded frame (-1 = frame not loaded yet); drives the timeline bars. */
private fun levelsAtHome(state: RadarState, place: Place, arrows: Boolean = false): List<Int> {
    val m = state.manifest ?: return emptyList()
    val z = state.zoom.coerceAtMost(m.provider.maxZoom)
    val n = 1 shl z
    val wx = worldX(place.lon, z)
    val wy = worldY(place.lat, z)
    val tx = ((floor(wx).toInt() % n) + n) % n
    val ty = floor(wy).toInt().coerceIn(0, n - 1)
    return m.frames.map { f ->
        val bmp = TileCache.peek(RadarFeed.radarUrl(m, f, z, tx, ty, arrows)) ?: return@map -1
        val px = ((wx - floor(wx)) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val py = ((wy - floor(wy)) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        RadarFeed.level(bmp.asAndroidBitmap().getPixel(px, py))
    }
}

private fun levelColor(level: Int) = Color(RadarFeed.legend[(level - 1).coerceIn(0, RadarFeed.legend.lastIndex)])

/** One bar per frame; height and colour both encode the echo at the home pin, so intensity never rests on colour alone. */
@Composable
private fun RadarTimeline(state: RadarState, levels: List<Int>, modifier: Modifier = Modifier) {
    val frames = state.frames
    if (frames.isEmpty()) return
    val nowcastCount = state.manifest?.nowcast?.size ?: 0
    val firstFuture = if (nowcastCount > 0) frames.size - nowcastCount else -1
    Column(modifier) {
        if (firstFuture >= 0) {
            // A forecast stamp is not live: red here would claim it is.
            Text(
                "FORECAST", fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.SemiBold,
                color = ThemeSemantics.accentBright, maxLines = 1, modifier = Modifier.align(Alignment.End),
            )
            Spacer(Modifier.height(2.dp))
        }
        Box(Modifier.fillMaxWidth().height(40.dp)) {
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs), verticalAlignment = Alignment.Bottom) {
                frames.forEachIndexed { i, _ ->
                    val cur = i == state.index
                    val future = firstFuture >= 0 && i >= firstFuture
                    val lv = levels.getOrElse(i) { -1 }
                    // Thin bars keep straight ends.
                    val tint = if (lv > 0) levelColor(lv) else if (cur) ThemeSemantics.accent else ThemeSemantics.textEmphatic
                    val h = if (lv > 0) (12 + lv * 3).dp else if (cur) 12.dp else 8.dp
                    Box(
                        Modifier.weight(1f).height(h)
                            .background(tint.copy(alpha = if (cur) 1f else if (future) 0.3f else if (lv > 0) 0.6f else 0.25f))
                            .then(if (cur && lv > 0) Modifier.border(2.dp, ThemeSemantics.textEmphatic) else Modifier),
                    )
                }
            }
            // Non-colour cue: a dashed rule at the observed / forecast boundary.
            if (firstFuture >= 0) {
                Canvas(Modifier.matchParentSize()) {
                    val x = size.width * firstFuture / frames.size.toFloat()
                    drawLine(
                        ThemeSemantics.textEmphatic.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, size.height),
                        strokeWidth = 1.5f.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f.dp.toPx(), 4f.dp.toPx())),
                    )
                }
            }
        }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(frameClock(frames.first().timeSec), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1)
            Text(frameClock(frames.last().timeSec), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun RadarLegend(modifier: Modifier = Modifier) {
    Column(modifier.width(220.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(RadarFeed.legend.map { Color(it) })))
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 dBZ", fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1)
            Text("70 dBZ", fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1)
        }
    }
}

/**
 * The scale bar's clearance from the bottom edge. A map-chrome inset, not page padding, so it stays literal:
 * the transport panel under it (controls over the timeline, the legend and the attribution line) stands roughly
 * 180dp tall with its overscan margin when the timeline carries its FORECAST label, and this keeps the bar just
 * clear of that tallest state.
 */
private val ScaleBarBottom = 190.dp

@Composable
private fun ScaleBar(zoom: Int, lat: Double, modifier: Modifier = Modifier) {
    val kmPerDp = 156.54303392 * cos(Math.toRadians(lat)) / (1 shl zoom)
    val imperial = WeatherUnits.distance(1.0).endsWith("mi")
    val unitPerDp = if (imperial) kmPerDp * 0.621371 else kmPerDp
    val raw = 90 * unitPerDp
    val mag = 10.0.pow(floor(log10(raw)))
    val nice = listOf(5.0, 2.0, 1.0).map { it * mag }.first { it <= raw }
    val widthDp = (nice / unitPerDp).toFloat()
    val label = "%s %s".format(if (nice >= 1) nice.roundToInt().toString() else "%.1f".format(nice), if (imperial) "mi" else "km")
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(label, fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.SemiBold, color = ThemeSemantics.textEmphatic, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        // The scale rule is a thin bar: straight ends.
        Box(Modifier.width(widthDp.dp).height(5.dp).border(width = 2.dp, color = ThemeSemantics.textEmphatic))
    }
}

/** Pill that sits on top of a map. */
@Composable
internal fun MapBadge(text: String, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(
        modifier.clip(RoundedCornerShape(ThemeSpec.Card.pill)).background(ThemeSemantics.scrimChip).padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) { Box(Modifier.size(7.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(ThemeSpec.Space.sm)) }
        Text(text, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textEmphatic, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Layer name, place and the one time that matters; rain over the pin only appears when there is some. */
@Composable
private fun RadarReadout(state: RadarState, place: Place, levels: List<Int>, strikes: Int?, modifier: Modifier = Modifier) {
    val frame = state.frames.getOrNull(state.index)
    val latest = state.index == state.latestIndex
    val deltaMin = frame?.let { ((System.currentTimeMillis() / 1000 - it.timeSec) / 60).toInt() } ?: 0
    val future = deltaMin < -1 && state.hasNowcast
    val here = levels.getOrNull(state.index)?.takeIf { it > 0 }
    Column(modifier.radarGlass(HudShape).padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.radar_title), fontSize = ThemeSpec.Type.title, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic, maxLines = 1)
            Spacer(Modifier.width(ThemeSpec.Space.sm))
            Text(place.shortLabel, fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textSecondary, maxLines = 1, modifier = Modifier.padding(bottom = ThemeSpec.Space.xs))
        }
        if (frame != null) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(frameClock(frame.timeSec), fontSize = ThemeSpec.Type.titleL, fontWeight = FontWeight.SemiBold, color = ThemeSemantics.textEmphatic, maxLines = 1)
                Spacer(Modifier.width(ThemeSpec.Space.sm))
                Text(
                    when {
                        latest -> "Latest"
                        future -> "Forecast +${-deltaMin} min"
                        else -> "${deltaMin.coerceAtLeast(0)} min ago"
                    }, fontSize = ThemeSpec.Type.meta,
                    // Only "Latest" is live; a forecast stamp reads as a forecast, in the bright accent.
                    color = if (latest) ThemeSemantics.live else if (future) ThemeSemantics.accentBright else ThemeSemantics.textSecondary,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        if (here != null) {
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(levelColor(here)))
                Spacer(Modifier.width(ThemeSpec.Space.sm))
                Text(
                    "~${RadarFeed.dbz(here)} dBZ · ${RadarFeed.intensityWord(here)}${if (future) " forecast" else ""} at your location",
                    fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textPrimary, maxLines = 1,
                )
            }
        }
        if (strikes != null) {
            Spacer(Modifier.height(ThemeSpec.Space.xs))
            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.radar_strikes, strikes), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1)
        }
    }
}

private fun coord(v: Double, pos: String, neg: String) = "%.2f°%s".format(abs(v), if (v >= 0) pos else neg)

@Composable
fun RadarPage(
    state: RadarState,
    place: Place,
    showLightning: Boolean,
    feedOn: Boolean,
    onToggleLightning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playFocus = remember { FocusRequester() }
    val mapFocus = remember { FocusRequester() }
    val toolsFocus = remember { FocusRequester() }
    val firstToolFocus = remember { FocusRequester() }
    var mapFocused by remember { mutableStateOf(false) }
    var showArrows by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    var storm3d by remember { mutableStateOf(false) }
    var focusReady by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val strikeCount = if (showLightning) remember(Lightning.version) { Lightning.snapshot().count { System.currentTimeMillis() - it.tMs <= 30 * 60_000L } } else null
    val levels = remember(TileCache.version, state.manifest, state.zoom, place.lat, place.lon, showArrows) { levelsAtHome(state, place, showArrows) }
    val atEnd = state.frames.isNotEmpty() && state.index == state.frames.lastIndex

    LaunchedEffect(Unit) { playFocus.awaitFocus(); focusReady = true }
    LaunchedEffect(state.panning, toolsOpen, focusReady) {
        if (!focusReady) return@LaunchedEffect
        if (state.panning) mapFocus.awaitFocus() else if (!toolsOpen) toolsFocus.tryFocus()
    }
    LaunchedEffect(toolsOpen) { if (toolsOpen) firstToolFocus.awaitFocus() }
    LaunchedEffect(reduceMotion) { if (reduceMotion) state.playing = false }
    BackHandler(enabled = state.panning) { state.panning = false }
    BackHandler(enabled = toolsOpen && !state.panning) { toolsOpen = false }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .then(if (mapFocused) Modifier.border(3.dp, ThemeSemantics.focusRing) else Modifier)
                .focusRequester(mapFocus)
                .onFocusChanged { mapFocused = it.isFocused }
                .focusable(enabled = state.panning)
                .onPreviewKeyEvent { ev ->
                    if (!state.panning || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionLeft -> { state.pan(-0.22, 0.0); true }
                        Key.DirectionRight -> { state.pan(0.22, 0.0); true }
                        Key.DirectionUp -> { state.pan(0.0, -0.22); true }
                        Key.DirectionDown -> { state.pan(0.0, 0.22); true }
                        Key.DirectionCenter, Key.Enter -> { state.panning = false; true }
                        else -> false
                    }
                },
        ) {
            RadarMap(state, place, Modifier.fillMaxSize(), lightning = showLightning, lightningWindowMin = 30, tilt = 0f, arrows = showArrows, reduceMotion = reduceMotion)

            RadarReadout(state, place, levels, strikeCount, Modifier.align(Alignment.TopStart).padding(start = ThemeSpec.Space.margin, top = ThemeSpec.Safe.vertical))

            Column(Modifier.align(Alignment.TopEnd).padding(end = ThemeSpec.Safe.horizontal, top = ThemeSpec.Safe.vertical), horizontalAlignment = Alignment.End) {
                if (state.panning) {
                    MapBadge("Press the arrows to pan. Press OK to finish.", dot = ThemeSemantics.accent)
                    Spacer(Modifier.height(ThemeSpec.Space.sm))
                    MapBadge("${coord(state.centerLat, "N", "S")}  ${coord(state.centerLon, "E", "W")}")
                }
            }
            ScaleBar(state.zoom.coerceAtMost(state.manifest?.provider?.maxZoom ?: RadarFeed.Provider.RAINVIEWER.maxZoom), state.centerLat, Modifier.align(Alignment.BottomStart).padding(start = ThemeSpec.Space.margin, bottom = ScaleBarBottom))

            if (state.panning) {
                Canvas(Modifier.align(Alignment.Center).size(44.dp)) {
                    val c = Color.White.copy(alpha = 0.85f)
                    val mid = Offset(size.width / 2, size.height / 2)
                    val gap = 8.dp.toPx()
                    val sw = 1.6.dp.toPx()
                    drawLine(c, Offset(0f, mid.y), Offset(mid.x - gap, mid.y), sw)
                    drawLine(c, Offset(mid.x + gap, mid.y), Offset(size.width, mid.y), sw)
                    drawLine(c, Offset(mid.x, 0f), Offset(mid.x, mid.y - gap), sw)
                    drawLine(c, Offset(mid.x, mid.y + gap), Offset(mid.x, size.height), sw)
                }
            }

            if (state.loading || state.failed || state.frames.isEmpty()) {
                MapBadge(
                    if (state.loading) "Loading radar…" else "Radar is unavailable. Check the connection.",
                    Modifier.align(Alignment.Center),
                    dot = if (state.loading) ThemeSemantics.accent else ThemeSemantics.live,
                )
            }
        }

        if (toolsOpen) {
            Column(
                Modifier.align(Alignment.TopEnd)
                    .padding(end = ThemeSpec.Safe.horizontal, top = 90.dp)
                    .radarGlass(HudShape)
                    .padding(ThemeSpec.Space.md),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            ) {
                Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.radar_map_tools), fontSize = ThemeSpec.Type.title, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic)
                RadarControl(Glyph.PLUS, Modifier.focusRequester(firstToolFocus), label = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.radar_zoom_in)) { state.zoomBy(1) }
                RadarControl(Glyph.MINUS, label = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.radar_zoom_out)) { state.zoomBy(-1) }
                RadarControl(Glyph.PAN, label = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.radar_pan)) { state.panning = true; toolsOpen = false }
                RadarControl(Glyph.CUBE, label = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.radar_storm_3d)) { storm3d = true; toolsOpen = false }
                RadarControl(Glyph.HOME, label = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.radar_recenter)) { state.recenter(place) }
                if (feedOn) RadarControl(Glyph.BOLT, label = if (showLightning) "Hide lightning" else "Show lightning", active = showLightning, onClick = onToggleLightning)
                if (state.supportsFlow) RadarControl(Glyph.ARROWS, label = if (showArrows) "Hide storm motion" else "Show storm motion", active = showArrows) { showArrows = !showArrows }
            }
        }

        RadarControl(
            Glyph.TOOLS,
            Modifier.align(Alignment.TopEnd).padding(end = ThemeSpec.Safe.horizontal, top = ThemeSpec.Safe.vertical).focusRequester(toolsFocus),
            label = if (toolsOpen) "Close tools" else "Map tools",
            active = toolsOpen,
        ) { toolsOpen = !toolsOpen }

        if (storm3d) Storm3DView(state, place, onDismiss = { storm3d = false })

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal, bottom = ThemeSpec.Safe.vertical)
                .radarGlass(HudShape)
                .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadarControl(
                    if (reduceMotion) Glyph.NEXT else if (state.playing) Glyph.PAUSE else if (atEnd) Glyph.REPLAY else Glyph.PLAY,
                    Modifier.focusRequester(playFocus),
                    primary = true,
                    label = if (reduceMotion) "Next frame" else if (atEnd) "Replay" else null,
                ) {
                    when {
                        reduceMotion -> RadarFeed.nextFrame(state.index, state.frames.size)?.let { state.index = it }
                        atEnd -> { state.index = 0; state.playing = true }
                        else -> state.playing = !state.playing
                    }
                }
                Spacer(Modifier.width(ThemeSpec.Space.sm))
                RadarControl(Glyph.PREV) { state.step(-1) }
                Spacer(Modifier.width(ThemeSpec.Space.sm))
                RadarControl(Glyph.NEXT) { state.step(1) }
                Spacer(Modifier.width(ThemeSpec.Space.lg))
                RadarTimeline(state, levels, Modifier.weight(1f))
                Spacer(Modifier.width(ThemeSpec.Space.lg))
                RadarLegend()
            }
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Text(
                (state.manifest?.provider ?: RadarFeed.Provider.RAINVIEWER).attribution + if (showLightning) " · Lightning © Blitzortung.org" else "",
                fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
