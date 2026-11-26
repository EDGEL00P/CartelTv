package com.carteltv.tv.ui.nav

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import com.carteltv.tv.R
import com.carteltv.tv.data.preferences.Profiles
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.nav.SectionNav
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.rememberClock
import com.carteltv.tv.ui.theme.PhosphorIcon
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.glassSurface
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState

/** A real masthead action: large enough for the remote, labelled for accessibility, and visibly focused. */
@Composable
internal fun ScreenActionButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.cornerSm)
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = label }
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftIcon, pressed))
            .clip(shape)
            .glassSurface(focused, shape)
            .clickableNoIndication(interaction, onClick),
        contentAlignment = Alignment.Center,
    ) {
        PhosphorIcon(
            icon,
            if (focused) ThemeSemantics.textEmphatic else ThemeSemantics.textSecondary,
            22.dp,
        )
    }
}

/** Search and profile replace the passive status HUD with actions that work from every screen. */
@Composable
internal fun MastheadActions(modifier: Modifier = Modifier) {
    val now = rememberClock(30_000L)
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(
            SimpleDateFormat("HH:mm", locale).format(now),
            fontSize = ThemeSpec.Type.meta,
            fontFamily = ThemeSpec.Font.mono,
            fontWeight = FontWeight.Bold,
            color = ThemeSemantics.textSecondary,
        )
        if (Nav.current != Route.Search) {
            ScreenActionButton(R.drawable.ph_magnifying_glass, "Search") {
                SectionNav.go(AppSection.SEARCH)
            }
        }
        ScreenActionButton(R.drawable.ph_user, "Profiles") {
            if (Profiles.state.value.isNotEmpty()) Profiles.pickerShown = true
            else SectionNav.go(AppSection.SETUP)
        }
    }
}
