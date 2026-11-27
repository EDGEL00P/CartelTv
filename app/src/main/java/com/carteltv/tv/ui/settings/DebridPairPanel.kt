package com.carteltv.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.data.debrid.DebridAuth
import com.carteltv.tv.data.debrid.TorboxAuth
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Real-Debrid linking without typing a key: the TV shows a short code and a QR to the approval page,
 * the user signs in on their phone, and this panel finishes on its own once Real-Debrid confirms.
 */
@Composable
fun DebridPairPanel(
    onLinked: () -> Unit,
    onCancel: () -> Unit,
) {
    DeviceCodePanel(
        provider = "Real-Debrid",
        begin = {
            DebridAuth.begin().let {
                DeviceCodeView(it.userCode, it.directVerificationUrl.ifBlank { it.verificationUrl }, it.intervalSec, it.expiresIn) to it
            }
        },
        poll = { DebridAuth.poll(it as DebridAuth.DeviceCode) },
        onLinked = onLinked,
        onCancel = onCancel,
    )
}

/** Torbox linking: the same code-and-QR screen, finished by the key Torbox hands back after approval. */
@Composable
fun TorboxPairPanel(
    onLinked: () -> Unit,
    onCancel: () -> Unit,
) {
    DeviceCodePanel(
        provider = "Torbox",
        begin = { TorboxAuth.begin().let { DeviceCodeView(it.userCode, it.verificationUrl, it.intervalSec, it.expiresIn) to it } },
        poll = { TorboxAuth.poll(it as TorboxAuth.DeviceCode) },
        onLinked = onLinked,
        onCancel = onCancel,
    )
}

private data class DeviceCodeView(
    val userCode: String,
    val verificationUrl: String,
    val intervalSec: Int,
    val expiresIn: Int,
)

@Composable
private fun DeviceCodePanel(
    provider: String,
    begin: suspend () -> Pair<DeviceCodeView, Any>,
    poll: suspend (Any) -> Boolean,
    onLinked: () -> Unit,
    onCancel: () -> Unit,
) {
    var device by remember { mutableStateOf<DeviceCodeView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val (d, handle) = begin()
            device = d
            val deadline = System.currentTimeMillis() + d.expiresIn * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(d.intervalSec * 1000L)
                if (poll(handle)) {
                    onLinked()
                    return@LaunchedEffect
                }
            }
            error = "The code expired. Try again."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not link $provider."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
        Text(
            "On your phone, open the link below, sign in and enter this code.",
            fontSize = ThemeSpec.Type.meta,
            color = ThemeSemantics.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xl), verticalAlignment = Alignment.CenterVertically) {
            device?.let { QrCode(text = it.verificationUrl, cellSize = 5.dp) }
            Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                Text(
                    device?.userCode ?: "····",
                    fontSize = ThemeSpec.Type.display,
                    fontWeight = FontWeight.Bold,
                    color = ThemeSemantics.accentBright,
                    fontFamily = ThemeSpec.Font.mono,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                            .background(ThemeSemantics.surfaceRaised)
                            .padding(horizontal = ThemeSpec.Space.xl, vertical = ThemeSpec.Space.md),
                )
                Text(
                    device?.verificationUrl ?: "Contacting $provider…",
                    fontSize = ThemeSpec.Type.label,
                    color = ThemeSemantics.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        error?.let {
            Text(
                it,
                color = ThemeSemantics.danger,
                fontSize = ThemeSpec.Type.meta,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ActionButton("Cancel", filled = false) { onCancel() }
    }
}
