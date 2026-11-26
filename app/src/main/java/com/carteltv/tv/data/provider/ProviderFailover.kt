package com.carteltv.tv.data.provider

import com.carteltv.tv.data.network.Connectivity
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.core.suspendRunCatching
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When the active provider stops answering, moves to the next saved provider that does. The order of the provider
 * list is the priority. It only ever runs when the user turned it on, and it tells the caller what it did.
 */
object ProviderFailover {
    private const val PING_MS = 25_000L

    /** The providers to try, in priority order, once the active one has failed. Skips providers known to be expired/dead. */
    fun candidates(all: List<Provider>, activeId: String): List<Provider> = all.filter { p ->
        p.id != activeId && (p.health == null || (!p.health.isWarning && p.health.daysLeft > 0))
    }

    /** Returns the provider switched to, or null when nothing changed (healthy, off, or nothing better). */
    suspend fun check(): Provider? {
        if (!AppPreferences.providerFailover) return null
        // With no internet every provider looks dead; switching then would only strand the user on the wrong one.
        if (!Connectivity.isOnline) return null
        val active = AppPreferences.activeProvider() ?: return null
        if (alive(active)) return null
        for (p in candidates(AppPreferences.providers(), active.id)) {
            if (alive(p)) {
                AppPreferences.activeProviderId = p.id
                ProviderSession.reload()
                return p
            }
        }
        return null
    }

    private suspend fun alive(p: Provider): Boolean =
        withTimeoutOrNull(PING_MS) { suspendRunCatching { ProviderSession.clientFor(p).ping() }.isSuccess } ?: false
}
