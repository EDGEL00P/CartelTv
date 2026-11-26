package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.data.provider.Provider
import com.carteltv.tv.data.provider.ProviderEdit
import com.carteltv.tv.data.provider.ProviderHealth
import com.carteltv.tv.data.provider.ProviderType
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.data.provider.StreamCredentials
import com.carteltv.tv.data.xtream.ChannelGroupConfig
import com.carteltv.tv.engine.stability.SecretVault
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Provider session preferences: saved providers, active provider, credentials, group config.
 *
 * Extracted from [AppPreferences] to give provider state its own responsibility (SRP).
 * All reads are memoized; writes go straight to SharedPreferences.
 */
class ProviderPreferences(private val sp: SharedPreferences, private val json: Json) {

    /** Decrypting and parsing the provider list is not free; cache until the stored text changes. */
    private val memoLock = Any()
    @Volatile private var provRaw: String? = null
    @Volatile private var provList: List<Provider> = emptyList()
    @Volatile private var groupRaw: String? = null
    @Volatile private var groupMap: Map<String, ChannelGroupConfig> = emptyMap()

    /** Every saved provider, oldest first. A pre-multi-provider single login is imported on first read. */
    fun providers(): List<Provider> {
        val raw = sp.getString("providers", null)
        if (raw != null) {
            if (raw == provRaw) return provList
            synchronized(memoLock) {
                if (raw == provRaw) return provList
                val parsed = runCatching {
                    json.decodeFromString<List<Provider>>(SecretVault.decrypt(raw) ?: raw)
                }.getOrDefault(emptyList())
                provList = parsed
                provRaw = raw
                return parsed
            }
        }
        val legacy = sp.getString("creds", null)?.let {
            runCatching { json.decodeFromString<Credentials>(SecretVault.decrypt(it) ?: it) }.getOrNull()
        } ?: return emptyList()
        val migrated = listOf(Provider.of(legacy))
        saveProviders(migrated)
        sp.edit().putString("active_provider", migrated[0].id).remove("creds").apply()
        return migrated
    }

    internal fun saveProviders(list: List<Provider>) {
        val encrypted = SecretVault.encrypt(json.encodeToString(list))
        if (encrypted == null) {
            android.util.Log.e("ProviderPreferences", "saveProviders: encryption failed; providers NOT saved to avoid data loss")
            return
        }
        sp.edit().putString("providers", encrypted).apply()
    }

    /** Moves a provider one place up (-1) or down (+1); the list order is the failover priority. */
    fun moveProvider(id: String, delta: Int) {
        val all = providers().toMutableList()
        val i = all.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in all.indices) return
        all.add(j, all.removeAt(i))
        saveProviders(all)
    }

    var activeProviderId: String
        get() = sp.getString("active_provider", "") ?: ""
        set(v) = sp.edit().putString("active_provider", v).apply()

    /** Hide, rename and order choices for the active provider's live groups, kept per provider. */
    fun channelGroupConfig(): ChannelGroupConfig {
        val id = activeProvider()?.id ?: return ChannelGroupConfig()
        return channelGroupConfigs()[id] ?: ChannelGroupConfig()
    }

    fun setChannelGroupConfig(config: ChannelGroupConfig) {
        val id = activeProvider()?.id ?: return
        val all = channelGroupConfigs().toMutableMap()
        if (config.isDefault) all.remove(id) else all[id] = config
        val jsonStr = json.encodeToString(all)
        val encrypted = SecretVault.encrypt(jsonStr) ?: jsonStr
        sp.edit().putString("group_cfg", encrypted).apply()
    }

    private fun channelGroupConfigs(): Map<String, ChannelGroupConfig> {
        val raw = sp.getString("group_cfg", null) ?: return emptyMap()
        val decrypted = SecretVault.decrypt(raw) ?: raw  // backward compat: unencrypted fallback
        if (decrypted == groupRaw) return groupMap
        synchronized(memoLock) {
            if (decrypted == groupRaw) return groupMap
            val parsed = runCatching {
                json.decodeFromString<Map<String, ChannelGroupConfig>>(decrypted)
            }.getOrDefault(emptyMap())
            groupMap = parsed
            groupRaw = decrypted
            return parsed
        }
    }

    fun provider(id: String): Provider? = providers().firstOrNull { it.id == id }

    /** Persists what the server last said about one provider's account (status, expiry, connections). */
    fun setProviderHealth(id: String, health: ProviderHealth) {
        val all = providers().toMutableList()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return
        saveProviders(all.toMutableList().also { list -> list[i] = list[i].copy(health = health) })
    }

    fun activeProvider(): Provider? {
        val all = providers()
        return all.firstOrNull { it.id == activeProviderId } ?: all.firstOrNull()
    }

    /** Adds the provider, or replaces the one with the same id. */
    fun upsertProvider(p: Provider) {
        val all = providers()
        saveProviders(if (all.any { it.id == p.id }) all.map { if (it.id == p.id) p else it } else all + p)
    }

    /** Removes a provider; when it was the active one the first remaining provider takes over. */
    fun removeProvider(id: String) {
        val rest = providers().filterNot { it.id == id }
        saveProviders(rest)
        if (activeProviderId == id || rest.none { it.id == activeProviderId }) {
            activeProviderId = rest.firstOrNull()?.id ?: ""
        }
    }

    /**
     * Saves a login that arrived without a provider id (the phone companion): the same server and user is updated,
     * otherwise it fills the provider being added or edited, otherwise it replaces the active one. Becomes active.
     */
    fun saveCredentials(c: Credentials) {
        val all = providers()
        val same = all.firstOrNull { it.host.equals(c.host, true) && it.user == c.user }
        val target = same ?: if (ProviderEdit.adding) null else ProviderEdit.targetId?.let { id -> all.firstOrNull { it.id == id } } ?: activeProvider()
        val xtreamTarget = target?.takeIf { it.kind == ProviderType.XTREAM }
        val cleanHost = if (xtreamTarget != null) ProviderSession.normalizeHost(c.host) else c.host.trim()
        val saved = xtreamTarget?.copy(host = cleanHost, user = c.user, pass = c.pass)
            ?: target?.copy(host = cleanHost, user = c.user, pass = c.pass)  // preserve non-Xtream kind
            ?: Provider.of(c.copy(host = cleanHost))
        upsertProvider(saved)
        activeProviderId = saved.id
    }

    fun clearCredentials() { activeProvider()?.let { removeProvider(it.id) } }

    /** The active provider's login, or null when none is set. */
    val credentials: Credentials?
        get() = activeProvider()?.let { Credentials(it.host, it.user, it.pass) }

    var providerFailover: Boolean
        get() = sp.getBoolean("provider_failover", false)
        set(v) = sp.edit().putBoolean("provider_failover", v).apply()
}

/** Provider login credentials. */
data class Credentials(val host: String, val user: String, val pass: String)
