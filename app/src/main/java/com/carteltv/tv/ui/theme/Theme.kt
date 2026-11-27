package com.carteltv.tv.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.carteltv.tv.R
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay

/*
 * Design system in three tiers: ThemePrimitives holds the raw concept tokens and is referenced only by
 * ThemeSemantics, the semantic roles feature code imports. ThemeSpec adds component and motion constants.
 */
object ThemePrimitives {
    // ink and scrims over media
    val inkBlack = Color(0xFF000000) // dark ground the scrims over media are built from
    val smokedGlass = Color(0x4D000000) // dark glass over media, 30%
    val scrimBand = Color(0x4D000000) // poster caption band anchor

    // neutrals
    val warmAsh = Color(0xFFA1A1A6) // muted text — concept --text-secondary

    // signals — concept semantic tokens
    val ledgerGreen = Color(0xFF46D369) // success / earned — readable status green
    val glacier = Color(0xFF48484A) // cool info lanes — graphite
    val amberLamp = Color(0xFF767676) // tertiary — muted
    val scarlet = Color(0xFFFF3B30) // destructive and error only

    // ── data colours that are not chrome ──
    val mediaInk = Color(0xFF121418) // dark ground under scorebugs, widgets and artwork
    val cornerRed = Color(0xFFC8102E) // red corner for athletes with no brand colour
    val cornerBlue = Color(0xFF1D4ED8) // blue corner

    // ── text + scrim ladder ──
    val scrimDeep = Color(0x73000000) // gradient anchor over artwork
    val scrimChip = Color(0x4D000000) // badge chip over artwork
    val trackWhite = Color(0x33000000) // progress track over artwork

    // ── concept accent set (reserved, used sparingly) ──
    val gold = Color(0xFF48484A) // premium tag — graphite
    val info = Color(0xFF48484A) // info lanes — graphite
    val warning = Color(0xFFFF9500) // warnings — --warning
    val sportsOrange = Color(0xFF48484A) // sports tones — graphite
}

/* ── THE APPEARANCE (Liquid Glass dark) ──
   Light ink on a dark stage; glass is a genuinely translucent dark tint (42–62%) over a
   backdrop-blurred copy of the stage (broadcastSurface / Haze), so colour behind a panel
   frosts and ghosts through instead of vanishing behind charcoal. Legibility comes from
   the blur, not opacity; reduceTransparency restores near-solid surfaces. */
object NeutralPrimitives {
    val stageTop = Color(0xFF080808) // cinematic black with just enough lift to separate chrome
    val stageBottom = Color(0xFF000000) // Netflix-black stage floor
    val ink = Color(0xFFFFFFFF) // primary text — pure white
    val ink2 = Color(0xFFB3B3B3) // secondary text — Netflix's quiet metadata gray
    val ink3 = Color(0xFF808080) // tertiary text — still readable at ten feet
    val hairline = Color(0x38FFFFFF) // glass rim at rest — a real edge, brightest at the top
    val glass = Color(0x6B141414) // resting glass: 42% charcoal over the blurred backdrop
    val glassStrong = Color(0x9E1C1C1C) // selected and raised glass: 62%
    val glassFocus = Color(0x8C1A1A1A) // focused glass: 55%, stepped forward by rim + glow
    val modalVeil = Color(0xB8080808) // modal glass: 72% over blur — dense copy stays readable
    val scrimVeil = Color(0xB3000000) // translucent veil over content behind dialogs
    val numeral = Color(0xFFB3B3B3) // numerals, chrome rules
    val chipOn = Color(0x29FFFFFF) // selected chip fill — quiet white, never red focus
}

object ThemeSemantics {
    private fun glass(base: Color): Color = if (ThemePresets.reduceTransparency) base.copy(alpha = maxOf(base.alpha, 0.96f)) else base

    // Preset hue tints every glass tier, strongest where focus lands, so a theme is visible
    // in the material itself and not only in badges and rings.
    private fun tinted(
        base: Color,
        amount: Float,
    ): Color = lerp(base, accent, amount).copy(alpha = base.alpha)

    private fun focusedGlass(): Color = tinted(NeutralPrimitives.glassFocus, 0.20f)

    // stage
    val stage: Color get() = NeutralPrimitives.stageBottom // every screen's floor
    val stageLift: Color get() = glass(NeutralPrimitives.glassStrong) // raised / pressed stage

