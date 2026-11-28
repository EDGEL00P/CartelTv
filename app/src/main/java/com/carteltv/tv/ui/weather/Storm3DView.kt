package com.carteltv.tv.ui.weather

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.weather.Place
import com.carteltv.tv.data.weather.RadarFeed
import com.carteltv.tv.data.weather.TileCache
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusRing
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The radar as a 3D diorama: the same TileCache frames and timeline as [RadarMap], composited into one
 * ground texture and laid flat in a Filament scene, with echoes at NEXRAD level 7+ (heavy rain and
 * above) extruded into pillars so storm cores read as volume. D-pad orbits the camera; the shared
 * [RadarState] keeps the 2D page underneath in sync, so leaving restores exactly that view.
 */

/** Ground texture covers a 5×5 tile span around the viewport centre, rendered into one 1024px bitmap. */
private const val SPAN_TILES = 5
private const val TEX_PX = 1024

/** Samples across the ground texture for storm-cell detection. */
private const val SCAN_GRID = 48
private const val PILLAR_MIN_LEVEL = 7
private const val MAX_PILLARS = 36

private const val DEFAULT_YAW = 0f
private const val DEFAULT_PITCH = 38f

/** The ground plane spans 10 world units so pillar sizes stay independent of zoom. */
private const val PLANE_UNITS = 10f

private val GroundDarken = ColorMatrix(
    floatArrayOf(0.36f, 0f, 0f, 0f, 0f, 0f, 0.38f, 0f, 0f, 0f, 0f, 0f, 0.42f, 0f, 2f, 0f, 0f, 0f, 1f, 0f),
)

private fun clockFmt(timeSec: Long): String = clockText(timeSec * 1000)

/** One frame of the diorama: the composited ground texture and the pillars mined from it. */
private class Diorama(val ground: Bitmap, val pillars: List<Pillar>) {
    class Pillar(val u: Float, val v: Float, val level: Int, val height: Float)
}

/**
 * Off-main composite of basemap + radar overlay for one frame into a single texture, then a coarse
 * scan for level-[PILLAR_MIN_LEVEL]+ echoes. Bitmap drawing is thread-safe; Filament only ever sees
 * the finished bitmap on the main thread through [ImageNode].
 */
