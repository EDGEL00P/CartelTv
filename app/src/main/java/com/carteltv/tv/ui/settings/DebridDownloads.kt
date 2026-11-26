package com.carteltv.tv.ui.settings
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.components.clickableTv
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.debrid.DebridClient
import com.carteltv.tv.data.debrid.TorboxClient
import com.carteltv.tv.ui.theme.*
import kotlinx.coroutines.launch
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.FilterChip
import com.carteltv.tv.ui.components.PageError
import com.carteltv.tv.ui.components.PageLoading
import com.carteltv.tv.ui.components.TvTextField
import com.carteltv.tv.ui.components.awaitFocus

/** Key entry measure: the dialog width, so the field reads as a form line and not as one grid cell. */
private val TOKEN_FIELD_WIDTH = 680.dp

sealed interface CloudDownloadState {
    data object Empty : CloudDownloadState
    data object Loading : CloudDownloadState
    data class Ready(val rows: List<CloudDownloadRow>) : CloudDownloadState
    data class Failed(val msg: String) : CloudDownloadState
}

data class CloudDownloadRow(
    val id: String,          // provider-qualified
    val name: String,
    val sizeGb: Double,
    val ready: Boolean,
    val progress: Double,
    val statusLabel: String,
    val play: suspend () -> String?,   // returns stream URL
)