    // Glass surfaces: translucent dark over whatever the backdrop draws, so content
    // behind tints them — a smoked window, not an opaque panel.
    val panel: Color get() = glass(tinted(NeutralPrimitives.glass, 0.12f)) // resting glass — cards, rows, chips
    val scrimBlack: Color get() = ThemePrimitives.inkBlack // use .copy(alpha = …) for ad-hoc veils
    val scrim: Color get() = ThemePrimitives.smokedGlass // glass over media
    val scrimTitleBand: Color get() = ThemePrimitives.scrimBand // caption gradient anchor
    val scrimModal: Color get() = NeutralPrimitives.scrimVeil // THE dialog veil
    val hairline: Color get() = NeutralPrimitives.hairline // dividers, idle strokes

    // typography pigments
    val textPrimary: Color get() = NeutralPrimitives.ink
    val textMuted: Color get() = NeutralPrimitives.ink2
    val textInverse: Color get() = ThemePresets.bus.value.onAccent // readable ink on an accent fill
    val textEmphatic: Color get() = NeutralPrimitives.ink // focused, read-aloud moments
    val textSecondary: Color get() = NeutralPrimitives.ink2 // subtitles, idle nav tabs
    val textTertiary: Color get() = NeutralPrimitives.ink3 // counts, captions

    // Ink for text laid directly on artwork or a light scrim, whatever the surface around it is.
    val onMedia: Color = NeutralPrimitives.ink
    val onMediaMuted: Color = NeutralPrimitives.ink2
    val onMediaInk: Color get() = NeutralPrimitives.stageBottom
    val chip: Color get() = panel // idle chip / pill fill
    val chipSelected: Color get() = NeutralPrimitives.chipOn // selected chip fill
    val scrimDeep: Color get() = ThemePrimitives.scrimDeep // bottom-of-tile / top-of-screen anchor
    val scrimChip: Color get() = ThemePrimitives.scrimChip // badge over artwork
    val progressTrack = ThemePrimitives.trackWhite // resume-bar track
    val numeral: Color get() = NeutralPrimitives.numeral // numerals, chrome rules

    // House accents read through ThemePresets.bus so a selection repaints
    // every semantic color reader without changing feature call sites.
    val accent: Color get() = ThemePresets.bus.value.primary // selection, ribbons, brand
    val accentBright: Color get() = ThemePresets.bus.value.glare
    val accentShadow: Color get() = ThemePresets.bus.value.shadow
    val money = ThemePrimitives.ledgerGreen // CLEARED, ready, linked — earned states
    val win = ThemePrimitives.ledgerGreen // a settled result, shown beside the dimmed loser
    val infoCold = ThemePrimitives.glacier // telemetry, cool info lanes
    val lampWarm = ThemePrimitives.amberLamp // muted tertiary cuts
    val gold = ThemePrimitives.gold // premium tag — recommendation ribbons
    val info = ThemePrimitives.info // cool info
    val warning = ThemePrimitives.warning // warnings
    val sports = ThemePrimitives.sportsOrange // live sports urgency

    // signals
    val danger = ThemePrimitives.scarlet // alarms, destructive
    val live: Color get() = accent // LIVE badges and clocks follow the active preset
    val mediaInk = ThemePrimitives.mediaInk // ground under scorebugs, widgets and artwork
    val cornerRed = ThemePrimitives.cornerRed // head-to-head corners
    val cornerBlue = ThemePrimitives.cornerBlue
    val focusRing: Color get() = NeutralPrimitives.ink // crisp focus core — see ThemeSpec.Focus
    val focusGlow: Color get() = accent // the glow that follows it — concept --focus-glow-color

    // ── surface ladder: translucent rest, focused, raised and deep glass ──
    val surface1: Color get() = panel // raised rows / cards over stage
    val surface2: Color get() = glass(tinted(NeutralPrimitives.glassStrong, 0.08f)) // cards on panels, list wells
    val surface3: Color get() = glass(NeutralPrimitives.modalVeil) // modals, sheets — deep glass
    val surface4: Color get() = glass(tinted(NeutralPrimitives.glassStrong, 0.08f)) // floating chrome: tooltips, flyouts
    val surfaceModal: Color get() = glass(NeutralPrimitives.modalVeil)
    val surfaceRest: Color get() = panel
    val surfaceFocus: Color get() = glass(focusedGlass())
    val surfaceSelected: Color get() = glass(tinted(NeutralPrimitives.glassStrong, 0.14f))
    val surfaceRaised: Color get() = glass(tinted(NeutralPrimitives.glassStrong, 0.08f))
    val surfaceFloat: Color get() = glass(NeutralPrimitives.modalVeil)
    val scrimBase: Color get() = NeutralPrimitives.scrimVeil
    val success: Color get() = ThemePrimitives.ledgerGreen
    val softEdge: Color get() = hairline
}

