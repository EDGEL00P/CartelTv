package com.carteltv.tv.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.carteltv.tv.ui.components.AutoSizeText
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.glassRow
import com.carteltv.tv.ui.components.glassRowDim
import com.carteltv.tv.ui.components.glassRowText
import com.carteltv.tv.ui.theme.PhosphorIcon
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec

/* Shared settings row primitives. Leaf composables used by every settings page; kept internal so the
   page files in this package compose them without re-declaring. */

@Composable
internal fun CatRow(
    ico: SettingsIcon,
    title: String,
    desc: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    status: String = "",
    onFocusGained: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focusHere by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lit = focused || focusHere
    val tile by animateColorAsState(
        if (lit) ThemeSemantics.textEmphatic.copy(alpha = 0.16f) else ThemeSemantics.accent.copy(alpha = 0.18f),
        tween(ThemeSpec.Motion.DUR_FAST), label = "catTile",
    )
    Row(
        Modifier.padding(vertical = 3.dp)
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .glassRow(lit, pressed)
            .then(modifier)
            .onFocusChanged {
                focusHere = it.isFocused
                if (it.isFocused) onFocusGained?.invoke()
            }
            .clickableNoIndication(interaction) { onClick() }
            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)).background(tile),
            contentAlignment = Alignment.Center,
        ) { Glyph(ico, tint = if (lit) glassRowText(lit) else ThemeSemantics.accent, size = 28.dp) }
        Spacer(Modifier.width(ThemeSpec.Space.md))
        Column(Modifier.weight(1f)) {
            AutoSizeText(title, fontSize = ThemeSpec.Type.body, fontWeight = FontWeight.SemiBold, color = glassRowText(lit), minScale = 0.8f)
            if (desc.isNotBlank()) Text(desc, fontSize = ThemeSpec.Type.meta, color = glassRowDim(lit), lineHeight = (ThemeSpec.Type.meta.value * 1.4f).sp, modifier = Modifier.padding(top = 2.dp),
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (status.isNotBlank()) StatusPill(status, lit)
        Chevron(glassRowDim(lit))
    }
}

