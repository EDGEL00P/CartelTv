package com.carteltv.tv.ui.settings

import com.carteltv.tv.ui.components.TransientStatus

import android.content.Context
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.network.isHttpUrl
import com.carteltv.tv.data.provider.Provider
import com.carteltv.tv.data.provider.ProviderEdit
import com.carteltv.tv.data.provider.ProviderType
import com.carteltv.tv.data.xtream.ChannelGroups
import com.carteltv.tv.data.xtream.ChannelGroupConfig
import com.carteltv.tv.data.xtream.LiveChannels
import com.carteltv.tv.data.xtream.EpgPresets
import com.carteltv.tv.data.xtream.EpgSync
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.data.xtream.normalizeOutputFormats
import com.carteltv.tv.data.xtream.normalizeOutputFormats
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.ui.components.TvDialogs
import com.carteltv.tv.core.suspendRunCatching
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Wording the account and provider rows share, so the two never drift apart. */
internal const val NEED_PROVIDER = "Add a provider first."
internal const val NEED_HTTP_URL = "URL must start with http:// or https://"
internal const val SUGGESTED_GUIDES = "Suggested guides"

/** Opens the login form to add a provider. */internal fun addProvider(ctx: Context) {
    val types = ProviderType.entries
    TvDialogs.menu("Add provider", types.map { it.label }) { i ->
        ProviderEdit.add(types[i])
        Nav.resetTo(Route.Home)
        Nav.push(Route.Login)
    }
}

private fun editProvider(p: Provider) {
    ProviderEdit.edit(p.id)
    Nav.resetTo(Route.Home)
    Nav.push(Route.Login)
}

/** Everything you can do to one provider; [onChanged] fires after anything that alters the list or the active one. */
internal fun showProviderMenu(ctx: Context, scope: CoroutineScope, p: Provider, onChanged: () -> Unit) {
    val active = AppPreferences.activeProvider()?.id == p.id
    val pos = AppPreferences.providers().indexOfFirst { it.id == p.id }
    val labels = listOfNotNull(
        if (active) null else "Set as active",
        "Edit login",
        "Rename",
        "Check account",
        if (pos > 0) "Move up (tried earlier on failover)" else null,
        if (pos < AppPreferences.providers().size - 1) "Move down (tried later on failover)" else null,
        "Remove",
    )
    TvDialogs.menu(p.name, labels) { i ->
        when (labels[i]) {
            "Set as active" -> {
                AppPreferences.activeProviderId = p.id
                ProviderSession.reload()
                onChanged()
                TransientStatus.show("${p.name} is now the active provider")
            }
            "Edit login" -> editProvider(p)
            "Rename" -> rename(p, onChanged)
            "Check account" -> checkAccount(ctx, scope, p)
            "Move up (tried earlier on failover)" -> { AppPreferences.moveProvider(p.id, -1); onChanged() }
            "Move down (tried later on failover)" -> { AppPreferences.moveProvider(p.id, 1); onChanged() }
            "Remove" -> confirmRemove(p, onChanged)
        }
    }
}

private fun rename(p: Provider, onChanged: () -> Unit) {
    TvDialogs.input("Rename provider", initial = p.name) { value ->
        val n = value.trim()
        if (n.isNotEmpty()) { AppPreferences.upsertProvider(p.copy(name = n)); onChanged() }
    }
}

private fun confirmRemove(p: Provider, onChanged: () -> Unit) {
    TvDialogs.confirm(
        "Remove ${p.name}?",
        message = "The sign-in details for ${p.user} at ${p.host} will be removed.",
        confirmLabel = "Remove",
        destructive = true,
    ) {
        val wasActive = AppPreferences.activeProvider()?.id == p.id
        AppPreferences.removeProvider(p.id)
        if (wasActive) ProviderSession.reload()
        onChanged()
    }
}

private fun checkAccount(ctx: Context, scope: CoroutineScope, p: Provider) {
    TransientStatus.show("Checking ${p.name}…")
    scope.launch {
        val result = suspendRunCatching {
            val client = ProviderSession.clientFor(p)
            val info = client.authenticate().first
            val channels = if (p.kind == ProviderType.XTREAM) null else client.liveStreams(null).size
            info to channels
        }
        val msg = result.fold(
            onSuccess = { (info, channels) ->
                // Persist the freshly checked health so the banner reflects it.
                AppPreferences.setProviderHealth(p.id, ProviderSession.healthOf(info))
                listOfNotNull(
                    "${p.kind.label}",
                    accountSummary(info),
                    channels?.let { "$it live channels" },
                ).joinToString("\n")
            },
            onFailure = { "Could not sign in: ${it.message ?: it.javaClass.simpleName}" },
        )
        TvDialogs.info(p.name, msg)
    }
}

