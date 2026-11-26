package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.engine.stability.SecretVault

/**
 * EPG guide preferences: sync state, validators, custom XMLTV, catch-up.
 *
 * Extracted from [AppPreferences] — EPG data freshness has its own responsibility.
 * Reads/writes that fall back to the active provider delegate to [ProviderPreferences].
 */
class EpgPreferences(
    private val sp: SharedPreferences,
    private val providerPreferences: ProviderPreferences,
) {

    /** Last successful and last failed provider XMLTV download (epoch ms); drives [com.carteltv.tv.data.xtream.EpgFreshness]. */
    var epgSyncOkMs: Long
        get() = sp.getLong("epg_sync_ok_ms", 0L)
        set(v) = sp.edit().putLong("epg_sync_ok_ms", v).apply()

    var epgSyncFailMs: Long
        get() = sp.getLong("epg_sync_fail_ms", 0L)
        set(v) = sp.edit().putLong("epg_sync_fail_ms", v).apply()

    /** ETag or Last-Modified the guide at [url] had when it was last read in full; blank if unknown. */
    fun epgValidator(url: String): String = sp.getString("epg_val_${url.hashCode()}", "").orEmpty()

    fun setEpgValidator(url: String, v: String) = sp.edit().putString("epg_val_${url.hashCode()}", v).apply()

    /** Channel count the last full guide sync matched against; a changed playlist must not be skipped as "unchanged". */
    var epgSyncChannels: Int
        get() = sp.getInt("epg_sync_channels", -1)
        set(v) = sp.edit().putInt("epg_sync_channels", v).apply()

    /**
     * Reads a SecretVault-protected string; a legacy plaintext value is re-saved encrypted on first read.
     */
    private fun vaulted(key: String): String {
        val raw = sp.getString(key, null) ?: return ""
        val plain = SecretVault.decrypt(raw) ?: return ""
        if (plain.isNotBlank() && plain == raw) {
            val enc = SecretVault.encrypt(plain)
            if (enc != null && enc != raw) sp.edit().putString(key, enc).apply()
        }
        return plain
    }

    private fun cleanGuides(raw: List<String>): List<String> = raw.map { it.trim() }
        .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
        .distinctBy { it.lowercase() }

    /**
     * Extra XMLTV guides read after the provider's: they only fill channels the earlier guides do not cover.
     * Stored on the active provider when one exists, otherwise in a legacy encrypted key.
     */
    var extraGuideUrls: List<String>
        get() {
            val p = providerPreferences.activeProvider()
            val legacy = vaulted("extra_xmltv_urls").lines()
            return cleanGuides(p?.extraEpg ?: legacy)
        }
        set(v) {
            val clean = cleanGuides(v)
            val p = providerPreferences.activeProvider()
            if (p != null) {
                providerPreferences.upsertProvider(p.copy(extraEpg = clean))
            } else {
                sp.edit().putString("extra_xmltv_urls", SecretVault.encrypt(clean.joinToString("\n"))).apply()
            }
        }

    /** Optional XMLTV guide URL used instead of the provider's own `xmltv.php`. */
    var customXmltvUrl: String
        get() {
            val p = providerPreferences.activeProvider()
            return p?.epgUrl ?: vaulted("custom_xmltv_url")
        }
        set(v) {
            val trimmed = v.trim()
            val p = providerPreferences.activeProvider()
            if (p != null) {
                providerPreferences.upsertProvider(p.copy(epgUrl = trimmed))
            } else {
                sp.edit().putString("custom_xmltv_url", SecretVault.encrypt(trimmed)).apply()
            }
        }

    /** Catch-up: keep playing past the programme it was started on. */
    var catchupKeepPlaying: Boolean
        get() = sp.getBoolean("catchup_keep_playing", false)
        set(v) = sp.edit().putBoolean("catchup_keep_playing", v).apply()

    /** Catch-up playlists that ask the `.ts` link format. */
    fun catchupTsPlaylists(): MutableSet<String> =
        sp.getStringSet("catchup_ts_playlists", null)?.toMutableSet() ?: mutableSetOf()

    fun setCatchupTsPlaylists(ids: Set<String>) {
        sp.edit().putStringSet("catchup_ts_playlists", ids).apply()
    }
}
