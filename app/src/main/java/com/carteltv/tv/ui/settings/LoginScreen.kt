package com.carteltv.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.companion.PhoneCompanionServer
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.provider.Provider
import com.carteltv.tv.data.provider.ProviderEdit
import com.carteltv.tv.data.provider.ProviderType
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.nav.ScreenScaffold
import com.carteltv.tv.ui.theme.PhosphorIcon
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.enterScene
import com.carteltv.tv.ui.theme.glassPane
import kotlinx.coroutines.launch

enum class LoginStage { FORM, SIGNING_IN }

/** The sign-in form is a single reading column; this keeps the brand, fields and CTA on one optical axis. */
private val FORM_WIDTH = 560.dp

// Example endpoints shown as field hints. Kept as one-line constants so the bughunt ignore stays on
// the same line as the literal (a formatter reflows inline comments off a wrapped when-branch otherwise).
private const val HINT_XTREAM = "http://example.com:8080" // bughunt:ignore kt-cleartext-http
private const val HINT_M3U = "http://example.com/playlist.m3u" // bughunt:ignore kt-cleartext-http
private const val HINT_STALKER = "http://example.com/c/" // bughunt:ignore kt-cleartext-http

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
    val ready =
        when (type) {
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
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = ThemeSpec.Safe.horizontal, vertical = ThemeSpec.Safe.vertical),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xl, Alignment.CenterHorizontally),
            ) {
                LoginFormCard(
                    modifier = Modifier.enterScene(),
                    editingName = editing?.name,
                    type = type,
                    ready = ready,
                    stage = stage,
                    error = error,
                    name = name,
                    host = host,
                    user = user,
                    pass = pass,
                    firstFocus = firstFocus,
                    onType = { typeState.value = it },
                    onName = { name = it },
                    onHost = { host = it },
                    onUser = { user = it },
                    onPass = { pass = it },
                    onConnect = {
                        if (!ready) return@LoginFormCard
                        stage = LoginStage.SIGNING_IN
                        error = null
                        scope.launch {
                            try {
                                val cleanHost = ProviderSession.normalizeHost(host)
                                val base = editing ?: Provider(Provider.newId(), "", cleanHost, "", "", type.name)
                                val p =
                                    base.copy(
                                        name = name.trim().ifBlank { Provider.hostLabel(cleanHost) },
                                        host = cleanHost,
                                        user =
                                            if (type ==
                                                ProviderType.M3U
                                            ) {
                                                ""
                                            } else {
                                                user.trim().let {
                                                    if (type ==
                                                        ProviderType.STALKER
                                                    ) {
                                                        it.uppercase()
                                                    } else {
                                                        it
                                                    }
                                                }
                                            },
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
                    },
                )
                PhonePanel(phoneUrl, Modifier.enterScene(delayMs = ThemeSpec.Motion.STAGGER))
            }
        }
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

/**
 * The sign-in form as a single floating glass pane: a brand lockup, the provider-type segmented
 * control, the credential fields and one accent call to action. The whole column scrolls inside the
 * pane so a four-field Xtream form stays reachable on a 540dp stage without crowding the masthead.
 */
@Composable
private fun LoginFormCard(
    modifier: Modifier = Modifier,
    editingName: String?,
    type: ProviderType,
    ready: Boolean,
    stage: LoginStage,
    error: String?,
    name: String,
    host: String,
    user: String,
    pass: String,
    firstFocus: FocusRequester,
    onType: (ProviderType) -> Unit,
    onName: (String) -> Unit,
    onHost: (String) -> Unit,
    onUser: (String) -> Unit,
    onPass: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val shape = RoundedCornerShape(ThemeSpec.Card.cornerLg)
    val scroll = rememberScrollState()
    Column(
        modifier
            .width(FORM_WIDTH)
            .fillMaxHeight()
            .glassPane(shape)
            .verticalScroll(scroll)
            .padding(ThemeSpec.Space.xl),
    ) {
        BrandLockup()
        Spacer(Modifier.height(ThemeSpec.Space.lg))

        Text(
            if (editingName != null) "Edit $editingName" else "Add a provider",
            fontSize = ThemeSpec.Type.titleL,
            fontFamily = ThemeSpec.Font.display,
            fontWeight = FontWeight.Bold,
            letterSpacing = ThemeSpec.Type.trackHeadline,
            color = ThemeSemantics.textEmphatic,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(ThemeSpec.Space.xs))
        Text(
            "Connect your live TV, films and sports source to get started.",
            fontSize = ThemeSpec.Type.meta,
            color = ThemeSemantics.textSecondary,
        )
        Spacer(Modifier.height(ThemeSpec.Space.lg))

        // Provider type as a segmented control: the filled pill is the active source.
        Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
            ProviderType.entries.forEach { pt ->
                ActionButton(pt.label, filled = pt == type) { onType(pt) }
            }
        }
        Spacer(Modifier.height(ThemeSpec.Space.lg))

        when (stage) {
            LoginStage.FORM -> {
                CredentialInputField(
                    label =
                        androidx.compose.ui.res
                            .stringResource(com.carteltv.tv.R.string.login_name),
                    value = name,
                    placeholder =
                        androidx.compose.ui.res
                            .stringResource(com.carteltv.tv.R.string.login_name_hint),
                    focusRequester = firstFocus,
                ) { onName(it) }
                Spacer(Modifier.height(ThemeSpec.Space.md))
                CredentialInputField(
                    label =
                        when (type) {
                            ProviderType.XTREAM -> {
                                androidx.compose.ui.res
                                    .stringResource(com.carteltv.tv.R.string.login_server_url)
                            }

                            ProviderType.M3U -> {
                                androidx.compose.ui.res
                                    .stringResource(com.carteltv.tv.R.string.login_playlist_url)
                            }

                            ProviderType.STALKER -> {
                                androidx.compose.ui.res
                                    .stringResource(com.carteltv.tv.R.string.login_portal_url)
                            }
                        },
                    value = host,
                    placeholder =
                        when (type) {
                            ProviderType.XTREAM -> HINT_XTREAM
                            ProviderType.M3U -> HINT_M3U
                            ProviderType.STALKER -> HINT_STALKER
                        },
                ) { onHost(it) }
                if (type == ProviderType.XTREAM) {
                    Spacer(Modifier.height(ThemeSpec.Space.md))
                    CredentialInputField(
                        label =
                            androidx.compose.ui.res
                                .stringResource(com.carteltv.tv.R.string.login_username),
                        value = user,
                        placeholder =
                            androidx.compose.ui.res
                                .stringResource(com.carteltv.tv.R.string.login_username_hint),
                    ) { onUser(it) }
                    Spacer(Modifier.height(ThemeSpec.Space.md))
                    CredentialInputField(
                        label =
                            androidx.compose.ui.res
                                .stringResource(com.carteltv.tv.R.string.login_password),
                        value = pass,
                        placeholder =
                            androidx.compose.ui.res
                                .stringResource(com.carteltv.tv.R.string.login_password_hint),
                        password = true,
                    ) { onPass(it) }
                }
                if (type == ProviderType.STALKER) {
                    Spacer(Modifier.height(ThemeSpec.Space.md))
                    CredentialInputField(
                        label =
                            androidx.compose.ui.res
                                .stringResource(com.carteltv.tv.R.string.login_mac_address),
                        value = user,
                        placeholder = "00:1A:79:12:34:56",
                    ) { onUser(it) }
                }
                Spacer(Modifier.height(ThemeSpec.Space.lg))

                // Unfilled while the form is incomplete; the hint below names what is missing.
                ActionButton("Connect", filled = ready, modifier = Modifier.fillMaxWidth()) { onConnect() }

                if (!ready) {
                    Spacer(Modifier.height(ThemeSpec.Space.md))
                    Text(
                        when (type) {
                            ProviderType.XTREAM -> "Enter the server, username and password to connect."
                            ProviderType.M3U -> "Enter the link to your M3U playlist (http or https)."
                            ProviderType.STALKER -> "Enter the portal link and the MAC address registered with your provider."
                        },
                        color = ThemeSemantics.textSecondary,
                        fontSize = ThemeSpec.Type.meta,
                    )
                }
                error?.let {
                    Spacer(Modifier.height(ThemeSpec.Space.md))
                    Text(it, color = ThemeSemantics.danger, fontSize = ThemeSpec.Type.meta, maxLines = 3)
                }
            }

            LoginStage.SIGNING_IN -> {
                // The phone panel shares this stage and the house six-row skeleton would neither fit under the
                // masthead nor sit clear of it, so the compact loader stays, in the PageStateViews voice.
                Text(
                    androidx.compose.ui.res
                        .stringResource(com.carteltv.tv.R.string.login_signing_in),
                    fontSize = ThemeSpec.Type.body,
                    fontWeight = FontWeight.Bold,
                    color = ThemeSemantics.textEmphatic,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Brand mark + wordmark: an accent glyph tile beside the product name and a one-line promise. */
@Composable
private fun BrandLockup() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                .background(ThemeSemantics.accent),
            contentAlignment = Alignment.Center,
        ) {
            PhosphorIcon(com.carteltv.tv.R.drawable.ph_broadcast, ThemeSemantics.textInverse, 24.dp)
        }
        Spacer(Modifier.width(ThemeSpec.Space.md))
        Column {
            Text(
                "CartelTV",
                fontSize = ThemeSpec.Type.title,
                fontFamily = ThemeSpec.Font.display,
                fontWeight = FontWeight.Bold,
                letterSpacing = ThemeSpec.Type.trackHeadline,
                color = ThemeSemantics.textEmphatic,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                "Live TV · Films · Sports",
                fontSize = ThemeSpec.Type.label,
                color = ThemeSemantics.textSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** The phone panel is sized to its QR code and caption; wider would crowd the sign-in form it sits beside. */
private val PHONE_PANEL_WIDTH = 300.dp

@Composable
private fun PhonePanel(
    url: String?,
    modifier: Modifier,
) {
    Column(
        modifier
            .width(PHONE_PANEL_WIDTH)
            .glassPane(RoundedCornerShape(ThemeSpec.Card.cornerLg))
            .padding(ThemeSpec.Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(com.carteltv.tv.R.drawable.ph_monitor_play, ThemeSemantics.accent, 20.dp)
            Spacer(Modifier.width(ThemeSpec.Space.sm))
            Text(
                androidx.compose.ui.res
                    .stringResource(com.carteltv.tv.R.string.login_type_from_phone),
                fontSize = ThemeSpec.Type.title,
                color = ThemeSemantics.textEmphatic,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Text(
            "Scan the QR code with your phone and type there. The selected field on the TV fills in as you type.",
            fontSize = ThemeSpec.Type.meta,
            color = ThemeSemantics.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(ThemeSpec.Space.lg))
        if (url != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                    .background(Color.White)
                    .padding(ThemeSpec.Space.sm),
            ) {
                QrCode(text = url, cellSize = 6.dp)
            }
            Spacer(Modifier.height(ThemeSpec.Space.md))
            Text(
                url,
                fontSize = ThemeSpec.Type.label,
                color = ThemeSemantics.accent,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        } else {
            Text(
                androidx.compose.ui.res
                    .stringResource(com.carteltv.tv.R.string.common_connecting),
                fontSize = ThemeSpec.Type.meta,
                color = ThemeSemantics.textSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