/** Current value as a rounded chip, so a row's state reads at a glance from the couch. */
@Composable
internal fun StatusPill(text: String, lit: Boolean) {
    Text(
        text, fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Medium, maxLines = 1,
        color = glassRowText(lit),
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = ThemeSpec.Space.cozy).widthIn(max = 220.dp)
            .clip(CircleShape).background(glassRowText(lit).copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** Trailing "opens something" affordance. */
@Composable
internal fun Chevron(tint: Color) {
    PhosphorIcon(
        com.carteltv.tv.R.drawable.ph_caret_right,
        tint,
        16.dp,
        Modifier.padding(start = ThemeSpec.Space.cozy),
    )
}

/** On/off switch drawn for a [glassRow]: the state reads from position as well as colour. */
@Composable
internal fun SwitchPill(on: Boolean, focused: Boolean) {
    val track by animateColorAsState(
        when {
            on -> ThemeSemantics.accent
            focused -> ThemeSemantics.textEmphatic.copy(alpha = 0.42f)
            else -> ThemeSemantics.textEmphatic.copy(alpha = 0.28f)
        },
        tween(ThemeSpec.Motion.DUR_FAST), label = "swTrack",
    )
    val thumb = ThemeSemantics.textEmphatic
    val x by animateDpAsState(if (on) 28.dp else 3.dp, tween(ThemeSpec.Motion.DUR_FAST), label = "swThumb")
    Box(Modifier.size(58.dp, 32.dp).clip(CircleShape).background(track)) {
        Box(Modifier.offset(x = x, y = 3.dp).size(26.dp).clip(CircleShape).background(thumb))
    }
}

/** Radio indicator: ring, with a filled dot when selected. */
@Composable
internal fun RadioDot(selected: Boolean, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        drawCircle(tint, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(width = stroke))
        if (selected) drawCircle(tint, radius = size.minDimension / 2f - 5.dp.toPx())
    }
}

/** Leaf action / value row inside a page. */
@Composable
internal fun ValueRow(
    title: String,
    desc: String,
    value: String,
    focus: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fm = if (focus != null) Modifier.focusRequester(focus) else Modifier
    Row(
        Modifier.padding(vertical = 3.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .glassRow(f, pressed)
            .then(fm)
            .onFocusChanged { f = it.isFocused }
            .clickableNoIndication(interaction) { onClick() }
            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AutoSizeText(title, fontSize = ThemeSpec.Type.body, fontWeight = FontWeight.Medium, color = glassRowText(f), minScale = 0.8f)
            if (desc.isNotBlank()) Text(desc, fontSize = ThemeSpec.Type.meta, color = glassRowDim(f), lineHeight = (ThemeSpec.Type.meta.value * 1.4f).sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (value.isNotBlank()) {
            StatusPill(value, f)
            Chevron(glassRowDim(f))
        }
    }
}

@Composable
internal fun PlainRow(title: String, desc: String, focus: FocusRequester?, onClick: (() -> Unit)? = null) {
    if (onClick != null) ValueRow(title, desc, "", focus) { onClick() } else ValueRow(title, desc, "", focus) { }
}

/** Radio row — selected row carries the check in the glyph slot, Shield-style. */
@Composable
internal fun ChoiceRow(
    title: String,
    selected: Boolean,
    focus: FocusRequester?,
    onClick: () -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fm = if (focus != null) Modifier.focusRequester(focus) else Modifier
    Row(
        Modifier.padding(vertical = 3.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .glassRow(f, pressed)
            .then(fm)
            .onFocusChanged { f = it.isFocused }
            .clickableNoIndication(interaction) { onClick() }
            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected, if (selected) glassRowText(f) else glassRowDim(f))
        Spacer(Modifier.width(ThemeSpec.Space.md))
        Text(title, fontSize = ThemeSpec.Type.body, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) glassRowText(f) else glassRowDim(f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Boolean row — current state rides at the right, exactly like the toggle prefs. */
@Composable
internal fun ToggleRow(
    title: String,
    desc: String?,
    value: Boolean,
    focus: FocusRequester?,
    onClick: () -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fm = if (focus != null) Modifier.focusRequester(focus) else Modifier
    Row(
        Modifier.padding(vertical = 3.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .glassRow(f, pressed)
            .then(fm)
            .onFocusChanged { f = it.isFocused }
            .clickableNoIndication(interaction) { onClick() }
            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = ThemeSpec.Type.body, fontWeight = FontWeight.Medium, color = glassRowText(f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            desc?.let {
                Text(it, fontSize = ThemeSpec.Type.meta, color = glassRowDim(f), lineHeight = (ThemeSpec.Type.meta.value * 1.4f).sp,
                    modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(ThemeSpec.Space.md))
        Text(if (value) "On" else "Off", fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Medium, color = glassRowDim(f))
        Spacer(Modifier.width(ThemeSpec.Space.cozy))
        SwitchPill(value, f)
    }
}

@Composable
internal fun Section(label: String) {
    // A quiet accent label with a hairline rule: groups the rows without a heading competing with the masthead.
    Row(
        Modifier.fillMaxWidth().padding(start = ThemeSpec.Space.md, end = ThemeSpec.Space.md, top = ThemeSpec.Space.lg, bottom = ThemeSpec.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold, color = ThemeSemantics.accent,
            letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1)
        Spacer(Modifier.width(ThemeSpec.Space.cozy))
        Box(Modifier.weight(1f).height(1.dp).background(ThemeSemantics.hairline))
    }
}

@Composable
internal fun Caption(text: String) {
    Text(text, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary,
        modifier = Modifier.padding(start = ThemeSpec.Space.md, top = ThemeSpec.Space.sm, bottom = ThemeSpec.Space.xs))
}

/** Sub-page opener: a Back chip naming where Back goes, in place of a plain caption. */
@Composable
internal fun BackHint(parent: String) {
    Row(
        Modifier.padding(start = ThemeSpec.Space.md, top = ThemeSpec.Space.xs, bottom = ThemeSpec.Space.sm)
            .clip(CircleShape).background(ThemeSemantics.textEmphatic.copy(alpha = 0.10f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("BACK", fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic, letterSpacing = ThemeSpec.Type.trackCaps)
        Spacer(Modifier.width(10.dp))
        Text(parent, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1)
    }
}

@Composable
internal fun Result(text: String) {
    Text(text, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textPrimary, lineHeight = (ThemeSpec.Type.label.value * 1.45f).sp,
        modifier = Modifier.padding(start = ThemeSpec.Space.md, top = ThemeSpec.Space.xs, bottom = ThemeSpec.Space.xs))
}