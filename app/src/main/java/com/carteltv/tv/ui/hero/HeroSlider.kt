package com.carteltv.tv.ui.hero
import com.carteltv.tv.ui.weather.WeatherIcon
import com.carteltv.tv.data.espn.GameChannelResolver
import com.carteltv.tv.data.espn.GameChannelFinder
import com.carteltv.tv.data.espn.GamePhotos
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.carteltv.tv.data.espn.EspnScoreboardClient
import com.carteltv.tv.data.events.Holiday
import com.carteltv.tv.data.events.MajorEvent
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.weather.Forecast
import com.carteltv.tv.data.weather.Place
import com.carteltv.tv.data.weather.Sky
import com.carteltv.tv.data.weather.VenueWeather
import com.carteltv.tv.data.weather.WeatherUnits
import com.carteltv.tv.data.xtream.ChannelLogos
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.ui.components.RollingText
import com.carteltv.tv.ui.components.ClockFormat
import com.carteltv.tv.ui.components.TABULAR
import com.carteltv.tv.ui.live.NameLogo
import com.carteltv.tv.ui.sports.clockLabel
import com.carteltv.tv.ui.sports.plateColors
import com.carteltv.tv.ui.theme.PhosphorIcon
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.enterScene
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as DayNameStyle
import java.util.Locale

/** One page of the hero. */
internal sealed interface HeroSlide {
    data class Media(val item: HeroItem) : HeroSlide
    /** A one-sided event, with its league's own mark when a feed on Home carries one. */
    data class Event(val event: MajorEvent, val logo: String? = null) : HeroSlide
    data class Game(val game: BigEvent) : HeroSlide
    data class HolidayCountdown(val holiday: Holiday) : HeroSlide
    data object Outlook : HeroSlide
}

/** What the forecast page draws from; leave it out for a media-only hero. */
@Immutable
internal class HeroWeather(val forecast: Forecast?, val place: Place)

/** Unified hero item from any source (continue-watching, live, VOD). */
internal data class HeroItem(
    val title: String,
    val art: String?,
    val description: String?,
    val badge: String?,
    val refId: String,
    val url: String,
    val progress: Float = 0f,
    /** The section OK opens when there is nothing to play: the greeting, pointing where its text does. */
    val opens: AppSection? = null,
    /** A direct stream the hero can play muted while it holds focus; null when only art will do. */
    val previewUrl: String? = null,
)

/** How many shows being watched, and how many films, the banner may pick. */
private const val HERO_WATCHED = 3
private const val HERO_FILMS = 5

/**
 * Build hero candidates from continue-watching + VOD. Resolved artwork for entries the player
 * saved without a poster arrives in [cwArt], keyed by [com.carteltv.tv.ui.live.GuideMetadata.cwArtKey].
 *
 * A show stands here once however many episodes were watched, and a live channel never does. Nothing
 * named in [shown] (see [com.carteltv.tv.ui.home.titleKey]) does either: the row under the banner has it,
 * and the same title twice, one above the other, reads as a repeat.
 */
internal fun buildHeroItems(
    recents: List<com.carteltv.tv.data.preferences.AppPreferences.RecentEntry>,
    channels: List<com.carteltv.tv.data.xtream.XtreamClient.LiveStream>,
    movies: List<com.carteltv.tv.data.xtream.XtreamClient.VodStream>,
    hour: Int,
    cwArt: Map<String, String> = emptyMap(),
    shown: Set<String> = emptySet(),
): List<HeroItem> {
    val items = mutableListOf<HeroItem>()

    com.carteltv.tv.ui.home.resumeRecents(recents, HERO_WATCHED).forEach { e ->
        if (e.url.isNotBlank() && com.carteltv.tv.ui.home.titleKey(com.carteltv.tv.ui.home.splitTitle(e.title).name) !in shown) {
            // The banner names the show, as its card does; where the episode sits leads the line beneath.
            val parts = com.carteltv.tv.ui.home.splitTitle(e.title)
            items.add(HeroItem(
                title = parts.name,
                art = e.poster.ifBlank { null } ?: cwArt[com.carteltv.tv.ui.live.GuideMetadata.cwArtKey(e.title)],
                description = com.carteltv.tv.ui.home.resumeDescription(parts, e.positionMs, e.durationMs),
                badge = if (e.durationMs > 0) "${com.carteltv.tv.ui.theme.cartelGreeting(hour)} · Resume" else null,
                refId = e.ref,
                url = e.url,
                progress = if (e.durationMs > 0) (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f) else 0f,
            ))
        }
    }

    com.carteltv.tv.ui.home.unseenTitles(movies, shown) { it.name }.take(HERO_FILMS).forEach { m ->
        items.add(HeroItem(
            title = m.name,
            art = m.icon,
            description = m.rating?.toFloatOrNull()?.let { "Rated %.1f. ${com.carteltv.tv.ui.home.PLAY_HINT}".format(it) } ?: com.carteltv.tv.ui.home.PLAY_HINT,
            badge = m.rating?.toFloatOrNull()?.let { "%.1f".format(it) },
            refId = "movie:${m.streamId}",
            url = com.carteltv.tv.data.xtream.ProviderSession.current?.movieUrl(m.streamId, m.ext).orEmpty(),
            previewUrl = com.carteltv.tv.data.xtream.ProviderSession.current?.movieUrl(m.streamId, m.ext),
        ))
    }

    return items.take(8)
}

