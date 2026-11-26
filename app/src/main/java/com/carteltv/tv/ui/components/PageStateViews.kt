package com.carteltv.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Text
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec

/** A page that is still loading shows the rows it is about to have, never a spinner on its own. */
@Composable
fun PageLoading(modifier: Modifier = Modifier) {
    RowsSkeleton(modifier = modifier)
}

/**
 * A page that could not load: one line on what happened, and the one way forward. The button is where the
 * remote lands, so a failed page is never a dead end.
 */
@Composable
fun PageError(message: String, modifier: Modifier = Modifier, retryLabel: String = "Retry", onRetry: () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(vertical = ThemeSpec.Space.margin, horizontal = ThemeSpec.Space.margin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
    ) {
        Text(message, fontSize = ThemeSpec.Type.body, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic, textAlign = TextAlign.Center)
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        com.carteltv.tv.ui.catalog.ActionButton(retryLabel, filled = true, modifier = Modifier.entryFocus(), onClick = onRetry)
    }
}
