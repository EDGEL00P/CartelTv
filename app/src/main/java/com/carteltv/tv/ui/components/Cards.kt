package com.carteltv.tv.ui.components
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.theme.*
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Quiet pointer precursor to D-pad focus. The animated value is read only in the draw layer. */
private fun Modifier.pointerHoverLift(
    source: MutableInteractionSource,
    lift: Float,
): Modifier =
    composed {
        val hovered by source.collectIsHoveredAsState()
        val focused by source.collectIsFocusedAsState()
        val scale =
            animateFloatAsState(
                targetValue = if (hovered && !focused) lift else 1f,
                animationSpec = ThemeSpec.Motion.focusSpring(),
                label = "pointerHoverLift",
            )
        graphicsLayer {
            val s = scale.value
            scaleX = s
            scaleY = s
        }
    }

/** House clickable: no touch ripple, a hand cursor and a restrained hover lift for pointer users. */
fun Modifier.clickableTv(
    interactionSource: MutableInteractionSource? = null,
    hoverEffect: Boolean = true,
    hoverLift: Float = ThemeSpec.Focus.liftHero,
    enabled: Boolean = true,
    showHand: Boolean = true,
    onClick: () -> Unit,
): Modifier =
    composed {
        val source = interactionSource ?: remember { MutableInteractionSource() }
        this
            .then(if (hoverEffect) Modifier.pointerHoverLift(source, hoverLift) else Modifier)
            .then(if (enabled && showHand) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
    }

/** Backwards-compatible name for the house clickable; touch feedback stays suppressed. */
fun Modifier.clickableNoIndication(
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = clickableTv(interactionSource = interactionSource, onClick = onClick)

/** Long-press cards use the same hover language as ordinary controls. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableNoIndication(
    interactionSource: MutableInteractionSource? = null,
    hoverLift: Float = ThemeSpec.Focus.liftHero,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier =
    composed {
        val source = interactionSource ?: remember { MutableInteractionSource() }
        this
            .pointerHoverLift(source, hoverLift)
            .pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                onClickLabel = onClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
    }

/** Shared clock ticker used across Home, Dock, Widgets, and SleepTimer. */
@Composable
fun rememberClock(periodMs: Long = 10_000L): Date {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = Date()
            // Land on the period's boundary, so the minute turns when the clock does, not up to a period late.
            delay(if (periodMs > 0) periodMs - (System.currentTimeMillis() % periodMs) else periodMs)
        }
    }
    return now
}