/** How long a hero slide holds before the carousel advances; the slide dots draw this as a fill. */
internal const val HERO_ROTATE_MS = 10_000L

private const val OUTLOOK_DAYS = 5

/** The forecast's day cells on the right, and the copy beside them, clear of each other on a 960dp-wide screen. */
private val OutlookWidth = 400.dp
private val OutlookCopyWidth = 320.dp
private val DayCellHeight = 148.dp

/** How wide a slide's copy may run: a title with two lines of description, or a score line with both marks. */
private val CopyWidth = 520.dp

/** A game's copy, kept clear of the matchup's crests at the band's right. */
private val GameCopyWidth = 340.dp

/** An event's copy, kept clear of its league's crest. */
private val EventCopyWidth = 420.dp

/** The copy's distance from the banner's foot, which leaves the page dots their own line beneath it. */
private val CopyFoot = 40.dp

/** A poster's widest stand at the band's right: clear of the copy column on a 960dp screen. */
private val PosterWidth = 340.dp

// The stage every score slide is set on.
private val BugBlack get() = ThemeSemantics.mediaInk

// Head-to-head athletes (UFC, boxing, tennis) have no brand colours, so they take the corners.
private val RedCorner get() = ThemeSemantics.cornerRed
private val BlueCorner get() = ThemeSemantics.cornerBlue

/** Black scrims keep hero copy readable without washing the artwork out. */
private fun Modifier.heroVeil(): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            0.45f to Color.Black.copy(alpha = 0.12f),
            1f to Color.Black.copy(alpha = 0.82f),
        ),
    )
}
    .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.88f), 0.5f to Color.Black.copy(alpha = 0.45f), 0.85f to Color.Transparent))

/** A slide's ground: everything it paints behind its copy. It dissolves into the stage at its foot, so the banner
 *  meets the row beneath without a cut edge; the copy is drawn above it and stays solid. */
@Composable
private fun SlideGround(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier.fillMaxSize().footFade(), content = content)
}

/** DstIn only bites inside an offscreen layer, hence the compositing strategy: everything drawn here keeps its
 *  colour and loses its alpha toward the foot, revealing whatever stage is behind. */
private fun Modifier.footFade(): Modifier = graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.verticalGradient(0f to Color.White, 0.86f to Color.White, 1f to Color.Transparent),
        blendMode = BlendMode.DstIn,
    )
}

/**
 * The one banner. Pages are media picks plus, optionally, the forecast. Left/right turn the page, OK opens
 * it. While a game card is focused the banner shows that game instead ([focusedGame]). The whole band is the
 * focus target; the action inside answers the press. [contentInset] is the distance from the band's own left edge
 * to the content edge beside it, so the copy never hides under the sidebar; a band that starts there passes none.
 */
