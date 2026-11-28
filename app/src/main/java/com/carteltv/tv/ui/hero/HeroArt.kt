package com.carteltv.tv.ui.hero

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** What one piece of art turns out to be once read: its shape, and the colour that leads it. */
internal data class ArtRead(
    val aspect: Float,
    val tint: Color,
) {
    /** Taller than a frame: a poster, which stands whole rather than fills. */
    val poster: Boolean get() = aspect < POSTER_ASPECT
}

/** Below this width-to-height ratio, art is a poster rather than a frame. */
private const val POSTER_ASPECT = 1.3f

/** Art is read at this many pixels on its long side: enough to tell its shape and take its leading colour. */
private const val ART_READ_PX = 24

/** Each read, by URL: a slide that comes back round is not decoded again. */
private val reads = ConcurrentHashMap<String, ArtRead>()

/**
 * Reads [url] small: once for its shape, once for the colour of its left quarter, which is the part of the band
 * the navigation bar's glass sits over. Null when there is nothing to read, so the caller falls back on its own ground.
 */
internal suspend fun readArt(
    ctx: Context,
    url: String,
): ArtRead? {
    reads[url]?.let { return it }
    val read =
        withContext(Dispatchers.IO) {
            // Hardware bitmaps answer no pixel questions, so this one is asked for in software.
            val request =
                ImageRequest
                    .Builder(ctx)
                    .data(url)
                    .size(ART_READ_PX)
                    .allowHardware(false)
                    .build()
            val bitmap =
                (runCatching { SingletonImageLoader.get(ctx).execute(request) }.getOrNull() as? SuccessResult)
                    ?.image
                    ?.let { (it as? BitmapImage)?.bitmap }
            if (bitmap == null || bitmap.width < 2 || bitmap.height < 2) {
                null
            } else {
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (x in 0 until max(1, bitmap.width / 4)) {
                    for (y in 0 until bitmap.height) {
                        val p = bitmap.getPixel(x, y)
                        red += (p shr 16) and 0xFF
                        green += (p shr 8) and 0xFF
                        blue += p and 0xFF
                        count++
                    }
                }
                ArtRead(bitmap.width / bitmap.height.toFloat(), Color(red / count, green / count, blue / count))
            }
        }
    read?.let { reads[url] = it }
    return read
}

/** The ground under a poster, or under a title that has no art: one colour rising from the ink where the copy
 *  reads to a glow where the art stands, so the band is never a flat black slab. */
@Composable
internal fun ArtField(color: Color) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.45f to color.copy(alpha = 0.22f),
                1f to color.copy(alpha = 0.55f),
            ),
        ),
    )
}

/** Each team's logo in a disc of its own colour: a mark drawn on transparency still reads as one shape. */
private val CrestSize = 150.dp

/**
 * Two teams as a matchup: away on the left, home on the right, each on its own colour and under its own logo
 * at banner scale. With no logo from the feed a side keeps only its colour.
 */
@Composable
internal fun Matchup(
    awayLogo: String?,
    homeLogo: String?,
    away: Color,
    home: Color,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to away.copy(alpha = 0.34f), 0.5f to Color.Transparent, 1f to home.copy(alpha = 0.34f)),
            ),
        )
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = ThemeSpec.Space.xl * 3),
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xl * 3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crest(awayLogo, away)
            Crest(homeLogo, home)
        }
    }
}

@Composable
private fun Crest(
    logo: String?,
    color: Color,
) {
    if (logo == null) return
    Box(
        Modifier
            .size(CrestSize)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(ThemeSpec.Space.lg),
        )
    }
}

/** A league's own mark where an event has no art of its own, at banner scale on the right of its copy. */
@Composable
internal fun LeagueCrest(logo: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AsyncImage(
            model = logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxHeight(0.45f)
                    .widthIn(max = LeagueCrestWidth)
                    .padding(end = ThemeSpec.Space.xl * 4),
        )
    }
}

private val LeagueCrestWidth = 280.dp
