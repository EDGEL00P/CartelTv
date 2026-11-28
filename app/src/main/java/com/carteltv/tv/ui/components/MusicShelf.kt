package com.carteltv.tv.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.theme.glassSurface

/** Shelf heading in the YouTube Music pattern: a small uppercase eyebrow over a bold title. */
@Composable
fun MusicShelfHeader(title: String, modifier: Modifier = Modifier, eyebrow: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
        if (eyebrow != null) Text(
            eyebrow.uppercase(), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary,
            fontFamily = ThemeSpec.Font.broadcast, letterSpacing = ThemeSpec.Type.trackCaps,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            title, fontSize = ThemeSpec.Type.titleL, fontWeight = FontWeight.Bold, fontFamily = ThemeSpec.Font.display,
            color = ThemeSemantics.textEmphatic, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Square artwork tile with a bold title and a two-line quiet subtitle underneath. */
@Composable
fun MusicArtCard(
    title: String,
    subtitle: String?,
    art: Any?,
    modifier: Modifier = Modifier,
    width: Dp = 220.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    Column(
        modifier.width(width)
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .clickableNoIndication(interaction, onClick),
    ) {
        Column(Modifier.fillMaxWidth().aspectRatio(1f).glassSurface(focused, shape)) {
            if (art != null) AsyncImage(
                model = art, contentDescription = title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) else CardArtwork(url = art as? String?, title = title, modifier = Modifier.fillMaxSize())
        }
        Text(
            title, fontSize = ThemeSpec.Type.body, fontWeight = FontWeight.SemiBold, fontFamily = ThemeSpec.Font.broadcast,
            color = ThemeSemantics.textEmphatic, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = ThemeSpec.Space.sm),
        )
        if (subtitle != null) Text(
            subtitle, fontSize = ThemeSpec.Type.label, fontFamily = ThemeSpec.Font.body, color = ThemeSemantics.textSecondary,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = ThemeSpec.Space.xs),
        )
    }
}