@Composable
internal fun HeroSlider(
    slides: List<HeroSlide>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onOpen: (HeroSlide) -> Unit,
    modifier: Modifier = Modifier,
    contentInset: Dp = 0.dp,
    focusedGame: BigEvent? = null,
    weather: HeroWeather? = null,
    /** Receives the colour of the art holding the banner, so the stage can wash with it (null clears). */
    onAmbient: (Color?) -> Unit = {},
) {
    val shown: HeroSlide? = focusedGame?.let { HeroSlide.Game(it) } ?: slides.getOrNull(index)
    val interaction = remember { MutableInteractionSource() }
    val hasFocus by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    // Pages the remote turned forward. Left turns back through those; past them it hands over (Home sends it to the
    // sidebar), so pages the carousel advanced to on its own never cost extra presses on the way there.
    var turned by remember { mutableIntStateOf(0) }
    // The banner is the one surface that lifts only a little: the concept's 1.02. Its focus shows in the action,
    // which turns solid white, because a ring around a band that meets the screen edge would sit in the overscan.
    val scale = focusScaleState(hasFocus, ThemeSpec.Focus.liftHero, pressed = pressed)
    Box(
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .focusLift(scale)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || slides.size < 2) return@onPreviewKeyEvent false
                when (ev.key) {
                    // Right wraps round to the first page. Left turns back only through pages the remote turned forward,
                    // so the first page (and any auto-advanced one) hands Left on to the sidebar like the first card of any row.
                    Key.DirectionRight -> {
                        turned++
                        onIndexChange((index + 1) % slides.size)
                        true
                    }

                    Key.DirectionLeft -> {
                        if (turned > 0 && index > 0) {
                            turned--
                            onIndexChange(index - 1)
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
            .clickableNoIndication(interaction) { shown?.let(onOpen) },
    ) {
        Crossfade(targetState = shown, animationSpec = tween(ThemeSpec.Motion.DUR_SLOW), label = "heroSlide") { slide ->
            Box(Modifier.fillMaxSize()) {
                when (slide) {
                    is HeroSlide.Media -> MediaSlide(slide.item, contentInset, hasFocus && slide === shown, pressed, onAmbient)
                    is HeroSlide.Event -> EventSlide(slide.event, slide.logo, contentInset, hasFocus, pressed)
                    is HeroSlide.Game -> GameSlide(slide.game, contentInset, hasFocus, pressed)
                    is HeroSlide.HolidayCountdown -> HolidayCountdownSlide(slide.holiday, contentInset)
                    HeroSlide.Outlook -> weather?.forecast?.let { OutlookSlide(it, weather.place, contentInset, hasFocus, pressed) }
                    else -> Unit
                }
            }
        }
        // Only a media page reads art; a game, the outlook or a countdown clears the wash so the
        // stage returns to its floor with the band.
        LaunchedEffect(shown) { if (shown !is HeroSlide.Media) onAmbient(null) }
        // The dots count the pages from the middle of the banner's foot, and step forward while it holds focus.
        if (focusedGame == null && slides.size > 1) {
            PageDots(slides.size, index, hasFocus, Modifier.align(Alignment.BottomCenter).padding(bottom = ThemeSpec.Space.lg))
        }
        // A whisper at the right edge that more pages exist: a decorative chevron, never focusable or clickable,
        // so it takes no part in the band's key handling and only ever overlays the art.
        if (slides.size > 1) {
            PhosphorIcon(
                com.carteltv.tv.R.drawable.ph_caret_right,
                ThemeSemantics.onMedia.copy(alpha = 0.30f),
                16.dp,
                Modifier.align(Alignment.CenterEnd).padding(end = ThemeSpec.Space.xl),
            )
        }
    }
}

/** The page dots: small white points, with the active one stretched to a pill whose fill is the timer. The fill
 *  grows across the hold, so the next slide never arrives unannounced, and is read at draw time, so the timer
 *  never recomposes the row. */
@Composable
private fun PageDots(count: Int, active: Int, focused: Boolean, modifier: Modifier) {
    val hold = remember { Animatable(0f) }
    LaunchedEffect(count, active) {
        hold.snapTo(0f)
        hold.animateTo(1f, tween(HERO_ROTATE_MS.toInt(), easing = LinearEasing))
    }
    val lit = ThemeSemantics.onMedia.copy(alpha = if (focused) 0.95f else 0.75f)
    val rest = ThemeSemantics.onMedia.copy(alpha = if (focused) 0.45f else 0.30f)
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy + ThemeSpec.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val on = i == active
            val width by animateDpAsState(if (on) 18.dp else 4.dp, tween(ThemeSpec.Motion.DUR_MID, easing = ThemeSpec.Motion.easeEnter), label = "dotWidth")
            Box(
                Modifier
                    .height(4.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(if (on) rest else lit)
                    .then(
                        if (on) Modifier.drawBehind { drawRect(color = lit, size = Size(size.width * hold.value, size.height)) } else Modifier,
                    ),
            )
        }
    }
}

private fun whenLabel(startMs: Long, live: Boolean, nowMs: Long): String {
    if (live) return "LIVE"
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(startMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val time = ClockFormat.time(startMs, AppPreferences.clock24)
    return when (start.toLocalDate()) {
        today -> "Today · $time"
        today.plusDays(1) -> "Tomorrow · $time"
        else -> ClockFormat.dateMedium(startMs) + " · $time"
    }
}

/** Venue, local time and weather for the strip; every part appears only when the data exists. */
private fun venueLine(game: EspnScoreboardClient.Score, venue: VenueWeather.Conditions?, nowMs: Long): String? {
    val parts = mutableListOf<String>()
    listOfNotNull(game.venue, game.location).joinToString(", ").takeIf { it.isNotBlank() }?.let(parts::add)
    venue?.let {
        parts += "${VenueWeather.localTime(nowMs, it.utcOffsetSec, AppPreferences.clock24)} local (${VenueWeather.utcLabel(it.utcOffsetSec)})"
    }
    when {
        game.indoor == true -> parts += "Indoors"
        venue != null -> parts += "${WeatherUnits.temp(venue.tempC)} ${venue.sky.label}"
    }
    return parts.joinToString(" · ").ifBlank { null }
}

/** Where every slide sets its copy: the lower left, a standard gutter in from the content edge ([inset] is the
 *  distance from the banner's own edge to that content edge, so the copy never hides under the sidebar) and
 *  [width] wide. [action] is the one thing OK does. */
@Composable
private fun HeroCopy(inset: Dp, width: Dp, action: (@Composable RowScope.() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(start = inset + ThemeSpec.Space.xl, end = ThemeSpec.Space.xl, bottom = CopyFoot)) {
        Column(
            Modifier.align(Alignment.BottomStart).width(width),
            verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy),
        ) {
            content()
            action?.let {
                Row(
                    Modifier.enterScene(delayMs = ThemeSpec.Motion.STAGGER * 3).padding(top = ThemeSpec.Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                    content = it,
                )
            }
        }
    }
}

/** The line above the title: an accent dot and tracked caps. */
@Composable
private fun HeroKicker(text: String) {
    Row(Modifier.enterScene(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
        Box(Modifier.size(ThemeSpec.Space.sm).clip(CircleShape).background(ThemeSemantics.onMedia))
        Text(
            // Weighted so a long kicker truncates here instead of pushing the dot row past the copy column.
            text, modifier = Modifier.weight(1f), fontSize = ThemeSpec.Type.meta, fontWeight = FontWeight.SemiBold,
            color = ThemeSemantics.onMedia, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The hero's title: display caps, two lines at most. */
@Composable
private fun HeroTitle(text: String, italic: Boolean = false) {
    Text(
        text, fontSize = ThemeSpec.Type.display, lineHeight = ThemeSpec.Type.display * 1.1f,
        fontWeight = FontWeight.Bold, fontFamily = ThemeSpec.Font.display, letterSpacing = ThemeSpec.Type.trackHeadline,
        fontStyle = if (italic) FontStyle.Italic else null,
        color = ThemeSemantics.onMedia, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.enterScene(delayMs = ThemeSpec.Motion.STAGGER),
    )
}

/** The hero's primary action (CTA), always present so the billboard always offers its next step. At rest it is a
 *  low-emphasis glass button; as the banner takes focus it becomes the solid, raised primary button and dips while
 *  OK is held — the focus indicator (fill + scale) lands the eye where the press will go. */
@Composable
private fun HeroCta(label: String, focused: Boolean, pressed: Boolean) {
    val lift = animateFloatAsState(
        targetValue = if (pressed) ThemeSpec.Focus.pressed else if (focused) ThemeSpec.Focus.lift else 1f,
        animationSpec = ThemeSpec.Motion.focusSpring(), label = "heroCta",
    )
    val ink = if (focused) ThemeSemantics.onMediaInk else ThemeSemantics.onMedia
    val fill by animateColorAsState(
        if (focused) ThemeSemantics.onMedia else ThemeSemantics.surfaceRaised,
        tween(ThemeSpec.Motion.DUR_MID, easing = ThemeSpec.Motion.easeEnter), label = "heroCtaFill",
    )
    Row(
        Modifier
            .focusLift(lift)
            .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
            .background(fill)
            .then(if (focused) Modifier else Modifier.border(1.dp, ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.cornerSm)))
            .padding(start = ThemeSpec.Space.md, end = ThemeSpec.Space.lg, top = ThemeSpec.Space.cozy, bottom = ThemeSpec.Space.cozy),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
    ) {
        PhosphorIcon(com.carteltv.tv.R.drawable.ph_play, ink, 12.dp)
        Text(
            label, fontSize = ThemeSpec.Type.body, fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.Bold,
            color = ink, maxLines = 1,
        )
    }
}

/** The house progress track beside the action: a translucent rail with the accent fill. */
@Composable
private fun ResumeTrack(progress: Float) {
    Box(Modifier.width(80.dp).height(2.dp).clip(CircleShape).background(ThemeSemantics.progressTrack)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(ThemeSemantics.onMedia))
    }
}

/** What the action says on a page that opens a section instead of playing. */
private fun AppSection.sectionLabel(): String =
    when (this) {
    AppSection.SETUP -> "Open Settings"
    AppSection.LIVE -> "Watch Live TV"
    else -> "Browse Movies"
}

/**
 * A media pick under the veil, then the copy in the TV order — kicker, title, description, action. A frame (a
 * backdrop, a cover) fills the band. A poster stands whole at the right on a field of its own colour, because
 * cropped to a banner it would be one blurry slice. With no art, the field alone is the ground.
 */
@Composable
private fun MediaSlide(hero: HeroItem, inset: Dp, ctaFocused: Boolean, pressed: Boolean, onAmbient: (Color?) -> Unit = {}) {
    val ctx = LocalContext.current
    val art = hero.art?.takeIf { it.isNotBlank() }
    // Read once, small: whether it is a frame or a poster, and the colour the sidebar's glass picks up.
    val read by produceState<ArtRead?>(null, art) { value = art?.let { readArt(ctx, it) } }
    // The art's leading colour flows out to the stage behind the shelves, so the banner and the
    // rows below it read as one canvas.
    LaunchedEffect(read) { onAmbient(read?.tint) }
    // One slow push per slide, not an endless loop: the art settles, and nothing keeps animating behind the scenes.
    val push = remember { Animatable(1f) }
    LaunchedEffect(hero.art) {
        push.snapTo(1f)
        push.animateTo(1.06f, tween(18_000, easing = LinearEasing))
    }
    val resume = hero.progress > 0f
    Box(Modifier.fillMaxSize()) {
        SlideGround(Modifier.background(BugBlack)) {
            val r = read
            when {
                // Still being read, or nothing to show: the field alone, so the band is never a flat black slab.
                art == null || r == null -> ArtField(BugBlack)
                r.poster -> {
                    ArtField(BugBlack)
                    // Sized by height with the read aspect, and capped, so a wide poster can never run over the copy column.
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(art).crossfade(ThemeSpec.Motion.DUR_SLOW).build(),
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = ThemeSpec.Space.xl * 3)
                            .fillMaxHeight(0.9f).aspectRatio(r.aspect).widthIn(max = PosterWidth),
                    )
                }
                else -> AsyncImage(
                    // Decode in place: the art dissolves onto the plate instead of popping.
                    model = ImageRequest.Builder(ctx).data(art).crossfade(ThemeSpec.Motion.DUR_SLOW).build(),
                    contentDescription = null, contentScale = ContentScale.Crop,
                    alignment = Alignment.CenterEnd,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = push.value; scaleY = push.value },
                )
            }
            Box(Modifier.fillMaxSize().heroVeil())
            // The Netflix/Disney-style muted preview: the slide's own stream, behind the veil, only while
            // this slide holds the hero's focus. No preview, or one that cannot start, leaves the art.
            HeroPreview(
                hero.previewUrl,
                active = ctaFocused && hero.progress == 0f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        HeroCopy(inset, CopyWidth, action = {
            HeroCta(hero.opens?.sectionLabel() ?: if (resume) "Resume" else "Watch Now", ctaFocused, pressed)
            if (resume) ResumeTrack(hero.progress)
        }) {
            hero.badge?.takeIf { it.isNotBlank() }?.let { HeroKicker(it) }
            HeroTitle(hero.title)
            hero.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, fontSize = ThemeSpec.Type.cardTitle, lineHeight = ThemeSpec.Type.cardTitle * 1.35f, color = ThemeSemantics.onMediaMuted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.enterScene(delayMs = ThemeSpec.Motion.STAGGER * 2),
                )
            }
        }
    }
}

/** The LIVE tag: a red capsule with a breathing white dot. */
@Composable
private fun LiveMark() {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ThemeSpec.Motion.PULSE_MS), RepeatMode.Reverse), label = "dot",
    )
    Row(
        Modifier
            .clip(CircleShape)
            .background(ThemeSemantics.live)
            .padding(start = ThemeSpec.Space.sm + ThemeSpec.Space.xs, end = ThemeSpec.Space.md, top = ThemeSpec.Space.xs, bottom = ThemeSpec.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
    ) {
        Box(Modifier.size(ThemeSpec.Space.xs).graphicsLayer { alpha = pulse }.clip(CircleShape).background(ThemeSemantics.onMedia))
        Text(
            "LIVE", fontSize = ThemeSpec.Type.label, fontFamily = ThemeSpec.Font.display, fontWeight = FontWeight.Bold,
            color = ThemeSemantics.onMedia, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1,
        )
    }
}

