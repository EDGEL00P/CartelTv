package com.carteltv.tv.ui.components
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import com.carteltv.tv.data.espn.PlayingSurface
import com.carteltv.tv.ui.theme.ThemeSemantics
import kotlin.math.cos
import kotlin.math.sin

private val Chalk = Color.White.copy(alpha = 0.60f)
private val LineWidth = 3f

/** Each surface's ground tone. Scene art, not chrome: these describe the venue, like the marking chalk above. */
private val Grass = Color(0xFF1B4023)
private val Hardwood = Color(0xFF5A3A1E)
private val IceSheet = Color(0xFF9FBAD1)
private val HardCourt = Color(0xFF274A78)
private val CanvasCloth = Color(0xFF2B2B30)
private val Asphalt = Color(0xFF33343A)
private val PoolWater = Color(0xFF1E5E8E)
private val Dirt = Color(0xFF6E4526)

private fun baseFor(surface: PlayingSurface): Color = when (surface) {
    PlayingSurface.FOOTBALL, PlayingSurface.PITCH, PlayingSurface.CRICKET, PlayingSurface.GOLF -> Grass
    PlayingSurface.DIAMOND -> Dirt
    PlayingSurface.COURT -> Hardwood
    PlayingSurface.ICE -> IceSheet
    PlayingSurface.TENNIS -> HardCourt
    PlayingSurface.CAGE, PlayingSurface.RING -> CanvasCloth
    PlayingSurface.TRACK -> Asphalt
    PlayingSurface.POOL -> PoolWater
}

/** Grass surfaces get mowing stripes; every other surface sits flat under its markings. */
private fun stripesFor(surface: PlayingSurface): Boolean = when (surface) {
    PlayingSurface.FOOTBALL, PlayingSurface.PITCH, PlayingSurface.CRICKET, PlayingSurface.GOLF -> true
    else -> false
}

/**
 * A playing surface behind a graphic: a drawn ground tone with the court or field markings
 * rendered crisply over it, veiled so the text on top stays readable.
 * Drawn, not photographed: the markings carry the read and nothing here tiles or smears.
 */
@Composable
internal fun SurfaceBackdrop(surface: PlayingSurface, modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        clipRect {
            val base = baseFor(surface)
            drawRect(base)
            if (stripesFor(surface)) {
                val stripe = Color.White.copy(alpha = 0.05f)
                for (i in 0 until 12) {
                    if (i % 2 == 0) drawRect(stripe, Offset(size.width * i / 12, 0f), Size(size.width / 12, size.height))
                }
            }
            when (surface) {
                PlayingSurface.FOOTBALL -> football()
                PlayingSurface.PITCH -> pitch()
                PlayingSurface.COURT -> court()
                PlayingSurface.ICE -> ice()
                PlayingSurface.TENNIS -> tennis()
                PlayingSurface.CAGE -> cage()
                PlayingSurface.RING -> ring()
                PlayingSurface.TRACK -> track()
                PlayingSurface.CRICKET -> cricket()
                PlayingSurface.POOL -> pool()
                PlayingSurface.GOLF -> Unit
                PlayingSurface.DIAMOND -> diamond()
            }
            val veil = if (surface == PlayingSurface.ICE) 0.45f else 0.36f
            // Lighter at the top so the field's markings show, heavier toward the foot where the copy sits.
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = veil * 0.5f), Color.White.copy(alpha = veil * 0.9f))))
        }
    }
}

private fun DrawScope.football() {
    for (i in 1 until 20) drawLine(Chalk, Offset(size.width * i / 20, 0f), Offset(size.width * i / 20, size.height), 2f)
    for (k in 0..99) for (y in listOf(0.36f, 0.64f)) {
        val x = size.width * k / 100
        drawLine(Chalk, Offset(x, size.height * y), Offset(x, size.height * y + 10f), 2f)
    }
    drawLine(Color.White, Offset(0f, size.height * 0.05f), Offset(size.width, size.height * 0.05f), 5f)
    drawLine(Color.White, Offset(0f, size.height * 0.95f), Offset(size.width, size.height * 0.95f), 5f)
}