@Composable
fun DebridDownloads() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var provider by remember { mutableStateOf(if (AppPreferences.debridProvider == "tb") "tb" else "rd") }
    var rdToken by remember { mutableStateOf(AppPreferences.debridToken) }
    var tbToken by remember { mutableStateOf(AppPreferences.torboxToken) }
    var tokenRevision by remember { mutableIntStateOf(0) }
    var rdPairing by remember { mutableStateOf(false) }
    var tbPairing by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<CloudDownloadState>(CloudDownloadState.Empty) }
    var accountLine by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    suspend fun loadLocker() {
        state = CloudDownloadState.Loading; err = null
        try {
            if (provider == "rd") {
                com.carteltv.tv.data.debrid.DebridAuth.ensureFreshOrKeep()
                val c = DebridClient(AppPreferences.debridToken)
                val u = c.user()
                accountLine = "${u.username} · ${if (u.isPremium) "PREMIUM" else "STANDARD"}"
                val ts = c.torrents().sortedByDescending { it.ended ?: "" }
                state = CloudDownloadState.Ready(ts.map { t ->
                    CloudDownloadRow(
                        id = "rd_${t.id}", name = t.name ?: t.filename,
                        sizeGb = t.bytes / 1e9, ready = t.status == "downloaded" && t.links.isNotEmpty(),
                        progress = t.progress, statusLabel = t.status,
                        play = { c.bestStreamLink(t)?.let { c.unrestrict(it)?.download } },
                    )
                })
            } else {
                val c = TorboxClient(AppPreferences.torboxToken)
                val u = c.user()
                accountLine = "${u.email ?: "…"} · ${if (u.isPremium) "PREMIUM" else "STANDARD"}"
                val ts = c.myList()
                state = CloudDownloadState.Ready(ts.map { t ->
                    CloudDownloadRow(
                        id = "tb_${t.id}", name = t.name,
                        sizeGb = t.size / 1e9, ready = t.downloadFinished,
                        progress = t.progress, statusLabel = t.downloadState,
                        play = { c.bestLink(t) },
                    )
                })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            state = CloudDownloadState.Failed(e.message ?: "Request failed"); accountLine = null
        }
    }

    val savedRd = remember(tokenRevision) { AppPreferences.debridToken }
    val savedTb = remember(tokenRevision) { AppPreferences.torboxToken }

    LaunchedEffect(provider, savedRd, savedTb) {
        val ok = if (provider == "rd") savedRd.isNotBlank() else savedTb.isNotBlank()
        if (ok) loadLocker() else { state = CloudDownloadState.Empty; accountLine = null }
    }
    LaunchedEffect(provider, savedRd, savedTb) {
        val needsToken = (provider == "rd" && savedRd.isBlank()) || (provider == "tb" && savedTb.isBlank())
        if (needsToken) suspendRunCatching { focus.awaitFocus() }
    }

    // Two-column grid: the downloads are the only list; the switch, token entry and status lines span both columns.
    val fullSpan: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal, bottom = ThemeSpec.Space.margin),
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
    ) {
        item(span = fullSpan) {
            Row(
                Modifier.padding(vertical = ThemeSpec.Space.md),
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip("Real-Debrid", provider == "rd") { provider = "rd"; AppPreferences.debridProvider = "rd" }
                FilterChip("Torbox", provider == "tb") { provider = "tb"; AppPreferences.debridProvider = "tb" }
                Spacer(Modifier.weight(1f))
                accountLine?.let {
                    Text(it, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.width(ThemeSpec.Space.md))
                    var autoBusy by remember { mutableStateOf(false) }
                    ActionButton(if (autoBusy) "Resolving…" else "Play all", filled = !autoBusy) {
                        if (autoBusy) return@ActionButton
                        autoBusy = true; err = null
                        scope.launch {
                            suspendRunCatching {
                                val s0 = state
                                val ready = (s0 as? CloudDownloadState.Ready)?.rows?.filter { it.ready }.orEmpty()
                                if (ready.isEmpty()) error("No downloads are ready yet.")
                                var lastErr: Throwable? = null
                                for ((tier, candidate) in ready.withIndex()) {
                                    val url = suspendRunCatching { candidate.play() }.getOrNull()
                                    if (url != null) {
                                        AppLauncher.player(ctx, url, candidate.name, "debrid:${candidate.id}")
                                        return@suspendRunCatching
                                    }
                                    lastErr = lastErr ?: RuntimeException("No source was found for download ${tier + 1}")
                                }
                                error(lastErr?.message ?: "No source was found for any download")
                            }.onFailure { err = it.message }
                            autoBusy = false
                        }
                    }
                    Spacer(Modifier.width(ThemeSpec.Space.sm))
                    ActionButton("Refresh", filled = false) { scope.launch { loadLocker() } }
                    Spacer(Modifier.width(ThemeSpec.Space.sm))
                    ActionButton("Disconnect", filled = false) {
                        if (provider == "rd") { AppPreferences.debridToken = ""; AppPreferences.debridOauth = null; rdToken = "" } else { AppPreferences.torboxToken = ""; tbToken = "" }
                        tokenRevision++
                        state = CloudDownloadState.Empty; accountLine = null
                    }
                }
            }
        }

        when {
            (provider == "rd" && savedRd.isBlank()) ||
            (provider == "tb" && savedTb.isBlank()) -> {
                item(span = fullSpan) {
                    Column {
                        Text(
                            if (provider == "rd") "Real-Debrid API key · real-debrid.com/apitoken"
                            else "Torbox API key · torbox.app/settings",
                            fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(ThemeSpec.Space.cozy))
                        if (provider == "rd" && rdPairing) {
                            DebridPairPanel(
                                onLinked = { rdPairing = false; rdToken = AppPreferences.debridToken; tokenRevision++ },
                                onCancel = { rdPairing = false },
                            )
                            return@Column
                        }
                        if (provider == "tb" && tbPairing) {
                            TorboxPairPanel(
                                onLinked = { tbPairing = false; tbToken = AppPreferences.torboxToken; tokenRevision++ },
                                onCancel = { tbPairing = false },
                            )
                            return@Column
                        }
                        if (provider == "rd" || provider == "tb") {
                            ActionButton("Link with phone", filled = true, modifier = Modifier.focusRequester(focus)) {
                                if (provider == "rd") rdPairing = true else tbPairing = true
                            }
                            Spacer(Modifier.height(ThemeSpec.Space.cozy))
                            Text("Or paste a key", fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1)
                            Spacer(Modifier.height(ThemeSpec.Space.xs))
                        }
                        TokenField(
                            value = if (provider == "rd") rdToken else tbToken,
                            onChange = { if (provider == "rd") rdToken = it else tbToken = it },
                        )
                        Spacer(Modifier.height(ThemeSpec.Space.cozy))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                            ActionButton("Authorize", filled = true) {
                                if (provider == "rd") AppPreferences.debridToken = DebridClient.normalizeToken(rdToken)
                                else AppPreferences.torboxToken = TorboxClient.normalizeToken(tbToken)
                                tokenRevision++
                            }
                            err?.let { Text(it, color = ThemeSemantics.danger, fontSize = ThemeSpec.Type.meta, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }

        when (val s = state) {
            is CloudDownloadState.Loading -> item(span = fullSpan) { PageLoading() }
            is CloudDownloadState.Failed -> item(span = fullSpan) {
                PageError(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.debrid_error, s.msg)) {
                    scope.launch { loadLocker() }
                }
            }
            is CloudDownloadState.Ready -> {
                item(span = fullSpan) {
                    Text(
                        "Downloads · ${s.rows.size}", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = ThemeSpec.Space.cozy, bottom = ThemeSpec.Space.sm), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                items(s.rows.size, key = { s.rows[it].id }, contentType = { "download" }) { idx ->
                    val row = s.rows[idx]
                    val interaction = remember(row.id) { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    val pressed by interaction.collectIsPressedAsState()
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 104.dp)
                            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
                            .glassSurface(focused, RoundedCornerShape(ThemeSpec.Card.corner), ringWidth = ThemeSpec.Focus.ringWidth)
                            // Unfinished downloads stay out of both the D-pad order and pointer interaction.
                            .clickableTv(interactionSource = interaction, enabled = row.ready) {
                                scope.launch {
                                    suspendRunCatching {
                                        val url = row.play() ?: error("No source is available for this download")
                                        AppLauncher.player(ctx, url, row.name, "debrid:${row.id}")
                                    }.onFailure { err = it.message }
                                }
                            }
                            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            when {
                                row.ready -> "READY"
                                row.progress > 0 -> "%.0f%% %s".format(row.progress * 100, row.statusLabel)
                                else -> row.statusLabel
                            },
                            fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Bold,
                            color = when {
                                row.ready -> ThemeSemantics.success
                                row.progress > 0 -> ThemeSemantics.info
                                else -> ThemeSemantics.textSecondary
                            },
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(row.name, fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textEmphatic, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("%.1f GB".format(row.sizeGb), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (focused && row.ready) Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.debrid_open), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textEmphatic, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
                if (s.rows.isEmpty()) item(span = fullSpan) {
                    EmptyState("No downloads yet.")
                }
            }
            CloudDownloadState.Empty -> {}
        }
    }
}

@Composable
private fun TokenField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val field = rememberFieldValue(value)
    TvTextField(
        value = field.value,
        onValueChange = {
            field.value = it
            onChange(it.text)
        },
        modifier = modifier.width(TOKEN_FIELD_WIDTH),
    )
}