object ThemeSpec {
    /** Corner radii — modern 2026 geometry: soft cards and panels, tight chips. */
    object Card {
        val cornerSm: Dp = 8.dp // small tiles, stream rows
        val corner: Dp = 12.dp // glass cards — soft enough to read as a pane, not a tile
        val cornerLg: Dp = 20.dp // panels, modals and floating glass panes
        val cornerXl: Dp = 0.dp // hero — full-bleed, no radius
        val pill: Dp = 24.dp // status badges, action pills — reads as a capsule at control height
    }

    /** Type scale for a 10-foot screen (the Shield lays out 960dp wide at 1080p). It follows Android TV's own
     *  ramp — a row title where tv-material sets titleLarge, body text at its bodyLarge, nothing under 12sp —
     *  because the concept's px ÷ 2 left the smallest lines at 12px on a TV, too small to read across a room.
     *  [scale] is the one knob that moves the whole hierarchy when 10-foot text needs to grow. Colour always
     *  comes from ThemeSemantics. */
    object Type {
        val scale = 1f
        val display = (36 * scale).sp // hero title — displaySmall
        val titleL = (26 * scale).sp // screen mastheads — headlineMedium
        val title = (20 * scale).sp // row titles, modal heads — titleLarge
        val cardTitle = (18 * scale).sp // card titles — titleMedium
        val body = (20 * scale).sp // primary rows, nav tabs, buttons — bodyLarge
        val cell = (16 * scale).sp // guide grid cell titles — bodyMedium
        val meta = (16 * scale).sp // secondary copy — bodyMedium
        val label = (14 * scale).sp // badges, callouts — labelLarge
        val micro = (12 * scale).sp // metadata, view counts — labelMedium
        val score = (36 * scale).sp // tabular scoreboard numerals — displaySmall

        /** Tight headline setting for display caps; tracked setting for eyebrow/label caps. */
        val trackHeadline = (-0.5).sp
        val trackCaps = 1.5.sp
    }

    /**
     * Faces: Outfit carries headers, plates, labels and
     * body copy, JetBrains Mono carries scores and clocks. Atkinson Hyperlegible backs the
     * accessibility toggle. Variable cuts declare their weight through FontVariation.
     * Source: google/fonts commit 06bfe0d62a98375cb09c942fa18e68bd32cff057, SIL OFL 1.1.
     */
    object Font {
        private val outfit: FontFamily =
            FontFamily(
                androidx.compose.ui.text.font.Font(
                    com.carteltv.tv.R.font.outfit,
                    androidx.compose.ui.text.font.FontWeight.W400,
                    variationSettings = FontVariation.Settings(FontVariation.weight(400)),
                ),
                androidx.compose.ui.text.font.Font(
                    com.carteltv.tv.R.font.outfit,
                    androidx.compose.ui.text.font.FontWeight.W600,
                    variationSettings = FontVariation.Settings(FontVariation.weight(600)),
                ),
                androidx.compose.ui.text.font.Font(
                    com.carteltv.tv.R.font.outfit,
                    androidx.compose.ui.text.font.FontWeight.W700,
                    variationSettings = FontVariation.Settings(FontVariation.weight(700)),
                ),
            )
        private val jetbrains: FontFamily =
            FontFamily(
                androidx.compose.ui.text.font.Font(
                    com.carteltv.tv.R.font.jetbrains_mono,
                    androidx.compose.ui.text.font.FontWeight.W400,
                    variationSettings = FontVariation.Settings(FontVariation.weight(400)),
                ),
                androidx.compose.ui.text.font.Font(
                    com.carteltv.tv.R.font.jetbrains_mono,
                    androidx.compose.ui.text.font.FontWeight.W700,
                    variationSettings = FontVariation.Settings(FontVariation.weight(700)),
                ),
            )

        /** Headers — Outfit, the same geometric sans as the rest of the type, replacing the slab. */
        val display: FontFamily get() = outfit