/** The warmup beat: the start is inside the window, so the copy says so in the live colour. */
@Composable
private fun StartingSoonMark() {
    Text(
        "STARTING SOON", fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold,
        color = ThemeSemantics.live, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1,
    )
}

/** The network's real logo when the catalogue has one, its name in plain text when not. */
@Composable
private fun NetworkMark(name: String) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ChannelLogos.ensureLoaded(ctx) }
    if (ChannelLogos.loaded && ChannelLogos.urlFor(name) != null) {
        NameLogo(name, ThemeSpec.Space.xl, plain = true)
    } else {
        Text(name.uppercase(), fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold, color = ThemeSemantics.onMediaMuted, maxLines = 1)
    }
}

/** One side of the score line: the mark in display italic over a short bar of the team's colour. */
@Composable
private fun TeamMark(abbr: String?, color: Color, logo: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
        if (logo != null) AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(48.dp))
        Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
            Text(
                abbr ?: "?", fontSize = ThemeSpec.Type.titleL, fontFamily = ThemeSpec.Font.display,
                fontWeight = FontWeight.Bold, color = ThemeSemantics.onMedia, maxLines = 1,
            )
            Box(Modifier.width(ThemeSpec.Space.xl * 2).height(ThemeSpec.Space.xs).clip(CircleShape).background(color))
        }
    }
}