private suspend fun buildDiorama(
    m: RadarFeed.Manifest,
    frameIdx: Int,
    z: Int,
    centerTileX: Double,
    centerTileY: Double,
    opacity: Float,
): Diorama? = withContext(Dispatchers.Default) {
    val n = 1 shl z
    val t0x = (centerTileX - SPAN_TILES / 2.0).roundToInt()
    val t0y = (centerTileY - SPAN_TILES / 2.0).roundToInt()
    val perTile = TEX_PX / SPAN_TILES

    val ground = Bitmap.createBitmap(TEX_PX, TEX_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(ground)
    val mapPaint = Paint().apply { colorFilter = ColorMatrixColorFilter(GroundDarken) }
    val radarPaint = Paint().apply { alpha = (opacity * 255).roundToInt().coerceIn(0, 255) }

    val f = m.frames.getOrNull(frameIdx) ?: return@withContext null
    val tileAt = { i: Int -> ((i % n) + n) % n }
    for (ty in 0 until SPAN_TILES) for (tx in 0 until SPAN_TILES) {
        val gx = tileAt(t0x + tx)
        val gy = tileAt(t0y + ty)
        val dstX = tx * perTile
        val dstY = ty * perTile
        val dst = android.graphics.Rect(dstX, dstY, dstX + perTile, dstY + perTile)
        TileCache.peek(RadarFeed.baseUrl(z, gx, gy))?.let { canvas.drawBitmap(it.asAndroidBitmap(), null, dst, mapPaint) }
        TileCache.peek(RadarFeed.radarUrl(m, f, z, gx, gy))?.let { canvas.drawBitmap(it.asAndroidBitmap(), null, dst, radarPaint) }
    }

    // Coarse scan on a SCAN_GRID lattice, keeping the strongest echoes (level 7+ is heavy rain and
    // up). Nearest legend colour gives the pillar tint; height grows with dBZ.
    val pixels = IntArray(SCAN_GRID * SCAN_GRID)
    ground.getPixels(pixels, 0, SCAN_GRID, 0, 0, SCAN_GRID, SCAN_GRID)
    val pillars = ArrayList<Diorama.Pillar>()
    val minSep = 1.6f / SCAN_GRID
    for (gy in 0 until SCAN_GRID) for (gx in 0 until SCAN_GRID) {
        val level = RadarFeed.level(pixels[gy * SCAN_GRID + gx])
        if (level < PILLAR_MIN_LEVEL) continue
        val u = (gx + 0.5f) / SCAN_GRID
        val v = (gy + 0.5f) / SCAN_GRID
        // Local-maximum debounce so a wide core doesn't melt into a solid slab of boxes.
        if (pillars.any { max(Math.abs(it.u - u), Math.abs(it.v - v)) < minSep && it.level >= level }) continue
        pillars.removeAll { max(Math.abs(it.u - u), Math.abs(it.v - v)) < minSep && it.level < level }
        val dbz = RadarFeed.dbz(level).toFloat()
        pillars += Diorama.Pillar(u, v, level, ((dbz - 25f) / 45f).coerceIn(0.12f, 1f))
        if (pillars.size >= MAX_PILLARS) break
    }
    Diorama(ground, pillars.sortedByDescending { it.height })
}

/** Full-screen 3D storm view over the radar page; Back or the Close control returns to 2D. */
@Composable
fun Storm3DView(
    state: RadarState,
    place: Place,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = state.manifest
    val z = min(state.zoom, m?.provider?.maxZoom ?: 7)
    var yaw by remember { mutableFloatStateOf(DEFAULT_YAW) }
    var pitch by remember { mutableFloatStateOf(DEFAULT_PITCH) }
    var diorama by remember { mutableStateOf<Diorama?>(null) }
    val sceneFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }

    BackHandler { onDismiss() }
    LaunchedEffect(Unit) { sceneFocus.awaitFocus() }

    // The ground texture and pillars track the shared timeline: frame, zoom, pan and loaded tiles.
    // RadarMap stays composed underneath and keeps prefetching and driving playback.
    LaunchedEffect(m, z, state.index, state.centerLat, state.centerLon, TileCache.version) {
        val manifest = m ?: return@LaunchedEffect
        val cx = worldX(state.centerLon, z)
        val cy = worldY(state.centerLat, z)
        diorama = buildDiorama(manifest, state.index, z, cx, cy, state.opacity / 100f)
    }

    Box(modifier.fillMaxSize().background(Color.White)) {
        val engine = rememberEngine()
        val modelLoader = rememberModelLoader(engine)
        val materialLoader = rememberMaterialLoader(engine)
        val cameraNode = rememberCameraNode(engine)

        // Orbit distance scales with zoom so the diorama fills the frame without a zoom control.
        val dist = 6f + (12 - z).coerceAtLeast(0) * 0.9f
        val pitchRad = Math.toRadians(pitch.toDouble())
        val yawRad = Math.toRadians(yaw.toDouble())
        val eye = Position(
            x = (dist * cos(pitchRad) * sin(yawRad)).toFloat(),
            y = (dist * sin(pitchRad)).toFloat(),
            z = (dist * cos(pitchRad) * cos(yawRad)).toFloat(),
        )

        SceneView(
            modifier = Modifier.fillMaxSize()
                .focusRequester(sceneFocus)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionLeft -> { yaw -= 6f; true }
                        Key.DirectionRight -> { yaw += 6f; true }
                        Key.DirectionUp -> { pitch = (pitch + 4f).coerceIn(15f, 85f); true }
                        Key.DirectionDown -> { pitch = (pitch - 4f).coerceIn(15f, 85f); true }
                        else -> false
                    }
                },
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            cameraManipulator = null, // the D-pad script owns the camera; a manipulator would overwrite it
            mainLightNode = null, // every material below is unlit; keep the look flat like the 2D map
        ) {
            // Camera at the orbit position looking at the origin; -X pitch tilts the view down.
            cameraNode.position = eye
            cameraNode.rotation = Rotation(x = -pitch, y = yaw, z = 0f)

            val bmp = diorama?.ground
            if (bmp != null) {
                ImageNode(
                    bitmap = bmp,
                    size = Size(x = PLANE_UNITS, y = PLANE_UNITS),
                )
                diorama?.pillars?.forEach { p ->
                    val h = 0.4f + p.height * 2.2f
                    CubeNode(
                        size = Size(x = 0.32f, y = h, z = 0.32f),
                        center = Position(x = (p.u - 0.5f) * PLANE_UNITS, y = h / 2f, z = (p.v - 0.5f) * PLANE_UNITS),
                        materialInstance = rememberPillarMaterial(materialLoader, p.level),
                    )
                }
                // Home pin: a slim cone at the diorama centre so the camera always knows where "here" is.
                ConeNode(radius = 0.12f, height = 0.5f, center = Position(x = 0f, y = 0.25f, z = 0f))
            }
        }

        // Readout + controls, mirroring the 2D page's glass styling.
        Column(Modifier.align(Alignment.TopStart).padding(start = ThemeSpec.Space.margin, top = ThemeSpec.Safe.vertical)) {
            MapBadge3D("Storm 3D · ${place.name}", dot = ThemeSemantics.accent)
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            MapBadge3D("Press the arrows to rotate the view. Press Back to exit.")
            if (m != null) {
                Spacer(Modifier.height(ThemeSpec.Space.sm))
                MapBadge3D(m.provider.attribution)
            }
        }
        m?.frames?.getOrNull(state.index)?.let { f ->
            MapBadge3D(clockFmt(f.timeSec), Modifier.align(Alignment.TopEnd).padding(end = ThemeSpec.Safe.horizontal, top = ThemeSpec.Safe.vertical))
        }

        if (diorama == null) {
            MapBadge3D(
                if (state.loading) "Loading 3D radar…" else "Waiting for radar data…",
                Modifier.align(Alignment.Center),
                dot = ThemeSemantics.accent,
            )
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = ThemeSpec.Space.margin)
                .background(ThemeSemantics.scrimChip, RoundedCornerShape(ThemeSpec.Card.pill))
                .padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.sm),
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Control3D(Glyph.ROTATE_LEFT) { yaw -= 12f }
            Control3D(Glyph.ROTATE_RIGHT) { yaw += 12f }
            Control3D(Glyph.TILT_UP) { pitch = (pitch + 6f).coerceIn(15f, 85f) }
            Control3D(Glyph.TILT_DOWN) { pitch = (pitch - 6f).coerceIn(15f, 85f) }
            Control3D(if (state.playing) Glyph.PAUSE else Glyph.PLAY) { state.playing = !state.playing }
            Control3D(Glyph.CLOSE, primary = true, focusRequester = closeFocus) { onDismiss() }
        }
    }
}

