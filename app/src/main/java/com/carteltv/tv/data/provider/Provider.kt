package com.carteltv.tv.data.provider

import kotlinx.serialization.Serializable
import com.carteltv.tv.data.preferences.Credentials

/** How a provider is reached. [host] is the server (Xtream), the playlist link (M3U) or the portal link (Stalker). */
enum class ProviderType(val label: String) {
    XTREAM("Xtream Codes"), M3U("M3U Playlist"), STALKER("Stalker Portal");

    companion object {
        fun of(raw: String): ProviderType = entries.firstOrNull { it.name.equals(raw, true) } ?: XTREAM
    }
}

/**
 * One saved IPTV source. [id] is stable across edits; [name] is what the user sees.
 * For Xtream, [user]/[pass] are the login. For M3U they are unused. For Stalker [user] holds the MAC address.
 * [epgUrl] and [extraEpg] are null until the user sets them: null falls back to the older app-wide guide setting.
 * [health] is the account state the server last reported (XCIPTV's user_info cache); null until the first check.
 */
@Serializable
data class Provider(
    val id: String,
    val name: String,
    val host: String,
    val user: String,
    val pass: String,
    val type: String = ProviderType.XTREAM.name,
    val epgUrl: String? = null,
    val extraEpg: List<String>? = null,
    val health: ProviderHealth? = null,
) {
    val kind: ProviderType get() = ProviderType.of(type)

    companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString().take(8)

        fun of(c: Credentials, name: String = hostLabel(c.host)): Provider =
            Provider(newId(), name, c.host, c.user, c.pass)

        fun hostLabel(host: String): String =
            host.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { host }
    }
}

/**
 * Account state from the provider's own handshake. [status] is the server's word
 * ("Active", "Expired", "Banned", "Disabled"); [expiryMs] is the epoch-ms expiry
 * or 0 when unlimited; [activeCons]/[maxCons] expose a saturated line.
 * [formats] is what user_info.allowed_output_formats declared ("ts"/"m3u8"/...), so
 * the last login's format choice survives app restarts without signing in again.
 */
@Serializable
data class ProviderHealth(
    val status: String? = null,
    val expiryMs: Long = 0L,
    val maxCons: Int? = null,
    val activeCons: Int? = null,
    val checkedAtMs: Long = 0L,
    val formats: List<String>? = null,
) {
    val isWarning: Boolean get() = status != null && !status.equals("Active", true)
    val daysLeft: Int get() = if (expiryMs <= 0L) Int.MAX_VALUE else ((expiryMs - System.currentTimeMillis() + 86_399_999L) / 86_400_000L).toInt()
}

/** What the login screen is working on: a provider being edited, or a new one being added. */
object ProviderEdit {
    @Volatile var targetId: String? = null
    @Volatile var adding: Boolean = false
    @Volatile var newType: ProviderType = ProviderType.XTREAM

    fun edit(id: String) { targetId = id; adding = false }
    fun add(type: ProviderType = ProviderType.XTREAM) { targetId = null; adding = true; newType = type }
    fun clear() { targetId = null; adding = false; newType = ProviderType.XTREAM }
}
