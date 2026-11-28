package com.carteltv.tv.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Scales the alpha a token already has. `copy(alpha = x)` would replace it, and Glass panels are translucent. */
fun Color.scaleAlpha(factor: Float): Color = copy(alpha = alpha * factor)

/** Genre tints for guide cells: a categorical data palette from the concept
 *  (sports=orange, news/drama=blue), not chrome. */
object GenreTones {
    val sports = ThemePrimitives.graphite
    val news = ThemePrimitives.graphite
    val movies = ThemePrimitives.graphite
    val nature = ThemePrimitives.graphite
    val kids = ThemePrimitives.graphite
}

/** The one in-progress marker: a small dot and the word, in ink, on nothing. The dot breathes; nothing else on the desk animates. */
@Composable
fun LiveBadge(text: String = "LIVE", modifier: Modifier = Modifier) {
    val dot by rememberInfiniteTransition("livePulse").animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(ThemeSpec.Motion.PULSE_MS / 2), RepeatMode.Reverse),
        label = "liveDot",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(ThemeSemantics.live.copy(alpha = dot)))
        Spacer(Modifier.width(ThemeSpec.Space.sm))
        Text(text.uppercase(), color = ThemeSemantics.textEmphatic, fontSize = ThemeSpec.Type.label, fontFamily = ThemeSpec.Font.broadcast,
            fontWeight = FontWeight.SemiBold, letterSpacing = ThemeSpec.Type.trackCaps)
    }
}