/** A live or upcoming game, as the matchup it is: each team's logo at banner scale on its own colour at the
 *  band's right, and the copy at its left — marks and score in mono, the clock and broadcaster beside LIVE, the
 *  venue as a whisper. */
@Composable
private fun GameSlide(event: BigEvent, inset: Dp, ctaFocused: Boolean, pressed: Boolean) {
    val now = rememberTickingNowMs()
    val g = event.game
    val ctx = LocalContext.current
    // A real photograph of the game's teams where ESPN has one, and the viewer's own channel that carries it.
    val photo by produceState(GamePhotos.cached(g), g.idA, g.idB, g.leaguePath) { value = GamePhotos.forGame(g) ?: value }
    val channel by produceState<GameChannelResolver.Match?>(null, event.key) { value = GameChannelFinder.best(ctx, g, event.league) }
    val venue by produceState<VenueWeather.Conditions?>(null, g.location, g.indoor) {
        value = g.location?.takeIf { g.indoor != true }?.let { VenueWeather.load(it) }
    }
    val away = plateColors(g.colorB, g.altColorB, RedCorner).outer
    val home = plateColors(g.colorA, g.altColorA, BlueCorner).outer
    Box(Modifier.fillMaxSize()) {
        SlideGround(Modifier.background(BugBlack)) {
            val shot = photo
            if (shot != null) {
                AsyncImage(model = shot, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Matchup(g.logoB, g.logoA, away, home)
            }
            Box(Modifier.fillMaxSize().heroVeil())
        }
        val watch = if (event.live) (if (channel != null) "Watch Now" else "Open Sports") else "Game options"
        HeroCopy(inset, GameCopyWidth, action = { HeroCta(watch, ctaFocused, pressed) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                if (event.live) {
                    LiveMark()
                    Text(
                        clockLabel(g), fontSize = ThemeSpec.Type.body, fontFamily = ThemeSpec.Font.mono, fontWeight = FontWeight.Bold,
                        color = ThemeSemantics.onMedia, style = TextStyle(fontFeatureSettings = TABULAR), maxLines = 1,
                    )
                    g.network?.let { NetworkMark(it) }
                } else {
                    // Inside the warmup window the league name yields to a louder beat: the game is imminent.
                    val warmup = g.startMs > now && g.startMs - now <= WARMUP_MS
                    if (warmup) StartingSoonMark()
                    // The game's league reads muted, yielding to the ticking countdown beside it.
                    else Text(event.league.uppercase(), fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold, color = ThemeSemantics.onMediaMuted, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1)
                    // Only while it still counts: once the start has passed the copy below carries the time.
                    if (g.startMs > now) {
                        Text(
                            countdownText(g.startMs - now), fontSize = ThemeSpec.Type.body, fontFamily = ThemeSpec.Font.mono,
                            fontWeight = FontWeight.Bold, color = if (warmup) ThemeSemantics.live else ThemeSemantics.onMedia,
                            style = TextStyle(fontFeatureSettings = TABULAR), maxLines = 1,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md)) {
                TeamMark(g.abbrB ?: g.nameB, away, g.logoB)
                if (event.live) {
                    Score(g.scoreB)
                    Text("\u2013", fontSize = ThemeSpec.Type.title, color = ThemeSemantics.onMediaMuted, maxLines = 1)
                    Score(g.scoreA)
                }
                TeamMark(g.abbrA ?: g.nameA, home, g.logoA)
            }
            venueLine(g, venue, now)?.let {
                Text(it, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.onMediaMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A score in the mono face; a corrected score changes without a flourish. */
@Composable
private fun Score(score: String?) {
    RollingText(
        score ?: "", fontSize = ThemeSpec.Type.score, fontFamily = ThemeSpec.Font.mono, fontWeight = FontWeight.Bold,
        color = ThemeSemantics.onMedia, animateDown = false,
    )
}

/** A one-sided event (grand prix, fight card, golf round): the sport, the big title, when it is on and where to watch. */
@Composable
private fun EventSlide(event: MajorEvent, logo: String?, inset: Dp, ctaFocused: Boolean, pressed: Boolean) {
    val now = rememberTickingNowMs()
    val live = event.isLive(now)
    // Inside the warmup window the sport name yields to a louder beat: the event is imminent.
    val warmup = !live && event.startMs > now && event.startMs - now <= WARMUP_MS
    Box(Modifier.fillMaxSize()) {
        SlideGround(Modifier.background(Brush.linearGradient(0f to ThemeSemantics.accentShadow.copy(alpha = 0.35f), 0.6f to BugBlack))) { logo?.let { LeagueCrest(it) } }
        HeroCopy(inset, EventCopyWidth, action = { HeroCta("Open Sports", ctaFocused, pressed) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                if (warmup) StartingSoonMark()
                // The event's sport reads at full strength: there is no countdown beside it at rest to yield to.
                else Text(event.sport.uppercase(), fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold, color = ThemeSemantics.onMedia, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1)
                if (live) LiveMark()
            }
            HeroTitle(event.title)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                if (!live && event.startMs > now) {
                    // Only while it still counts: once the start has passed, whenLabel already says when it is on.
                    // Within the warmup window the ticking clock takes the live colour, so the urgency is visible.
                    Text(
                        countdownText(event.startMs - now), fontSize = ThemeSpec.Type.body, fontFamily = ThemeSpec.Font.mono,
                        fontWeight = FontWeight.Bold,
                        color = if (warmup) ThemeSemantics.live else ThemeSemantics.onMedia,
                        style = TextStyle(fontFeatureSettings = TABULAR), maxLines = 1,
                    )
                }
                Text(whenLabel(event.startMs, live, now), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.onMediaMuted, maxLines = 1)
                event.watchOn().takeIf { it.isNotEmpty() }?.let {
                    Text("Watch on " + it.joinToString(" \u00b7 "), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.onMediaMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One day of the forecast, with today picked out in the live theme accent. */
@Composable
private fun DayCell(sky: Sky, day: String, high: String, low: String, rain: String?, today: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    Column(
        modifier.heightIn(min = DayCellHeight).clip(shape)
            .background(if (today) ThemeSemantics.accent.copy(alpha = 0.24f) else ThemeSemantics.surface1)
            .border(1.dp, if (today) ThemeSemantics.accentBright.copy(alpha = 0.65f) else ThemeSemantics.hairline, shape)
            .padding(vertical = ThemeSpec.Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs, Alignment.CenterVertically),
    ) {
        Text(
            day.uppercase(), fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold,
            fontFamily = ThemeSpec.Font.broadcast, letterSpacing = ThemeSpec.Type.trackCaps,
            color = if (today) ThemeSemantics.accentBright else ThemeSemantics.onMediaMuted, maxLines = 1,
        )
        WeatherIcon(sky, Modifier.size(38.dp))
        Text(high, fontSize = ThemeSpec.Type.titleL, fontFamily = ThemeSpec.Font.mono, fontWeight = FontWeight.Bold, color = ThemeSemantics.onMedia, maxLines = 1)
        Text(low, fontSize = ThemeSpec.Type.meta, fontFamily = ThemeSpec.Font.mono, color = ThemeSemantics.onMediaMuted, maxLines = 1)
        // The rain line keeps its height when there is none, so the cells stay level.
        Text(
            rain?.let { "Rain $it" }.orEmpty().ifEmpty { " " }, fontSize = ThemeSpec.Type.micro,
            fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.Bold,
            color = if (today) ThemeSemantics.accentBright else ThemeSemantics.onMediaMuted, maxLines = 1,
        )
    }
}

/** A slow, accent-coloured atmosphere replaces the fixed weather photograph and follows palette changes live. */
@Composable
private fun OutlookBackground(sky: Sky) {
    val drift by rememberInfiniteTransition(label = "outlookAtmosphere").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "outlookDrift",
    )
    SlideGround(
        Modifier.background(
            Brush.linearGradient(
                listOf(ThemeSemantics.mediaInk, ThemeSemantics.accentShadow, ThemeSemantics.mediaInk),
            ),
        ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val glowCenter = Offset(size.width * (0.70f + drift * 0.10f), size.height * (0.12f + drift * 0.16f))
            val glowRadius = size.minDimension * 1.15f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(ThemeSemantics.accent.copy(alpha = 0.48f), ThemeSemantics.accent.copy(alpha = 0.10f), Color.Transparent),
                    center = glowCenter, radius = glowRadius,
                ),
                center = glowCenter, radius = glowRadius,
            )
            drawRect(
                Brush.horizontalGradient(
                    0f to ThemeSemantics.mediaInk.copy(alpha = 0.96f),
                    0.42f to ThemeSemantics.mediaInk.copy(alpha = 0.72f),
                    1f to Color.Transparent,
                ),
            )
        }
        WeatherIcon(
            sky,
            Modifier.align(Alignment.TopEnd).padding(end = 28.dp, top = 8.dp).size(176.dp).graphicsLayer { alpha = 0.13f },
        )
    }
}

/** The forecast: live conditions on the left and a compact five-day outlook on the right. */
@Composable
private fun OutlookSlide(forecast: Forecast, place: Place, inset: Dp, ctaFocused: Boolean, pressed: Boolean) {
    val today = forecast.days.firstOrNull()
    Box(Modifier.fillMaxSize()) {
        OutlookBackground(forecast.now.sky)
        Column(
            Modifier.align(Alignment.BottomEnd).width(OutlookWidth).padding(end = ThemeSpec.Space.xl, bottom = CopyFoot),
            verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
        ) {
            Text(
                "NEXT ${minOf(OUTLOOK_DAYS, forecast.days.size)} DAYS", fontSize = ThemeSpec.Type.label,
                fontFamily = ThemeSpec.Font.broadcast, fontWeight = FontWeight.Bold, letterSpacing = ThemeSpec.Type.trackCaps,
                color = ThemeSemantics.onMediaMuted, maxLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                forecast.days.take(OUTLOOK_DAYS).forEachIndexed { i, day ->
                    DayCell(
                        Sky.of(day.code), if (i == 0) "Today" else dayName(day.date), WeatherUnits.temp(day.maxC), WeatherUnits.temp(day.minC),
                        day.popMax.takeIf { it >= 20 }?.let { "$it%" }, i == 0, Modifier.weight(1f),
                    )
                }
            }
        }
        HeroCopy(inset, OutlookCopyWidth, action = { HeroCta("Open Weather", ctaFocused, pressed) }) {
            Text(
                "Weather now · ${place.name}".uppercase(), fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold,
                color = ThemeSemantics.accentBright, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md)) {
                WeatherIcon(forecast.now.sky, Modifier.size(52.dp))
                Text(
                    WeatherUnits.temp(forecast.now.tempC), fontSize = ThemeSpec.Type.display, lineHeight = ThemeSpec.Type.display * 1.06f,
                    fontWeight = FontWeight.Bold, fontFamily = ThemeSpec.Font.display, letterSpacing = ThemeSpec.Type.trackHeadline,
                    color = ThemeSemantics.onMedia, maxLines = 1,
                )
            }
            Text(
                listOfNotNull(forecast.now.sky.label, today?.let { "H ${WeatherUnits.temp(it.maxC)}  L ${WeatherUnits.temp(it.minC)}" }).joinToString(" · "),
                fontSize = ThemeSpec.Type.title, color = ThemeSemantics.onMediaMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                forecast.nowcastLine() ?: "Feels like ${WeatherUnits.temp(forecast.now.feelsC)}",
                fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.onMediaMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun dayName(date: String): String =
    runCatching { LocalDate.parse(date).dayOfWeek.getDisplayName(DayNameStyle.SHORT, Locale.getDefault()) }.getOrDefault(date)
