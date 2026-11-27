package com.carteltv.tv.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.carteltv.tv.ui.components.CardArtwork
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec

/**
 * The shared hero artwork: key art (or a monogram fallback) under the house veil — clear at the top, deep at the
 * foot. The billboard and every detail page draw their art through this, so the veil and fallback read identically.
 */
@Composable
internal fun HeroArt(
    art: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (!art.isNullOrBlank()) {
            AsyncImage(
                model = art,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CardArtwork(url = null, title = title, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to ThemeSemantics.scrimDeep.copy(alpha = 0.35f),
                    1f to ThemeSemantics.scrimDeep.copy(alpha = 0.9f),
                ),
            ),
        )
    }
}

/**
 * The shared detail hero: a [DetailArtHeight] key-art plate under the house veil, with the title (or a logo)
 * across its foot. Movie, series and add-on detail pages all lead with this, so the three read as one design.
 */
@Composable
internal fun DetailHeroPlate(
    art: String?,
    title: String,
    modifier: Modifier = Modifier,
    logo: String? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(DetailArtHeight)
            .clip(RoundedCornerShape(ThemeSpec.Card.corner))
            .background(ThemeSemantics.surfaceRest),
    ) {
        HeroArt(art, title, Modifier.fillMaxSize())
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(ThemeSpec.Space.md)
                        .widthIn(max = 320.dp)
                        .height(90.dp),
            )
        } else {
            Text(
                title,
                fontSize = ThemeSpec.Type.display,
                fontWeight = FontWeight.Bold,
                color = ThemeSemantics.textEmphatic,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(ThemeSpec.Space.md),
            )
        }
    }
}