        /** Plates, shelf heads, badges — concept Outfit. */
        val broadcast: FontFamily get() = outfit

        /** Long-form copy — concept Outfit Regular. */
        val body: FontFamily get() = outfit

        /** Scores, clocks, timestamps — concept JetBrains Mono. */
        val mono: FontFamily get() = jetbrains

        /** The accessibility toggle's reading face — concept Atkinson Hyperlegible. */
        val dyslexia: FontFamily =
            FontFamily(
                androidx.compose.ui.text.font.Font(
                    com.carteltv.tv.R.font.atkinson_hyperlegible_regular,
                    androidx.compose.ui.text.font.FontWeight.W400,
                ),
                androidx.compose.ui.text.font.Font(
                    com.carteltv.tv.R.font.atkinson_hyperlegible_bold,
                    androidx.compose.ui.text.font.FontWeight.W700,
                ),
            )
    }

    /**
     * Overscan-safe margins for the 960x540dp design canvas (Android TV layout guide): 5% of the width and height.
     * dp scales this to 720p, 1080p and 4K panels, so it is the one edge inset for every screen. Text, focus and
     * controls stay inside it; backdrops and rows may still run under it.
     */
    object Safe {
        val horizontal = 48.dp
        val vertical = 27.dp
    }

    /** Concept spacing ladder (4/8/16/24/32px) ÷ 2 for the Shield's density. */
    object Space {
        val xs = 2.dp
        val sm = 4.dp
        val md = 8.dp
        val lg = 12.dp
        val xl = 16.dp
        val margin = xl // page-content inset: pages pad content by this so it lands on the safe margin
        val gutter = xl // gap between cards in a rail, shelf or grid
        val cozy = 6.dp // in-card padding, chip gaps — concept 12px
        val rhythm = 5.dp // chip and row internals — concept 10px
        val rowGap = 6.dp // standard list/card row gap — concept 12px
    }

    /** Glass material constants: backdrop blur, rim and rest elevation. */
    object Glass {
        val rimWidth: Dp = 1.dp // glass border width
        val blurRadius: Dp = 20.dp // frosted-glass backdrop blur (Haze / RenderEffect)
        const val noiseFactor: Float = 0.02f // faint grain so large panes do not band
        val restElevation: Dp = 8.dp // soft drop shadow at rest; focus swaps it for the glow
        val paneElevation: Dp = 18.dp // floating panes: dialogs, player deck, nav folder
        const val rimTopAlpha: Float = 0.38f // specular rim, top edge
        const val rimBottomAlpha: Float = 0.05f // rim where the pane meets the backdrop
    }

    /** Focus constants. A crisp white core carries the indicator; the accent glow
     *  (concept --focus-glow-color) blooms behind the surface. */
    object Focus {
        val ring: Color get() = ThemeSemantics.focusRing
        val glow: Color get() = ThemeSemantics.focusGlow
        val ringWidth: Dp = 2.dp // readable white focus at ten feet
        val idleWidth: Dp = 1.dp // idle hairline
        val glowSm: Dp = 6.dp // restrained elevation, not a neon halo
        val glowLg: Dp = 12.dp
        val lift: Float = 1.06f // clear feedback without colliding with siblings
        val liftHero: Float = 1.02f
        val liftCompact: Float = 1.04f // rows, small tiles — kept tight for density
        val liftPoster: Float = 1.08f // focused art lifts gently, like Netflix's browse rows
        val liftIcon: Float = 1.06f // tiny round targets
        val pressed: Float = 0.97f // OK held down: a subtle press, not a large dip
        val hoverLift: Float = 1.02f // quiet pointer precursor
    }

    /** Motion — concept curves and durations. */
    object Motion {
        // durations, ms
        const val DUR_FAST = 150 // presses, ripples of state — concept exit
        const val DUR_MID = 200 // focus travel, scrims — concept transitions
        const val DUR_SLOW = 300 // scene entrances — concept slide/fade
        const val STAGGER = 100 // per-section entrance delay — concept page load
        const val PULSE_MS = 1500 // ambient loops (live dots) — concept 1.5s pulse

