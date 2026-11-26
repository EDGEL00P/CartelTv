package com.carteltv.tv.ui.settings

import com.carteltv.tv.data.network.isHttpUrl
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.ui.theme.ThemeSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carteltv.tv.data.provider.Provider
import com.carteltv.tv.data.provider.ProviderEdit
import com.carteltv.tv.data.provider.ProviderType
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.companion.PhoneCompanionServer
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.nav.ScreenScaffold
import kotlinx.coroutines.launch
import com.carteltv.tv.ui.components.awaitFocus

enum class LoginStage { FORM, SIGNING_IN }

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val editing = remember { ProviderEdit.targetId?.let { AppPreferences.provider(it) } }
    val saved = remember { AppPreferences.credentials }
    val typeState = remember { mutableStateOf(editing?.kind ?: ProviderEdit.newType) }
    val type = typeState.value
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var host by remember { mutableStateOf(editing?.host ?: "") }
    var user by remember { mutableStateOf(editing?.user ?: "") }
    var pass by remember { mutableStateOf(editing?.pass ?: "") }
    val ready = when (type) {
        ProviderType.XTREAM -> host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
        ProviderType.M3U -> host.trim().isNotBlank()
        ProviderType.STALKER -> host.isNotBlank() && user.isNotBlank()
    }
    var error by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf(LoginStage.FORM) }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var phoneUrl by remember { mutableStateOf<String?>(null) }

    ScreenScaffold {
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ThemeSpec.Safe.horizontal, vertical = ThemeSpec.Space.lg),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("CartelTV", fontSize = ThemeSpec.Type.display, color = ThemeSemantics.accent, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Text(if (editing != null) "Edit ${editing.name}" else "New provider: ${type.label}", fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.height(ThemeSpec.Space.md))

        Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
            ProviderType.entries.forEach { pt ->
                ActionButton(pt.label, filled = pt == type) {
                    typeState.value = pt
                }
            }
        }
        Spacer(Modifier.height(ThemeSpec.Space.md))

        when (stage) {
            LoginStage.FORM -> {
                CredentialInputField(
                    label = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.login_name),
                    value = name,
                    placeholder = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.login_name_hint),
                    focusRequester = firstFocus,
                ) { name = it }
                Spacer(Modifier.height(ThemeSpec.Space.sm))
                CredentialInputField(
                    label = when (type) {
                        ProviderType.XTREAM -> androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.login_server_url)
                        ProviderType.M3U -> androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.login_playlist_url)
                        ProviderType.STALKER -> androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.login_portal_url)
                    },
                    value = host,
                    placeholder = when (type) {
                        ProviderType.XTREAM -> "http://example.com:8080" // bughunt:ignore kt-cleartext-http
                        ProviderType.M3U -> "http://example.com/playlist.m3u" // bughunt:ignore kt-cleartext-http
                        ProviderType.STALKER -> "http://example.com/c/" // bughunt:ignore kt-cleartext-http
                    },
                ) { host = it }
                if (type == ProviderType.XTREAM) {
                    Spacer(Modifier.height(ThemeSpec.Space.sm))
                    CredentialInputField(
                        label = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.login_username),
                        value = user,
                        placeholder = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.login_username_hint),
                    ) { user = it }
                    Spacer(Modifier.height(ThemeSpec.Space.sm))
                    CredentialInputField(
                        label = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.login_password),
                        value = pass,
                        placeholder = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.login_password_hint),
                        password = true,
                    ) { pass = it }
                }
                if (type == ProviderType.STALKER) {
                    Spacer(Modifier.height(ThemeSpec.Space.sm))
                    CredentialInputField(
                        label = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.login_mac_address),
                        value = user,
                        placeholder = "00:1A:79:12:34:56",
                    ) { user = it }
                }
                Spacer(Modifier.height(ThemeSpec.Space.sm))


                Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                    // Unfilled while the form is incomplete; the hint below names what is missing.
                    ActionButton("Connect", filled = ready) {
                        if (!ready) return@ActionButton
                        stage = LoginStage.SIGNING_IN
                        error = null
                        scope.launch {
                            try {
                                val cleanHost = ProviderSession.normalizeHost(host)
                                val base = editing ?: Provider(Provider.newId(), "", cleanHost, "", "", type.name)
                                val p = base.copy(
                                    name = name.trim().ifBlank { Provider.hostLabel(cleanHost) },
                                    host = cleanHost,
                                    user = if (type == ProviderType.M3U) "" else user.trim().let { if (type == ProviderType.STALKER) it.uppercase() else it },
                                    pass = if (type == ProviderType.XTREAM) pass else "",
                                )
                                val (client, health) = ProviderSession.verify(p)
                                AppPreferences.upsertProvider(p)
                                AppPreferences.setProviderHealth(p.id, health)
                                if (editing == null) AppPreferences.activeProviderId = p.id
                                if (AppPreferences.activeProviderId == p.id) ProviderSession.reload()
                                onLoggedIn()
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                error = "Could not sign in: ${t.message ?: t.javaClass.simpleName}"
                                stage = LoginStage.FORM
                            }
                        }
                    }
                }

                if (!ready) {
                    Spacer(Modifier.height(ThemeSpec.Space.sm))
                    Text(
                        when (type) {
                            ProviderType.XTREAM -> "Enter the server, username and password to connect."
                            ProviderType.M3U -> "Enter the link to your M3U playlist (http or https)."
                            ProviderType.STALKER -> "Enter the portal link and the MAC address registered with your provider."
                        },
                        color = ThemeSemantics.textSecondary, fontSize = ThemeSpec.Type.meta)
                }
                error?.let {
                    Spacer(Modifier.height(ThemeSpec.Space.md))
                    Text(it, color = ThemeSemantics.danger, fontSize = ThemeSpec.Type.meta, maxLines = 3)
                }
            }

            LoginStage.SIGNING_IN -> {
                // The phone panel shares this stage and the house six-row skeleton would neither fit under the
                // masthead nor sit clear of it, so the compact loader stays, in the PageStateViews voice.
                Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.login_signing_in), fontSize = ThemeSpec.Type.body, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }

    PhonePanel(phoneUrl, Modifier.align(Alignment.CenterEnd).padding(end = ThemeSpec.Safe.horizontal))
    }

    LaunchedEffect(Unit) {
        PhoneCompanionServer.start(ctx)
        repeat(8) {
            phoneUrl = PhoneCompanionServer.activeUrl(ctx)
            if (phoneUrl != null) return@LaunchedEffect
            kotlinx.coroutines.delay(1000)
        }
    }

    LaunchedEffect(saved) {
        if (stage == LoginStage.FORM) suspendRunCatching { firstFocus.awaitFocus() }
    }

    LaunchedEffect(stage) {
        if (stage == LoginStage.FORM) {
            kotlinx.coroutines.delay(2000)
            var lastSeen = AppPreferences.credentials
            while (stage == LoginStage.FORM) {
                kotlinx.coroutines.delay(1500)
                // The companion server doesn't have an Xtream endpoint yet, so we check AppPreferences
                val fresh = AppPreferences.credentials
                if (fresh != null && fresh != lastSeen) {
                    lastSeen = fresh
                    PhoneCompanionServer.stop()
                    stage = LoginStage.SIGNING_IN
                    scope.launch {
                        try {
                            ProviderSession.verify(fresh.host, fresh.user, fresh.pass)
                            ProviderSession.reload()
                            onLoggedIn()
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            error = "Could not sign in: ${t.message ?: t.javaClass.simpleName}"
                            stage = LoginStage.FORM
                        }
                    }
                    break
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ProviderEdit.clear()
            PhoneCompanionServer.stop()
        }
    }
    }
}

/** The phone panel is sized to its QR code and caption; wider would crowd the sign-in form it sits beside. */
private val PHONE_PANEL_WIDTH = 300.dp

@Composable
private fun PhonePanel(url: String?, modifier: Modifier) {
    Column(modifier.width(PHONE_PANEL_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.login_type_from_phone), fontSize = ThemeSpec.Type.title, color = ThemeSemantics.textEmphatic, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Text(
            "Scan the QR code with your phone and type there. The selected field on the TV fills in as you type.",
            fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(ThemeSpec.Space.md))
        if (url != null) {
            QrCode(text = url, cellSize = 6.dp)
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Text(url, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.accent, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        } else {
            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.common_connecting), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
