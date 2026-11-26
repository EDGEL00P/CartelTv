package com.carteltv.tv.ui.settings
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.ThemeSemantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carteltv.tv.data.preferences.Profiles
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.ui.theme.*
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.trapFocus

/*
 * WHO'S WATCHING + KIDS VEIL (bedtime/limit
 * enforcement): pickers and gates render over everything in the shell.
 */

/** The avatar circle: seven tiles (six faces + add) at this size still fit a 960dp TV stage in one row. */
private val FACE_SIZE = 84.dp

/** Measure line for the veil's copy, so the explanation wraps to two short lines around the action. */
private val VEIL_WIDTH = 620.dp

private val FaceTileShape = RoundedCornerShape(ThemeSpec.Card.corner)

/**
 * One face tile surface: the house lift and the white focus ring. The profile in use also keeps an accent
 * edge, because selection is identity; while the ring covers that edge, its avatar ring and ACTIVE label
 * still carry it. Lift stays at the compact step: a full row of seven tiles has no room to spare.
 */
@Composable
private fun Modifier.faceSurface(focused: Boolean, pressed: Boolean, active: Boolean = false): Modifier =
    this.focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
        .glassSurface(focused, FaceTileShape, idle = if (active) ThemeSemantics.accent else Color.Transparent)

@Composable
fun ProfilePickerOverlay(onClose: (() -> Unit)? = null) {
    val firstRef = remember { FocusRequester() }
    LaunchedEffect(Unit) { suspendRunCatching { firstRef.awaitFocus() } }
    Box(
        // The dialog fill, not the veil: Home's big title text would otherwise read through the faces.
        Modifier.fillMaxSize().trapFocus().background(ThemeSemantics.surfaceModal),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.profile_whos_watching), fontSize = ThemeSpec.Type.titleL, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.profile_switch_hint), fontSize = ThemeSpec.Type.label,
                color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(ThemeSpec.Space.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.lg)) {
                Profiles.state.value.forEachIndexed { i, p ->
                    FaceTile(p, if (i == 0) firstRef else null)
                }
                if (Profiles.state.value.size < 6) {
                    AddFaceTile()
                }
            }
            if (onClose != null) {
                Spacer(Modifier.height(ThemeSpec.Space.xl))
                // A hint, not a control: never a D-pad stop, so Back stays the only way to stay put.
                Text("Press Back to stay on ${Profiles.active()?.name ?: "the current profile"}",
                    fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary)
            }
        }
    }
    androidx.activity.compose.BackHandler { onClose?.invoke() }
}