        // curves
        val easeStandard = CubicBezierEasing(0.40f, 0.00f, 0.20f, 1.00f) // concept cubic-bezier(0.4,0,0.2,1)
        val easeEnter = CubicBezierEasing(0.00f, 0.00f, 0.20f, 1.00f) // ease-out — entrances
        val easeExit = CubicBezierEasing(0.40f, 0.00f, 1.00f, 1.00f) // accelerate out
        val easeLinear = LinearEasing

        /** The one focus spring: a little give, one visible settle, like a weight coming to rest. */
        fun focusSpring() = spring<Float>(dampingRatio = 0.74f, stiffness = 320f)

        /** Scene fades, scrim dissolves. */
        fun <T> tweenMid() = tween<T>(DUR_MID, easing = easeStandard)

        fun <T> tweenFast() = tween<T>(DUR_FAST, easing = easeExit)

        fun <T> tweenSlow() = tween<T>(DUR_SLOW, easing = easeEnter)
    }
}

/**
 * The focus executables: every ephemeral D-pad indicator routes through these. Selection (accent)
 * stays at call sites; it is identity, not focus.
 *
 * Ring only — for sites that manage their own transform and glow.
 * Focused = the crisp white ring. Idle keeps whatever hairline the caller asked for.
 */
fun Modifier.focusRing(
    focused: Boolean,
    shape: Shape,
    idle: Color = Color.Transparent,
): Modifier =
    if (focused) {
        border(ThemeSpec.Focus.ringWidth, ThemeSpec.Focus.ring, shape)
    } else {
        border(ThemeSpec.Focus.idleWidth, idle, shape)
    }

/**
 * THE shared focus scale. One spring, one curve, house-wide.
 * Usage: `val sc = focusScaleState(focused)` then `.focusLift(sc)`. The animated value is only read
 * in the draw layer, so the spring does not recompose the tile on every frame.
 */
@Composable
fun focusScaleState(
    focused: Boolean,
    lift: Float = ThemeSpec.Focus.lift,
    pressed: Boolean = false,
): State<Float> {
    val view = androidx.compose.ui.platform.LocalView.current
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        // Skip the first pass so tiles composed already-focused stay silent; honours the system sound-effects setting.
        if (focused && settled &&
            com.carteltv.tv.data.preferences.AppPreferences.focusSounds
        ) {
            view.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_DOWN)
        }
        settled = true
    }
    return if (ThemePresets.systemAnimationsRemoved) {
        // No spring: focus lifts land instantly instead of animating.
        remember(focused, pressed) {
            mutableStateOf(
                if (pressed) {
                    ThemeSpec.Focus.pressed
                } else if (focused) {
                    lift
                } else {
                    1f
                },
            )
        }
    } else {
        animateFloatAsState(
            targetValue =
                if (pressed) {
                    ThemeSpec.Focus.pressed
                } else if (focused) {
                    lift
                } else {
                    1f
                },
            animationSpec = ThemeSpec.Motion.focusSpring(),
            label = "focusScale",
        )
    }
}

/** Applies [focusScaleState] in the draw layer; the State is read there, never in composition. */
fun Modifier.focusLift(scale: State<Float>): Modifier =
    graphicsLayer {
        val s = scale.value
        scaleX = s
        scaleY = s
    }

/** Backdrop-blur source for glass, provided by ScreenScaffold / StagedScreen / the player.
 *  Null outside those hosts (dialog host, screenshot harnesses): glass falls back to its tint. */
val LocalGlassBackdrop = androidx.compose.runtime.staticCompositionLocalOf<HazeState?> { null }

/** Specular rim: bright where light catches the top edge, dissolving down the sides. */
fun glassRimBrush(): Brush =
    Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = ThemeSpec.Glass.rimTopAlpha),
            Color.White.copy(alpha = 0.10f),
            Color.White.copy(alpha = ThemeSpec.Glass.rimBottomAlpha),
        ),
    )

/** The glass fill itself: a Haze backdrop blur where a source is provided, else the tint.
 *  [fill] is the tint over the frost; reduceTransparency forces the near-solid fallback. */
