package com.carteltv.tv.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carteltv.tv.data.weather.ConvRisk
import com.carteltv.tv.data.weather.Threat
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.components.onAccent

@Composable
fun Threat.color(): Color = when (this) {
    Threat.CLEAR -> ThemeSemantics.textSecondary
    Threat.DISTANT -> ThemeSemantics.info
    Threat.WATCH -> ThemeSemantics.accent
    Threat.WARNING -> ThemeSemantics.accentBright
    Threat.DANGER -> ThemeSemantics.danger
}

@Composable
fun ConvRisk.color(): Color = when (this) {
    ConvRisk.NONE -> ThemeSemantics.textTertiary
    ConvRisk.LOW -> ThemeSemantics.info
    ConvRisk.MODERATE -> ThemeSemantics.accent
    ConvRisk.HIGH -> ThemeSemantics.accentBright
    ConvRisk.EXTREME -> ThemeSemantics.danger
}

/** Label, big value, caption, and an optional glyph top-right. A D-pad stop. */
@Composable
fun WeatherTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    valueColor: Color = ThemeSemantics.textEmphatic,
    visual: (@Composable () -> Unit)? = null,
) {
    WeatherFocusCard(modifier.height(108.dp)) {
        Box(Modifier.fillMaxSize().padding(ThemeSpec.Space.md)) {
            Column(Modifier.align(Alignment.TopStart)) {
                Text(
                    label.uppercase(), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary,
                    fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.Bold, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1,
                )
                Spacer(Modifier.height(ThemeSpec.Space.sm))
                Text(
                    value, fontSize = ThemeSpec.Type.cardTitle, fontWeight = FontWeight.SemiBold, fontFamily = ThemeSpec.Font.mono,
                    color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (sub != null) Text(
                sub, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary,
                fontFamily = ThemeSpec.Font.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart),
            )
            if (visual != null) Box(Modifier.align(Alignment.TopEnd).padding(top = ThemeSpec.Space.xs)) { visual() }
        }
    }
}

/** Compass ring with a Phosphor navigation arrow pointing toward [deg] (clockwise from north). */
@Composable
fun CompassArrow(deg: Float, color: Color, modifier: Modifier = Modifier.size(40.dp)) {
    val ring = ThemeSemantics.hairline
    val tick = ThemeSemantics.textTertiary
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.minDimension / 100f
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(ring, 46 * u, c, style = Stroke(2.5f * u))
            drawLine(tick, Offset(c.x, 2 * u), Offset(c.x, 10 * u), strokeWidth = 3 * u)
        }
        Image(
            painterResource(com.carteltv.tv.R.drawable.ph_navigation_arrow),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = Modifier.fillMaxSize().padding(9.dp).graphicsLayer { rotationZ = deg - 45f },
        )
    }
}

/** Subtle chip that states a fact on a card, e.g. "Rising". */
@Composable
fun WeatherPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold, color = onAccent(color), maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(ThemeSpec.Card.pill)).background(color).padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.xs),
    )
}