@Composable
private fun MapBadge3D(text: String, modifier: Modifier = Modifier, dot: Color? = null) {
    val shape = RoundedCornerShape(ThemeSpec.Card.pill)
    Row(
        modifier.background(ThemeSemantics.scrimChip, shape)
            .border(1.dp, ThemeSemantics.hairline, shape)
            .padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.width(8.dp).height(8.dp).background(dot, RoundedCornerShape(50)))
            Spacer(Modifier.width(ThemeSpec.Space.sm))
        }
        Text(text, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1)
    }
}

/** One round control of the 3D view, drawn from the same glyph set as the 2D radar page. */
@Composable
private fun Control3D(
    glyph: Glyph,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.pill)
    val fg = if (primary) ThemeSemantics.textInverse else ThemeSemantics.textEmphatic
    Box(
        (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftIcon, pressed))
            .background(
                when { primary -> ThemeSemantics.accent; focused -> ThemeSemantics.onMedia; else -> Color.Transparent },
                shape,
            )
            .focusRing(focused, shape, idle = Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickableNoIndication(interaction, onClick)
            .padding(ThemeSpec.Space.cozy),
        contentAlignment = Alignment.Center,
    ) { RadarGlyph(glyph, fg) }
}

/** One unlit material per NEXRAD level, remembered so pillar rebuilds don't churn materials. */
@Composable
private fun rememberPillarMaterial(materialLoader: io.github.sceneview.loaders.MaterialLoader, level: Int) =
    remember(materialLoader, level) {
        materialLoader.createUnlitColorInstance(
            RadarFeed.legend[(level - 1).coerceIn(0, RadarFeed.legend.lastIndex)].toInt(),
        )
    }