private fun DrawScope.pitch() {
    val w = size.width; val h = size.height
    val frame = Stroke(LineWidth)
    drawRect(Chalk, Offset(w * 0.03f, h * 0.06f), Size(w * 0.94f, h * 0.88f), style = frame)
    drawLine(Chalk, Offset(w / 2, h * 0.06f), Offset(w / 2, h * 0.94f), LineWidth)
    drawCircle(Chalk, h * 0.22f, Offset(w / 2, h / 2), style = frame)
    for (x in listOf(w * 0.03f, w * 0.97f - w * 0.12f)) drawRect(Chalk, Offset(x, h * 0.25f), Size(w * 0.12f, h * 0.5f), style = frame)
}

private fun DrawScope.court() {
    val w = size.width; val h = size.height
    val frame = Stroke(LineWidth)
    drawRect(Chalk, Offset(w * 0.02f, h * 0.06f), Size(w * 0.96f, h * 0.88f), style = frame)
    drawLine(Chalk, Offset(w / 2, h * 0.06f), Offset(w / 2, h * 0.94f), LineWidth)
    drawCircle(Chalk, h * 0.2f, Offset(w / 2, h / 2), style = frame)
    drawRect(Chalk, Offset(w * 0.02f, h * 0.32f), Size(w * 0.18f, h * 0.36f), style = frame)
    drawRect(Chalk, Offset(w * 0.98f - w * 0.18f, h * 0.32f), Size(w * 0.18f, h * 0.36f), style = frame)
    drawArc(Chalk, -65f, 130f, false, Offset(w * 0.2f - h * 0.42f, h * 0.5f - h * 0.42f), Size(h * 0.84f, h * 0.84f), style = frame)
    drawArc(Chalk, 115f, 130f, false, Offset(w * 0.8f - h * 0.42f, h * 0.5f - h * 0.42f), Size(h * 0.84f, h * 0.84f), style = frame)
}

private fun DrawScope.ice() {
    val w = size.width; val h = size.height
    val red = ThemeSemantics.cornerRed.copy(alpha = 0.80f)
    val blue = ThemeSemantics.cornerBlue.copy(alpha = 0.75f)
    drawLine(red, Offset(w / 2, 0f), Offset(w / 2, h), 7f)
    for (x in listOf(0.37f, 0.63f)) drawLine(blue, Offset(w * x, 0f), Offset(w * x, h), 9f)
    for (x in listOf(0.08f, 0.92f)) drawLine(red, Offset(w * x, 0f), Offset(w * x, h), 3f)
    drawCircle(blue, h * 0.18f, Offset(w / 2, h / 2), style = Stroke(LineWidth))
    for ((x, y) in listOf(0.22f to 0.28f, 0.22f to 0.72f, 0.78f to 0.28f, 0.78f to 0.72f)) {
        drawCircle(red, h * 0.18f, Offset(w * x, h * y), style = Stroke(LineWidth))
    }
}

private fun DrawScope.tennis() {
    val w = size.width; val h = size.height
    val line = Color.White.copy(alpha = 0.80f)
    val frame = Stroke(LineWidth)
    drawRect(line, Offset(w * 0.06f, h * 0.08f), Size(w * 0.88f, h * 0.84f), style = frame)
    drawLine(line, Offset(w * 0.06f, h * 0.2f), Offset(w * 0.94f, h * 0.2f), LineWidth)
    drawLine(line, Offset(w * 0.06f, h * 0.8f), Offset(w * 0.94f, h * 0.8f), LineWidth)
    for (x in listOf(0.28f, 0.72f)) drawLine(line, Offset(w * x, h * 0.2f), Offset(w * x, h * 0.8f), LineWidth)
    drawLine(line, Offset(w * 0.28f, h / 2), Offset(w * 0.72f, h / 2), LineWidth)
    drawLine(Color.White.copy(alpha = 0.45f), Offset(w / 2, h * 0.04f), Offset(w / 2, h * 0.96f), 6f)
}