@Composable
private fun FaceTile(p: com.carteltv.tv.data.preferences.Profile, ref: FocusRequester?) {
    val active = p.id == Profiles.activeId
    val interaction = remember { MutableInteractionSource() }
    val f by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .then(if (ref != null) Modifier.focusRequester(ref) else Modifier)
            .faceSurface(f, pressed, active)
            .clickableNoIndication(interaction) {
                if (p.pinSha.isBlank()) {
                    Profiles.switch(p.id); Profiles.pickerShown = false
                } else {
                    // vetted entry: PIN gate guards this face
                    PendingProfile.pin = p.id
                    PendingProfile.show = true
                }
            }
            .padding(ThemeSpec.Space.md),
    ) {
        Box(
            Modifier.size(FACE_SIZE).clip(CircleShape)
                .background(if (active) ThemeSemantics.accent.copy(alpha = 0.18f) else ThemeSemantics.surfaceRest)
                .border(2.dp, if (active) ThemeSemantics.accent else ThemeSemantics.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                p.name.trim().take(1).uppercase().ifBlank { "?" },
                fontSize = ThemeSpec.Type.titleL,
                fontWeight = FontWeight.Bold,
                fontFamily = ThemeSpec.Font.broadcast,
                color = if (active) ThemeSemantics.accentBright else ThemeSemantics.textEmphatic,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Text(p.name, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textEmphatic, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(
            when { active -> "ACTIVE"; p.kids -> "RESTRICTED"; else -> "" },
            fontSize = ThemeSpec.Type.label, color = if (active) ThemeSemantics.accent else ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }

    // PIN gate for vetted faces (rendered once via the bus below)
}

/** pending face unlock bus (so the PinGate renders above the picker) */
object PendingProfile {
    var show by mutableStateOf(false)
    var pin by mutableStateOf("")
}

@Composable
fun PendingFaceGate() {
    if (!PendingProfile.show) return
    val id = PendingProfile.pin
    PinGate(
        title = "Profile PIN",
        onOk = { code ->
            if (Profiles.pinOk(id, code)) {
                Profiles.switch(id)
                PendingProfile.show = false
                Profiles.pickerShown = false
                true
            } else false
        },
        onCancel = { PendingProfile.show = false },
    )
}

@Composable
private fun AddFaceTile() {
    val interaction = remember { MutableInteractionSource() }
    val f by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .faceSurface(f, pressed)
            .clickableNoIndication(interaction) {
                val n = Profiles.state.value.size + 1
                Profiles.add("Profile $n", Profiles.FACES[n % Profiles.FACES.size], kids = false)
            }
            .padding(ThemeSpec.Space.md),
    ) {
        Box(
            Modifier.size(FACE_SIZE).clip(CircleShape).border(1.dp, ThemeSemantics.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("+", fontSize = ThemeSpec.Type.display, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.profile_new), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Full-screen restricted operator gate for scheduled hours and daily allocation. */
@Composable
fun KidsVeil(block: Profiles.Block, parentPinArmed: Boolean, onSwitchFaces: () -> Unit) {
    val (head, body) = when (block) {
        Profiles.Block.Bedtime -> "Bedtime" to "It's past bedtime. TV access is paused."
        Profiles.Block.Limit -> "Time is up" to "The daily screen time limit has been reached. A parent can extend the time."
    }
    androidx.activity.compose.BackHandler { }
    Box(
        Modifier.fillMaxSize().trapFocus().background(Brush.verticalGradient(listOf(ThemeSemantics.stage, ThemeSemantics.surfaceRaised))),
        contentAlignment = Alignment.Center,
    ) {
        val cta = remember { FocusRequester() }
        LaunchedEffect(Unit) { suspendRunCatching { cta.awaitFocus() } }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(VEIL_WIDTH)) {
            VeilGlyph(block)
            Spacer(Modifier.height(ThemeSpec.Space.md))
            Text(head, fontSize = ThemeSpec.Type.titleL, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(ThemeSpec.Space.cozy))
            Text(body, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = (ThemeSpec.Type.meta.value * 1.55f).sp)
            Spacer(Modifier.height(ThemeSpec.Space.xl))
            var moreF by remember { mutableStateOf(false) }
            if (block == Profiles.Block.Limit) {
                Text(
                    if (parentPinArmed) "Extend time (parent PIN required)" else "Extend time",
                    fontSize = ThemeSpec.Type.meta, fontWeight = FontWeight.Bold,
                    color = if (moreF) ThemeSemantics.textInverse else ThemeSemantics.accent,
                    modifier = Modifier
                        .focusRequester(cta)
                        .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                        .background(if (moreF) ThemeSemantics.accent else Color.Transparent)
                        .border(1.dp, ThemeSemantics.accent, RoundedCornerShape(ThemeSpec.Card.cornerLg))
                        .onFocusChanged { moreF = it.isFocused }
                        .clickableNoIndication {
                            if (!parentPinArmed) {
                                Profiles.grantMore()
                            } else MoreTimeApproval.ask = true
                        }
                        .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.cozy), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(ThemeSpec.Space.sm))
            }
            var swF by remember { mutableStateOf(false) }
            Text(
                "Switch profile", fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold,
                color = if (swF) ThemeSemantics.textEmphatic else ThemeSemantics.textSecondary,
                modifier = Modifier
                    .then(if (block == Profiles.Block.Bedtime) Modifier.focusRequester(cta) else Modifier)
                    .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                    .background(if (swF) ThemeSemantics.textEmphatic.copy(alpha = 0.14f) else Color.Transparent)
                    .onFocusChanged { swF = it.isFocused }
                    .clickableNoIndication { onSwitchFaces() }
                    .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VeilGlyph(block: Profiles.Block) {
    val icon = when (block) {
        Profiles.Block.Bedtime -> com.carteltv.tv.R.drawable.ph_moon
        Profiles.Block.Limit -> com.carteltv.tv.R.drawable.ph_clock
    }
    PhosphorIcon(icon, ThemeSemantics.accent, 56.dp)
}

/** MORE TIME parent-pin bus */
object MoreTimeApproval { var ask by mutableStateOf(false) }

/** profile-PIN capture bus + gate (SET mode reuses PinGate) */
object ProfilePinLatch { var setId by mutableStateOf<String?>(null) }

@Composable
fun ProfilePinSetGate() {
    val id = ProfilePinLatch.setId ?: return
    val name = Profiles.byId(id)?.name ?: "Profile"
    PinGate(
        title = "Set a PIN for $name",
        onOk = { code ->
            if (code.length in 1..8) {
                Profiles.pinSet(id, code)
                ProfilePinLatch.setId = null
                true
            } else false
        },
        onCancel = { ProfilePinLatch.setId = null },
    )
}