@Composable
internal fun Modifier.glassFill(
    fill: Color,
    blur: Boolean,
): Modifier {
    val hazeState = LocalGlassBackdrop.current
    return if (blur && hazeState != null && !ThemePresets.reduceTransparency) {
        this.hazeEffect(
            state = hazeState,
            style =
                HazeStyle(
                    backgroundColor = ThemeSemantics.stage,
                    tint = HazeTint(fill),
                    blurRadius = ThemeSpec.Glass.blurRadius,
                    noiseFactor = ThemeSpec.Glass.noiseFactor,
                    fallbackTint = HazeTint(fill.copy(alpha = maxOf(fill.alpha, 0.85f))),
                ),
        )
    } else {
        val topAlpha = if (ThemePresets.reduceTransparency) 0.98f else fill.alpha
        val bottomAlpha = if (ThemePresets.reduceTransparency) 0.96f else (fill.alpha * 0.88f).coerceAtLeast(0.18f)
        this.background(Brush.verticalGradient(listOf(fill.copy(alpha = topAlpha), fill.copy(alpha = bottomAlpha))))
    }
}

/**
 * THE card surface: frosted glass over the stage — backdrop blur plus a translucent tint,
 * a specular rim, a soft rest shadow, and on focus the white ring and accent glow bloom.
 * Depth comes from the frost and the rim, never from an opaque repaint.
 */
@Composable
fun Modifier.broadcastSurface(
    focused: Boolean,
    shape: Shape,
    idle: Color = Color.Transparent,
    halo: Boolean = true,
    /** Dense list rows draw a solid ring while focused; cards rely on the bracket and lift. */
    ringWidth: Dp? = null,
    /** Real backdrop blur, for surfaces floating over a registered backdrop source
     *  (see [glassBackdropSource]). In-content cards leave this off: their tint ghosts
     *  whatever is directly behind them, and a Haze pane would only frost the stage. */
    blur: Boolean = false,
): Modifier {
    val fill = if (focused) ThemeSemantics.surfaceFocus else ThemeSemantics.surfaceRest
    return this
        .then(
            when {
                focused && halo -> {
                    Modifier.shadow(
                        ThemeSpec.Focus.glowSm,
                        shape,
                        clip = false,
                        ambientColor = ThemeSemantics.focusGlow.copy(alpha = 0.18f),
                        spotColor = ThemeSemantics.focusGlow.copy(alpha = 0.28f),
                    )
                }

                else -> {
                    Modifier.shadow(
                        ThemeSpec.Glass.restElevation,
                        shape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.45f),
                        spotColor = Color.Black.copy(alpha = 0.45f),
                    )
                }
            },
        ).clip(shape)
        .glassFill(fill, blur)
        .then(
            when {
                focused -> Modifier.border(ThemeSpec.Focus.ringWidth, ThemeSpec.Focus.ring, shape)
                idle != Color.Transparent -> Modifier.border(ThemeSpec.Focus.idleWidth, idle, shape)
                else -> Modifier.border(ThemeSpec.Glass.rimWidth, glassRimBrush(), shape)
            },
        ).then(if (focused && ringWidth != null) Modifier.border(ringWidth, ThemeSpec.Focus.ring, shape) else Modifier)
        .glassLight()
}

/** A floating glass pane: dialogs, sheets, the nav folder and other chrome that floats
 *  over content rather than sitting in a rail. Heavier shadow, modal tint, same frost. */
@Composable
fun Modifier.glassPane(
    shape: Shape,
    fill: Color = ThemeSemantics.surfaceFloat,
    elevation: Dp = ThemeSpec.Glass.paneElevation,
): Modifier =
    this
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black.copy(alpha = 0.55f))
        .clip(shape)
        .glassFill(fill, blur = true)
        .border(ThemeSpec.Glass.rimWidth, glassRimBrush(), shape)
        .glassLight()

/** Neutral glass light: a specular wash along the top edge, drawn after the content. */
fun Modifier.glassLight(): Modifier =
    drawWithContent {
        drawContent()
        val band = 6.dp.toPx()
        drawRect(
            brush =
                Brush.verticalGradient(
                    listOf(ThemeSemantics.textPrimary.copy(alpha = 0.16f), Color.Transparent),
                    startY = 0f,
                    endY = band,
                ),
            size = Size(size.width, band),
        )
    }

/** Kept as the shared call site for every tile and row; the material is the glass tier ladder. */
@Composable
fun Modifier.glassSurface(
    focused: Boolean,
    shape: Shape,
    idle: Color = Color.Transparent,
    halo: Boolean = true,
    ringWidth: Dp? = null,
    blur: Boolean = false,
): Modifier = broadcastSurface(focused, shape, idle, halo, ringWidth, blur)

/** Registers this content as a backdrop that [glassPane] / blurred glass frosts over.
 *  Put it on full-bleed artwork and stage washes — never on the glass itself. No-op
 *  outside a host that provides a backdrop (dialog host, harnesses). */
