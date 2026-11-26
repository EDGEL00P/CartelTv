package com.carteltv.tv.ui.settings
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.ui.theme.ThemeSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.trakt.TraktAuth
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.glassLight
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.awaitFocus

@Composable
fun TraktPairPanel(onDone: () -> Unit) {
    val pairing = remember {
        TraktPairing(
            PairState(
                when {
                    TraktAuth.client != null -> PairStage.LINKED
                    AppPreferences.traktClientId.isBlank() -> PairStage.NEED_CLIENT_ID
                    else -> PairStage.REQUESTING
                },
            ),
            TraktAccountPort,
        )
    }
    val ui by pairing.state.collectAsStateWithLifecycle()
    val stage = ui.stage
    val device = ui.device
    val user = ui.user
    val err = ui.error
    var clientId by remember { mutableStateOf(AppPreferences.traktClientId) }
    var clientSecret by remember { mutableStateOf(AppPreferences.traktClientSecret) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(stage) {
        when (stage) {
            PairStage.NEED_CLIENT_ID -> suspendRunCatching { focus.awaitFocus() }
            PairStage.REQUESTING -> pairing.request()
            PairStage.AWAITING_CODE -> pairing.awaitConfirmation()
            PairStage.LINKED -> {}
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(ThemeSpec.Space.margin),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (stage) {
            PairStage.NEED_CLIENT_ID -> {
                Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.trakt_authorization), fontSize = ThemeSpec.Type.title, color = ThemeSemantics.textEmphatic, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(ThemeSpec.Space.cozy))
                Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.trakt_client_help), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary)
                Spacer(Modifier.height(ThemeSpec.Space.md))
                CredentialInputField(
                    label = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.trakt_client_id),
                    value = clientId,
                    placeholder = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.trakt_client_id_hint),
                    focusRequester = focus,
                ) { clientId = it }
                Spacer(Modifier.height(ThemeSpec.Space.cozy))
                CredentialInputField(
                    label = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.trakt_client_secret),
                    value = clientSecret,
                    placeholder = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.trakt_client_secret_hint),
                    password = true,
                ) { clientSecret = it }
                Spacer(Modifier.height(ThemeSpec.Space.md))
                // Unfilled until both secrets are typed: the same gate the old disabled state held.
                ActionButton("Authorize", filled = clientId.isNotBlank() && clientSecret.isNotBlank()) {
                    if (clientId.isBlank() || clientSecret.isBlank()) return@ActionButton
                    AppPreferences.traktClientId = clientId
                    AppPreferences.traktClientSecret = clientSecret
                    pairing.authorize()
                }
                err?.let {
                    Spacer(Modifier.height(ThemeSpec.Space.cozy))
                    Text(it, color = ThemeSemantics.danger, fontSize = ThemeSpec.Type.meta, maxLines = 3)
                }
            }

            PairStage.AWAITING_CODE, PairStage.REQUESTING -> {
                Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.trakt_activate), fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(ThemeSpec.Space.lg))
                Text(
                    device?.userCode ?: "····",
                    fontSize = ThemeSpec.Type.display, fontWeight = FontWeight.Bold, color = ThemeSemantics.accentBright,
                    fontFamily = ThemeSpec.Font.mono,
                    modifier = Modifier
                        .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                        .background(ThemeSemantics.surfaceRaised)
                        .glassLight()
                        .border(1.dp, ThemeSemantics.accent.copy(alpha = 0.7f), RoundedCornerShape(ThemeSpec.Card.cornerLg))
                        .padding(horizontal = ThemeSpec.Space.xl, vertical = ThemeSpec.Space.md), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(ThemeSpec.Space.cozy))

                device?.let { d ->
                    val activationUrl = "https://trakt.tv/activate?code=${d.userCode}"
                    QrCode(text = activationUrl, cellSize = 6.dp)
                    Spacer(Modifier.height(ThemeSpec.Space.sm))
                    Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.trakt_scan), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(ThemeSpec.Space.cozy))
                }

                Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.trakt_awaiting), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(ThemeSpec.Space.md))
                ActionButton("Cancel", filled = false) { pairing.cancel(hasToken = AppPreferences.traktToken.isNotBlank()) }
            }

            PairStage.LINKED -> {
                Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.trakt_linked), fontSize = ThemeSpec.Type.title, color = ThemeSemantics.success, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(ThemeSpec.Space.sm))
                Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.trakt_synchronized, user ?: TraktAuth.userName ?: "Linked"), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textPrimary)
                Spacer(Modifier.height(ThemeSpec.Space.md))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                    ActionButton("Done", filled = true) { onDone() }
                    ActionButton("Disconnect", filled = false) {
                        TraktAuth.unpair(); onDone()
                    }
                }
            }
        }
    }
}
