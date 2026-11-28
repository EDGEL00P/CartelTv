package com.carteltv.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.ui.theme.PhosphorIcon
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.glassPane

/** A page that is still loading shows the rows it is about to have, never a spinner on its own. */
@Composable
fun PageLoading(modifier: Modifier = Modifier) {
    RowsSkeleton(modifier = modifier)
}

/**
 * A page that could not load, or has nothing to show: one line on what happened and the one way forward.
 * The copy sits on a floating glass card with an accent glyph, so an empty or failed page reads as the same
 * material as the rest of the app; the retry button is where the remote lands, so it is never a dead end.
 */
@Composable
internal fun PageStateContent(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    @DrawableRes icon: Int? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .glassPane(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .padding(ThemeSpec.Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                        .background(ThemeSemantics.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    PhosphorIcon(icon, ThemeSemantics.textInverse, 28.dp)
                }
                Spacer(Modifier.height(ThemeSpec.Space.md))
            }
            Text(
                title,
                fontSize = ThemeSpec.Type.title,
                fontWeight = FontWeight.Bold,
                color = ThemeSemantics.textEmphatic,
                textAlign = TextAlign.Center,
            )
            if (hint != null) {
                Spacer(Modifier.height(ThemeSpec.Space.xs))
                Text(hint, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, textAlign = TextAlign.Center)
            }
            if (retryLabel != null && onRetry != null) {
                Spacer(Modifier.height(ThemeSpec.Space.md))
                com.carteltv.tv.ui.catalog
                    .ActionButton(retryLabel, filled = true, modifier = Modifier.entryFocus(), onClick = onRetry)
            }
        }
    }
}

@Composable
fun PageError(
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String = "Retry",
    onRetry: () -> Unit,
) {
    PageStateContent(title = message, modifier = modifier, retryLabel = retryLabel, onRetry = onRetry)
}