/** Expiry, connection counts and the line's stream formats as the server reports them; an absent or zero expiry means no end date. */
internal fun accountSummary(u: XtreamClient.UserInfo?, now: Long = System.currentTimeMillis()): String {
    if (u == null) return "Signed in."
    val expiryMs = u.expiryDate?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
    val expiry = when {
        expiryMs == null -> "No expiry date"
        expiryMs < now -> "Expired " + DateFormat.getDateInstance().format(Date(expiryMs))
        else -> "Expires " + DateFormat.getDateInstance().format(Date(expiryMs)) +
            " (${TimeUnit.MILLISECONDS.toDays(expiryMs - now)} days left)"
    }
    val conns = if (u.maxConnections != null) "Connections: ${u.activeCons ?: 0} of ${u.maxConnections} in use" else null
    val formats = normalizeOutputFormats(u.allowedOutputFormats)?.joinToString(" · ") { formatLabel(it) }?.let { "Formats: $it" }
    return listOfNotNull("Signed in as ${u.username ?: "—"}", expiry, conns, formats).joinToString("\n")
}

/** The line's declared outputs in viewer-facing spelling; unknown names keep their raw shape. */
private fun formatLabel(f: String) = when (f) {
    "ts" -> "TS"; "m3u8" -> "HLS"; "hls" -> "HLS"; "rtmp" -> "RTMP"; else -> f.uppercase()
}

/** Add an extra guide: a link typed in, or one of the suggested lists. */
internal fun showAddGuide(ctx: Context, scope: CoroutineScope, onChanged: () -> Unit) {
    if (AppPreferences.extraGuideUrls.size >= EpgSync.MAX_SOURCES - 1) {
        TransientStatus.show("You can add up to ${EpgSync.MAX_SOURCES - 1} extra guides.")
        return
    }
    TvDialogs.menu("Add guide", listOf("Type a URL", "Pick a suggested guide")) { i ->
        if (i == 0) editGuide(ctx, scope, null, onChanged) else pickSuggested(onChanged)
    }
}

private fun pickSuggested(onChanged: () -> Unit) {
    val presets = (EpgPresets.extras + EpgPresets.all).distinctBy { it.url.lowercase() }
    TvDialogs.menu(SUGGESTED_GUIDES, presets.map { it.label }) { i ->
        if (saveGuides(AppPreferences.extraGuideUrls + presets[i].url)) onChanged()
    }
}

/** Edit [old] in place, or add a new link when it is null. */
internal fun editGuide(ctx: Context, scope: CoroutineScope, old: String?, onChanged: () -> Unit) {
    TvDialogs.input(
        if (old == null) "Add guide URL" else "Edit guide URL",
        initial = old.orEmpty(),
        hint = "https://…/guide.xml",
    ) { value ->
        val v = value.trim()
        if (!v.isHttpUrl()) {
            TransientStatus.show(NEED_HTTP_URL)
            return@input
        }
        val cur = AppPreferences.extraGuideUrls
        val next = if (old == null) cur + v else cur.map { if (it == old) v else it }
        if (saveGuides(next)) {
            onChanged()
            scope.launch { probe(ctx, v) }
        }
    }
}

/** Actions for one saved extra guide. */
internal fun showGuideMenu(ctx: Context, scope: CoroutineScope, url: String, onChanged: () -> Unit) {
    val list = AppPreferences.extraGuideUrls
    val idx = list.indexOfFirst { it == url }
    val labels = listOfNotNull("Edit URL", if (idx > 0) "Move up (read earlier)" else null, "Remove")
    TvDialogs.menu(guideLabel(url), labels, message = url) { i ->
        when (labels[i]) {
            "Edit URL" -> editGuide(ctx, scope, url, onChanged)
            "Remove" -> { saveGuides(list - url, remove = true); onChanged() }
            else -> {
                val moved = list.toMutableList().apply { removeAt(idx); add(idx - 1, url) }
                saveGuides(moved); onChanged()
            }
        }
    }
}

internal fun guideLabel(url: String): String =
    (EpgPresets.extras + EpgPresets.all).firstOrNull { it.url.equals(url, true) }?.label
        ?: url.substringAfter("://").substringBefore('/')

/** Saves the list, reading it back through the same rules the sync uses. Returns false when nothing changed. */
private fun saveGuides(next: List<String>, remove: Boolean = false): Boolean {
    val clean = next.map { it.trim() }.filter(String::isHttpUrl).distinctBy { it.lowercase() }
        .take(EpgSync.MAX_SOURCES - 1)
    if (!remove && clean == AppPreferences.extraGuideUrls) return false
    AppPreferences.extraGuideUrls = clean
    AppPreferences.epgSyncOkMs = 0L
    return true
}