private fun DrawScope.octagon(center: Offset, radius: Float): Path = Path().apply {
    for (i in 0 until 8) {
        val a = Math.toRadians(22.5 + i * 45.0)
        val p = Offset(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat())
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun DrawScope.cage() {
    val c = Offset(size.width / 2, size.height / 2)
    drawPath(octagon(c, size.height * 0.62f), Color.White.copy(alpha = 0.40f), style = Stroke(4f))
    drawPath(octagon(c, size.height * 0.46f), Color.White.copy(alpha = 0.16f), style = Stroke(2f))
}

private fun DrawScope.ring() {
    val w = size.width; val h = size.height
    for ((y, color) in listOf(0.28f to ThemeSemantics.cornerRed, 0.5f to Color.White, 0.72f to ThemeSemantics.cornerBlue)) {
        drawLine(color.copy(alpha = 0.70f), Offset(w * 0.25f, h * y), Offset(w * 0.75f, h * y), 4f)
    }
    for ((x, y) in listOf(0.25f to 0.08f, 0.75f to 0.08f, 0.25f to 0.92f, 0.75f to 0.92f)) {
        drawCircle(Color.White.copy(alpha = 0.65f), 8f, Offset(w * x, h * y))
    }
    drawRect(Color.White.copy(alpha = 0.18f), Offset(w * 0.25f, h * 0.08f), Size(w * 0.5f, h * 0.84f), style = Stroke(2f))
}

private fun DrawScope.track() {
    val w = size.width; val h = size.height
    val a = Offset(-w * 0.1f, h * 1.15f); val b = Offset(w * 1.1f, -h * 0.15f)
    val kerb = PathEffect.dashPathEffect(floatArrayOf(36f, 36f))
    val dx = h * 0.7f
    drawLine(ThemeSemantics.cornerRed, Offset(a.x - dx, a.y), Offset(b.x - dx, b.y), 10f, pathEffect = kerb)
    drawLine(Color.White, Offset(a.x - dx + 36f, a.y), Offset(b.x - dx + 36f, b.y), 10f, pathEffect = kerb)
    drawLine(ThemeSemantics.cornerRed, Offset(a.x + dx, a.y), Offset(b.x + dx, b.y), 10f, pathEffect = kerb)
    drawLine(Color.White.copy(alpha = 0.70f), a, b, 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(60f, 50f)))
}

private fun DrawScope.cricket() {
    val w = size.width; val h = size.height
    drawOval(Chalk, Offset(w * 0.04f, h * 0.06f), Size(w * 0.92f, h * 0.88f), style = Stroke(LineWidth))
    drawRect(Color(0xFFC9B27A).copy(alpha = 0.85f), Offset(w * 0.35f, h * 0.43f), Size(w * 0.3f, h * 0.14f))
    for (x in listOf(0.36f, 0.64f)) drawLine(Color.White, Offset(w * x, h * 0.43f), Offset(w * x, h * 0.57f), LineWidth)
}

private fun DrawScope.diamond() {
    val w = size.width; val h = size.height
    drawCircle(Grass, w * 0.30f, Offset(w * 0.5f, h * 0.62f))
    val home = Offset(w * 0.5f, h * 0.30f)
    val first = Offset(w * 0.30f, h * 0.52f)
    val second = Offset(w * 0.70f, h * 0.52f)
    val third = Offset(w * 0.5f, h * 0.74f)
    for ((a, b) in listOf(home to first, first to second, second to third, third to home)) {
        drawLine(Chalk, a, b, LineWidth)
    }
    drawCircle(Color.White.copy(alpha = 0.70f), 6f, home)
}

private fun DrawScope.pool() {
    val h = size.height
    for (i in 1 until 6) {
        drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, h * i / 6), Offset(size.width, h * i / 6), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
    }
}