/** Shared time-of-day formatting. ThreadLocal formatters avoid per-call allocation and are thread-safe. */
object ClockFormat {
    private val time24 = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val time12 = ThreadLocal.withInitial { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    private val hour24 = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val hour12 = ThreadLocal.withInitial { SimpleDateFormat("h a", Locale.getDefault()) }
    private val dayTime24 = ThreadLocal.withInitial { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    private val dayTime12 = ThreadLocal.withInitial { SimpleDateFormat("EEE h:mm a", Locale.getDefault()) }
    private val mediumDateFormat = ThreadLocal.withInitial { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }

    fun time(
        ms: Long,
        clock24: Boolean,
    ): String = (if (clock24) time24.get() else time12.get()).format(Date(ms))

    /** Hour-only label — "15:00" / "3 PM" — for a column of hours. */
    fun hour(
        ms: Long,
        clock24: Boolean,
    ): String = (if (clock24) hour24.get() else hour12.get()).format(Date(ms))

    fun dayTime(
        ms: Long,
        clock24: Boolean,
    ): String = (if (clock24) dayTime24.get() else dayTime12.get()).format(Date(ms))

    fun dateMedium(ms: Long): String = mediumDateFormat.get().format(Date(ms))
}

/** What a focused card tells the screen above it (e.g. the Home hero). */
data class TitleFocus(
    val title: String,
    val art: String?,
    val subtitle: String?,
    val logos: List<String> = emptyList(),
    val meta: String? = null,
    /** "league:game name" for a game card, so the hero can show that game's scorebug. */
    val gameKey: String? = null,
)

/** Provided by screens that want to follow card focus; null elsewhere. */
val LocalTitleFocus = staticCompositionLocalOf<((TitleFocus?) -> Unit)?> { null }

/**
 * Card artwork with a quiet ground while loading, a crossfade on arrival, and a readable title
 * fallback when art is missing or the source fails, so no tile is ever a blank rectangle.
 * Fills its parent; call from a clipped Box.
 */
@Composable
fun CardArtwork(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(ThemeSemantics.mediaInk, ThemeSemantics.scrimBase.copy(alpha = 0.85f))),
            ),
        )
        if (url.isNullOrBlank() || failed) {
            Box(Modifier.fillMaxSize().padding(ThemeSpec.Space.xl), contentAlignment = Alignment.Center) {
                Text(
                    title,
                    fontSize = ThemeSpec.Type.cardTitle,
                    fontFamily = ThemeSpec.Font.broadcast,
                    fontWeight = FontWeight.SemiBold,
                    color = ThemeSemantics.onMediaMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(url)
                        .crossfade(220)
                        .build(),
                contentDescription = title,
                contentScale = contentScale,
                onState = { state -> failed = state is AsyncImagePainter.State.Error },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The one title tile used by every row and grid. Artwork only at rest; on focus it lifts, takes a
 * white ring and reveals title + subtitle. `progress` draws a resume bar, `badge` a callout chip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    width: Dp,
    height: Dp,
    imageUrl: String?,
    title: String,
    subtitle: String? = null,
    progress: Float = 0f,
    badge: String? = null,
    modifier: Modifier = Modifier,
    captionOnFocus: Boolean = true,
    /** False where the caller writes the title beneath the tile, so it is not said twice. */
    titled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    val reportFocus = LocalTitleFocus.current
    if (reportFocus != null && focused) {
        DisposableEffect(title, imageUrl, subtitle) {
            reportFocus(TitleFocus(title, imageUrl, subtitle))
            onDispose { reportFocus(null) }
        }
    }
    val clickMod =
        if (onLongClick != null) {
            Modifier.combinedClickableNoIndication(
                interactionSource = interaction,
                onLongClick = onLongClick,
                onClick = onClick,
            )
        } else {
            Modifier.clickableNoIndication(interaction, onClick)
        }

    Box(
        modifier =
            modifier
                .width(width)
                .height(height)
                .cardSurface(focused, pressed = pressed)
                .focusZLift(focused)
                .then(clickMod),
    ) {
        CardArtwork(imageUrl, title, Modifier.fillMaxSize())
        badge?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                fontSize = ThemeSpec.Type.label,
                fontWeight = FontWeight.SemiBold,
                fontFamily = ThemeSpec.Font.broadcast,
                color = onAccent(ThemeSemantics.accent),
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(ThemeSpec.Space.sm)
                        .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                        .background(ThemeSemantics.accent)
                        .padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.xs),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        // Titles are visible at rest, Kayo-style: the metadata line is the focus-only reveal. A tile whose caller
        // writes the title beneath it shows art alone.
        if (titled) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, ThemeSemantics.scrimDeep)))
                    .padding(
                        start = ThemeSpec.Space.sm,
                        end = ThemeSpec.Space.sm,
                        top = ThemeSpec.Space.lg,
                        bottom =
                            if (progress >
                                0f
                            ) {
                                ThemeSpec.Space.sm
                            } else {
                                ThemeSpec.Space.sm
                            },
                    ),
            ) {
                Text(
                    title,
                    fontSize = ThemeSpec.Type.meta,
                    color = ThemeSemantics.onMedia,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (focused && captionOnFocus) {
                    subtitle?.let {
                        Text(
                            it,
                            fontSize = ThemeSpec.Type.label,
                            color = ThemeSemantics.onMediaMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(ThemeSemantics.progressTrack),
            ) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(ThemeSemantics.accent))
            }
        }
    }
}

/**
 * The boxed classic catalog tile, after Android TV's classic card: one framed box whose head is
 * the 16:9 artwork and whose foot is a raised content block carrying title and subtitle inside
 * the frame. The caption never floats free of the box, so a rail reads as a row of boxes; on
 * focus the whole box lifts, rings and rises above its siblings, and the hero mirrors it
 * through the same [TitleFocus] report [PosterCard] makes.
 */