/** Tells the user right away whether a freshly saved guide link answers. */
private suspend fun probe(ctx: Context, url: String) {
    val code = withContext(Dispatchers.IO) {
        runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 8_000; readTimeout = 8_000; requestMethod = "GET"
                try { responseCode } finally { disconnect() }
            }
        }.getOrNull()
    }
    val msg = when {
        code == null -> "Saved, but ${guideLabel(url)} did not respond. Check the link."
        code in 200..299 -> "Guide saved. ${guideLabel(url)} is reachable."
        else -> "Saved, but ${guideLabel(url)} returned HTTP $code."
    }
    TransientStatus.show(msg)
}

/** Lists the active provider's live groups so they can be hidden, renamed and put in order. */
internal fun showGroupManager(ctx: Context, scope: CoroutineScope) {
    val client = ProviderSession.current
    if (client == null) {
        TransientStatus.show(NEED_PROVIDER)
        return
    }

    scope.launch {
        val raw = LiveChannels.rawGroups(client)
        if (raw.isEmpty()) {
            TransientStatus.show("No groups loaded yet. Open Live TV once, then try again.")
        } else {
            renderGroupList(ctx, raw)
        }
    }
}

private fun renderGroupList(ctx: Context, raw: List<com.carteltv.tv.data.xtream.XtreamClient.Category>) {
    val config = AppPreferences.channelGroupConfig()
    val ordered = ChannelGroups.apply(raw, config.copy(hidden = emptySet()))
    val originals = raw.associate { it.category_id to it.category_name }
    val rows = ordered.map { g ->
        val hidden = g.category_id in config.hidden
        val renamed = config.names.containsKey(g.category_id)
        val locked = g.category_id in config.locked
        (if (hidden) "HIDDEN · " else "") + (if (locked) "PIN · " else "") + g.category_name +
            if (renamed) "   (was ${originals[g.category_id]})" else ""
    }
    val reset = "Reset all groups"
    TvDialogs.menu(
        "Channel groups · ${config.hidden.count { it in originals }} hidden",
        rows + reset,
        cancelLabel = "Done",
    ) { i ->
        if (i == rows.size) {
            AppPreferences.setChannelGroupConfig(ChannelGroupConfig())
            renderGroupList(ctx, raw)
        } else {
            showGroupMenu(ctx, raw, ordered.map { it.category_id }, ordered[i].category_id, originals[ordered[i].category_id].orEmpty())
        }
    }
}

private fun showGroupMenu(
    ctx: Context, raw: List<com.carteltv.tv.data.xtream.XtreamClient.Category>,
    shown: List<String>, id: String, original: String,
) {
    val config = AppPreferences.channelGroupConfig()
    val hidden = id in config.hidden
    val locked = id in config.locked
    val labels = listOf(if (hidden) "Show" else "Hide", if (locked) "Remove PIN" else "Lock with a PIN", "Rename", "Move up", "Move down")
    TvDialogs.menu(
        config.names[id] ?: original, labels,
        cancelLabel = "Back", onCancel = { renderGroupList(ctx, raw) },
    ) { i ->
        when (labels[i]) {
            "Show", "Hide" -> { AppPreferences.setChannelGroupConfig(ChannelGroups.toggleHidden(config, id)); renderGroupList(ctx, raw) }
            "Lock with a PIN", "Remove PIN" -> {
                if (!locked && !com.carteltv.tv.data.preferences.AppPreferences.settingsPinIsSet()) {
                    // The lock is meaningless without a PIN; send the user to set one first.
                    com.carteltv.tv.ui.settings.PinRequests.requestSet?.invoke()
                } else {
                    AppPreferences.setChannelGroupConfig(ChannelGroups.toggleLocked(config, id))
                }
                renderGroupList(ctx, raw)
            }
            "Move up" -> { AppPreferences.setChannelGroupConfig(ChannelGroups.move(config, shown, id, -1)); renderGroupList(ctx, raw) }
            "Move down" -> { AppPreferences.setChannelGroupConfig(ChannelGroups.move(config, shown, id, 1)); renderGroupList(ctx, raw) }
            else -> TvDialogs.input(
                "Rename group",
                initial = config.names[id] ?: original,
                message = "Leave it empty to go back to \"$original\".",
                onCancel = { renderGroupList(ctx, raw) },
            ) { value ->
                AppPreferences.setChannelGroupConfig(ChannelGroups.rename(AppPreferences.channelGroupConfig(), id, original, value))
                renderGroupList(ctx, raw)
            }
        }
    }
}
