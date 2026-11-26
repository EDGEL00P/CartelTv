package com.carteltv.tv.ui.nav
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carteltv.tv.data.xtream.ChannelLogos
import com.carteltv.tv.ui.live.NameLogo
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec

/** A network's real logo from the bundled catalogue, or its name as a small badge when there is none. */
@Composable
internal fun NetworkBadge(name: String, size: Dp) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ChannelLogos.ensureLoaded(ctx) }
    val hasLogo = ChannelLogos.loaded && ChannelLogos.urlFor(name) != null
    Box(Modifier.padding(start = ThemeSpec.Space.sm)) {
        if (hasLogo) NameLogo(name, size)
        else Box(
            Modifier.height(size).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)).background(ThemeSemantics.surfaceRest).padding(horizontal = ThemeSpec.Space.sm),
            contentAlignment = Alignment.Center,
        ) { Text(name, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.accent, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}