@Composable
fun GridPoster(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val reportFocus = LocalTitleFocus.current
    if (reportFocus != null && focused) {
        DisposableEffect(title, imageUrl, subtitle) {
            reportFocus(TitleFocus(title, imageUrl, subtitle))
            onDispose { reportFocus(null) }
        }
    }
    Box(
        modifier
            .focusZLift(focused)
            .cardSurface(focused, lift = ThemeSpec.Focus.liftPoster, pressed = pressed)
            .clickableNoIndication(interaction, onClick),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                CardArtwork(imageUrl, title, Modifier.fillMaxSize())
                badge?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontSize = ThemeSpec.Type.label,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = ThemeSpec.Font.broadcast,
                        color = onAccent(ThemeSemantics.accent),
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(ThemeSpec.Space.sm)
                                .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                                .background(ThemeSemantics.accent)
                                .padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.xs),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            // The classic content block: a raised glass plate inside the frame, in place of a scrim caption.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(ThemeSemantics.surface2)
                    .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.cozy),
                verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
            ) {
                Text(
                    title,
                    fontSize = ThemeSpec.Type.cardTitle,
                    fontFamily = ThemeSpec.Font.broadcast,
                    fontWeight = FontWeight.SemiBold,
                    color = if (focused) ThemeSemantics.textEmphatic else ThemeSemantics.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontSize = ThemeSpec.Type.label,
                        fontFamily = ThemeSpec.Font.body,
                        color = ThemeSemantics.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Shared filter/category chip used across Search, Scores, Sections, Detail, and Debrid; see [Chip].
 *  [onClick] is last so call sites can use trailing-lambda syntax; [compact] and [icon] stay named-arg only.
 *  [icon] is a real vector drawable (Phosphor), tinted to the chip's text so a selected chip stays legible. */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    compact: Boolean = false,
    @DrawableRes icon: Int? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = Chip(
    label,
    selected,
    modifier = modifier,
    compact = compact,
    leading =
        icon?.let { id ->
            {
                PhosphorIcon(
                    id,
                    if (selected) ThemeSemantics.textInverse else ThemeSemantics.textEmphatic,
                    if (compact) 14.dp else 16.dp,
                )
            }
        },
    onClick = onClick,
)

/**
 * The Netflix browse-row poster: portrait 2:3 art with the title baked into the artwork, so the
 * tile is art alone at rest. Focus is the drama — the poster looms over its row at
 * [ThemeSpec.Focus.liftPoster] — and the caption reveals beneath the focused tile only, as a
 * bold title over a metadata line. Resume progress and a badge ride the artwork as always.
 */
@Composable
fun PosterTile(
    imageUrl: String?,
    title: String,
    meta: String? = null,
    modifier: Modifier = Modifier,
    width: Dp = 160.dp,
    progress: Float = 0f,
    badge: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val reportFocus = LocalTitleFocus.current
    if (reportFocus != null && focused) {
        DisposableEffect(title, imageUrl, meta) {
            reportFocus(TitleFocus(title, imageUrl, meta))
            onDispose { reportFocus(null) }
        }
    }
    Column(modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .focusZLift(focused)
                .cardSurface(focused, lift = ThemeSpec.Focus.liftPoster, pressed = pressed)
                .then(
                    // A tile with a long-press (Continue watching's remove) keeps the same surface, lift and ring.
                    if (onLongClick != null) {
                        Modifier.combinedClickableNoIndication(
                            interactionSource = interaction,
                            onLongClick = onLongClick,
                            onClick = onClick,
                        )
                    } else {
                        Modifier.clickableNoIndication(interaction, onClick)
                    },
                ),
        ) {
            CardArtwork(imageUrl, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            badge?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontSize = ThemeSpec.Type.label,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = ThemeSpec.Font.broadcast,
                    color = onAccent(ThemeSemantics.accent),
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(ThemeSpec.Space.sm)
                            .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                            .background(ThemeSemantics.accent)
                            .padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.xs),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(ThemeSemantics.progressTrack),
                ) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(ThemeSemantics.accent))
                }
            }
        }
        // The reveal: Netflix's focused-card caption. Only the focused tile names itself, so a
        // row at rest is pure poster art.
        androidx.compose.animation.AnimatedVisibility(
            visible = focused,
            enter =
                androidx.compose.animation.expandVertically(ThemeSpec.Motion.tweenMid()) +
                    androidx.compose.animation.fadeIn(ThemeSpec.Motion.tweenMid()),
            exit =
                androidx.compose.animation.shrinkVertically(ThemeSpec.Motion.tweenFast()) +
                    androidx.compose.animation.fadeOut(ThemeSpec.Motion.tweenFast()),
        ) {
            Column(
                Modifier.padding(top = ThemeSpec.Space.cozy),
                verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
            ) {
                Text(
                    title,
                    fontSize = ThemeSpec.Type.cardTitle,
                    fontFamily = ThemeSpec.Font.broadcast,
                    fontWeight = FontWeight.Bold,
                    color = ThemeSemantics.textEmphatic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                meta?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontSize = ThemeSpec.Type.label,
                        fontFamily = ThemeSpec.Font.broadcast,
                        color = ThemeSemantics.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Provider names arrive as "[EN] DISNEY": drop the bracket tag and sentence-case the shouting. */
internal fun tidyRailTitle(raw: String): String {
    val t =
        raw
            .replace(LEADING_BRACKET_TAG, "")
            .replace(WHITESPACE_RUN, " ")
            .trim()
            .ifEmpty { raw }
    if (t.any { it.isLowerCase() }) return t
    return t.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}

private val LEADING_BRACKET_TAG = Regex("^\\s*\\[[^\\]]*\\]\\s*")
private val WHITESPACE_RUN = Regex("\\s+")
