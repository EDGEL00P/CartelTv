package com.carteltv.tv.data.xtream

import com.carteltv.tv.data.network.isHttpUrl

import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.provider.Provider
import com.carteltv.tv.data.provider.ProviderHealth
import com.carteltv.tv.data.provider.ProviderType

/** Active session holder; the saved providers live in AppPreferences. */
object ProviderSession {
    var current: XtreamClient? = null

    suspend fun login(hostRaw: String, user: String, pass: String): XtreamClient {
        val client = XtreamClient(normalizeHost(hostRaw), user.trim(), pass)
        val (u, _) = client.authenticate() // throws on bad creds
        noteHealth(client, u)
        current = client
        return client
    }

    fun clear() { current = null }

    fun normalizeHost(hostRaw: String): String =
        hostRaw.trim().trimEnd('/').let { if (!it.isHttpUrl()) "http://$it" else it }

    /** The client that talks to this provider's kind of source. */
    fun clientFor(p: Provider): XtreamClient {
        val client = when (p.kind) {
            ProviderType.XTREAM -> XtreamClient(normalizeHost(p.host), p.user, p.pass)
            ProviderType.M3U -> M3uClient(p.host)
            ProviderType.STALKER -> StalkerClient(p.host, p.user)
        }
        // The last login's declared formats seed the client, so live links keep the provider's
        // output shape even before a fresh authenticate() has run.
        client.allowedFormats = normalizeOutputFormats(p.health?.formats)
        return client
    }

    /** Checks a login against the server without touching the active session. */
    suspend fun verify(hostRaw: String, user: String, pass: String): XtreamClient =
        XtreamClient(normalizeHost(hostRaw), user.trim(), pass).also {
            val (u, _) = it.authenticate()
            noteHealth(it, u)
        }

    /** Signs in to a provider of any kind without touching the active session. Throws when it does not work. Returns the health snapshot. */
    suspend fun verify(p: Provider): Pair<XtreamClient, ProviderHealth> {
        val client = clientFor(p)
        val (u, _) = client.authenticate()
        return client to healthOf(u)
    }

    /** Remembers what the server said about the account this client logs into, so the banner can warn early. */
    private fun noteHealth(client: XtreamClient, u: XtreamClient.UserInfo?) {
        val p = AppPreferences.providers().firstOrNull { it.host == client.baseUrl && it.user == client.username }
            ?: return
        AppPreferences.setProviderHealth(p.id, healthOf(u))
    }

    fun healthOf(u: XtreamClient.UserInfo?) = ProviderHealth(
        status = u?.status,
        expiryMs = u?.expiryDate?.toLongOrNull()?.times(1000) ?: 0L,
        maxCons = u?.maxConnections,
        activeCons = u?.activeCons,
        checkedAtMs = System.currentTimeMillis(),
        formats = u?.allowedOutputFormats,
    )

    /** Points the session at the active provider (or none) without dropping loaded data; used at app start. */
    fun restoreActive() {
        current = AppPreferences.activeProvider()?.let(::clientFor)
    }

    /**
     * Points the session at the provider AppPreferences now calls active (or none) and drops what was loaded for the
     * previous one. Call after switching, editing the active provider, or removing one.
     */
    fun reload() {
        restoreActive()
        LiveChannels.invalidate()
        AppPreferences.epgSyncOkMs = 0L
    }

    fun restore(host: String, user: String, pass: String): XtreamClient =
        XtreamClient(host, user, pass).also { current = it }
}