@Composable
fun Modifier.glassBackdropSource(): Modifier {
    val state = LocalGlassBackdrop.current ?: return this
    return this.hazeSource(state = state)
}

/** Hairline edge for floating glass capsules (the top nav bar); focus lifts it to the focus ring. */
fun Modifier.glassEdge(
    shape: Shape,
    focused: Boolean = false,
): Modifier = border(1.dp, if (focused) ThemeSpec.Focus.ring else ThemeSemantics.hairline, shape)

/* ═══════════════ ENTRANCE CHOREOGRAPHY ═══════════════
   Scenes rise once: fade 15%→100% with a gentle ascent.
   Sections stagger by ThemeSpec.Motion.STAGGER per index.
   Plays once per mount; re-runs naturally on screen re-entry.   */

@Composable
fun Modifier.enterScene(delayMs: Int = 0): Modifier {
    // System "remove animations" (ANIMATOR_DURATION_SCALE == 0): scenes appear settled, no fade or ascent.
    if (ThemePresets.systemAnimationsRemoved) return this
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { } // paint one settled frame first
        if (delayMs > 0) delay(delayMs.toLong())
        entered = true
    }
    val t by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = ThemeSpec.Motion.tweenSlow(),
        label = "enterScene",
    )
    return graphicsLayer {
        alpha = 0.15f + 0.85f * t
        translationY = (1f - t) * 16f
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}

@Composable
fun CartelTheme(content: @Composable () -> Unit) {
    // Every slot is set explicitly from ThemeSemantics, so the constructor's defaults never surface.
    val colorScheme =
        darkColorScheme(
            primary = ThemeSemantics.accent,
            onPrimary = ThemeSemantics.textInverse,
            primaryContainer = ThemeSemantics.accentShadow,
            onPrimaryContainer = ThemeSemantics.textEmphatic,
            background = ThemeSemantics.stage,
            onBackground = ThemeSemantics.textPrimary,
            surface = ThemeSemantics.panel,
            onSurface = ThemeSemantics.textPrimary,
            surfaceVariant = ThemeSemantics.surface2,
            onSurfaceVariant = ThemeSemantics.textSecondary,
            secondary = ThemeSemantics.accentBright,
            onSecondary = ThemeSemantics.textInverse,
            secondaryContainer = ThemeSemantics.surface3,
            onSecondaryContainer = ThemeSemantics.textPrimary,
            tertiary = ThemeSemantics.accentBright,
            onTertiary = ThemeSemantics.textInverse,
            error = ThemeSemantics.danger,
            onError = Color.White,
            border = ThemeSemantics.softEdge,
            borderVariant = ThemeSemantics.hairline,
            scrim = ThemeSemantics.scrimModal,
        )
    // The type hierarchy: Outfit carries display, headline, title, card titles, labels and body;
    // scores and clocks pick JetBrains Mono per call site.
    val base = androidx.tv.material3.Typography()
    val slab = ThemeSpec.Font.display
    val face = ThemeSpec.Font.broadcast
    val typography =
        androidx.tv.material3.Typography(
            displayLarge =
                base.displayLarge.copy(
                    fontFamily = slab,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = ThemeSpec.Type.trackHeadline,
                ),
            displayMedium =
                base.displayMedium.copy(
                    fontFamily = slab,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = ThemeSpec.Type.trackHeadline,
                ),
            displaySmall =
                base.displaySmall.copy(
                    fontFamily = slab,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = ThemeSpec.Type.trackHeadline,
                ),
            headlineLarge =
                base.headlineLarge.copy(
                    fontFamily = slab,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = ThemeSpec.Type.trackHeadline,
                ),
            headlineMedium =
                base.headlineMedium.copy(
                    fontFamily = slab,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = ThemeSpec.Type.trackHeadline,
                ),
            headlineSmall =
                base.headlineSmall.copy(
                    fontFamily = slab,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = ThemeSpec.Type.trackHeadline,
                ),
            titleLarge =
                base.titleLarge.copy(
                    fontFamily = slab,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = ThemeSpec.Type.trackHeadline,
                ),
            titleMedium = base.titleMedium.copy(fontFamily = face, fontWeight = FontWeight.SemiBold),
            titleSmall = base.titleSmall.copy(fontFamily = face, fontWeight = FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.copy(fontFamily = ThemeSpec.Font.body),
            bodyMedium = base.bodyMedium.copy(fontFamily = ThemeSpec.Font.body),
            bodySmall = base.bodySmall.copy(fontFamily = ThemeSpec.Font.body),
            labelLarge = base.labelLarge.copy(fontFamily = face, fontWeight = FontWeight.Bold, letterSpacing = ThemeSpec.Type.trackCaps),
            labelMedium = base.labelMedium.copy(fontFamily = face, fontWeight = FontWeight.Bold, letterSpacing = ThemeSpec.Type.trackCaps),
            labelSmall = base.labelSmall.copy(fontFamily = face, fontWeight = FontWeight.Bold, letterSpacing = ThemeSpec.Type.trackCaps),
        )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content,
    )
}

/** True inside ScreenScaffold, which already draws the backdrop, header and navigation bar. */
val LocalInScreenScaffold = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Stage backdrop: the black floor plus soft fields of the active preset's colour, so glass
 * surfaces have something real and coloured to frost over. Flat black behind glass is
 * invisible — the glows are what the blur smears into each pane. Screens layer artwork and
 * the AmbientStage wash over this; the glows are the floor, not the picture.
 */
@Composable
fun CartelBackdrop(modifier: Modifier = Modifier) {
    if (LocalInScreenScaffold.current) return
    val accent = ThemeSemantics.accent
    val glare = ThemeSemantics.accentBright
    val shadowTone = ThemeSemantics.accentShadow
    Box(
        modifier
            .fillMaxSize()
            .background(ThemeSemantics.stage)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(listOf(accent.copy(alpha = 0.30f), Color.Transparent)),
                    radius = size.width * 0.55f,
                    center = Offset(size.width * 0.88f, size.height * 0.02f),
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(glare.copy(alpha = 0.16f), Color.Transparent)),
                    radius = size.width * 0.48f,
                    center = Offset(size.width * 0.04f, size.height * 0.96f),
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(shadowTone.copy(alpha = 0.35f), Color.Transparent)),
                    radius = size.width * 0.40f,
                    center = Offset(size.width * 0.55f, size.height * 1.05f),
                )
            },
    )
}

val BrassSheen get() =
    Brush.linearGradient(
        listOf(
            ThemeSemantics.accentShadow,
            ThemeSemantics.accent,
            ThemeSemantics.accentBright,
            ThemeSemantics.accent,
            ThemeSemantics.accentShadow,
        ),
    )
val BrassSolid get() = Brush.verticalGradient(listOf(ThemeSemantics.accent, ThemeSemantics.accentShadow))

private enum class GreetingPeriod { MORNING, AFTERNOON, EVENING, NIGHT }

private fun greetingPeriod(hour: Int): GreetingPeriod =
    when (hour) {
        in 5..11 -> GreetingPeriod.MORNING
        in 12..16 -> GreetingPeriod.AFTERNOON
        in 17..21 -> GreetingPeriod.EVENING
        else -> GreetingPeriod.NIGHT
    }

fun cartelGreeting(hour: Int): String =
    when (greetingPeriod(hour)) {
        GreetingPeriod.MORNING -> "Good morning"
        GreetingPeriod.AFTERNOON -> "Good afternoon"
        GreetingPeriod.EVENING -> "Good evening"
        GreetingPeriod.NIGHT -> "Good night"
    }

/** Bundled, ungenerated photography matched to the same local-time period as the greeting. */
@androidx.annotation.DrawableRes
fun cartelGreetingArt(hour: Int): Int =
    when (greetingPeriod(hour)) {
        GreetingPeriod.MORNING -> R.drawable.greeting_morning
        GreetingPeriod.AFTERNOON -> R.drawable.greeting_afternoon
        GreetingPeriod.EVENING -> R.drawable.greeting_evening
        GreetingPeriod.NIGHT -> R.drawable.greeting_night
    }

fun cartelGreetingTagline(hour: Int): String =
    when (greetingPeriod(hour)) {
        GreetingPeriod.MORNING -> "Ease into the day with live news, films and your watchlist."
        GreetingPeriod.AFTERNOON -> "Pick up where you left off, or find something new."
        GreetingPeriod.EVENING -> "Settle in with live TV, films and your watchlist."
        GreetingPeriod.NIGHT -> "Wind down with something from your library."
    }
