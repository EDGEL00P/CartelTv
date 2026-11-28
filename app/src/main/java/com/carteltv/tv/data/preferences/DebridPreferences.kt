package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.data.debrid.DebridOauth
import com.carteltv.tv.engine.stability.SecretVault
import org.json.JSONObject

/**
 * Debrid credentials: Real-Debrid, Torbox, TMDB key.
 *
 * Extracted from [AppPreferences] — third-party debrid auth has its own responsibility.
 */
class DebridPreferences(private val sp: SharedPreferences) {

    var debridToken: String
        get() = SecretVault.decrypt(sp.getString("rd_tok", null)) ?: ""
        set(v) = sp.edit().putString("rd_tok", SecretVault.encrypt(v.trim())).apply()

    /** Real-Debrid device-flow grant (secret): client credentials and refresh token. Null for a hand-pasted key. */
    var debridOauth: DebridOauth?
        get() = SecretVault.decrypt(sp.getString("rd_oauth", null))?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                val j = JSONObject(raw)
                DebridOauth(
                    j.getString("c"), j.getString("s"), j.getString("r"), j.getString("a"), j.getLong("e"),
                )
            }.getOrNull()
        }
        set(v) {
            val raw = v?.let {
                JSONObject().put("c", it.clientId).put("s", it.clientSecret)
                    .put("r", it.refreshToken).put("a", it.accessToken).put("e", it.expiresAtMs).toString()
            }
            sp.edit().apply { if (raw == null) remove("rd_oauth") else putString("rd_oauth", SecretVault.encrypt(raw)) }.apply()
        }

    var torboxToken: String
        get() = SecretVault.decrypt(sp.getString("tb_tok", null)) ?: ""
        set(v) = sp.edit().putString("tb_tok", SecretVault.encrypt(v.trim())).apply()

    /** Which cloudLocker the Debrid UI shows: "rd" | "tb" */
    var debridProvider: String
        get() = sp.getString("db_prov", "rd") ?: "rd"
        set(v) = sp.edit().putString("db_prov", v).apply()

    /** TMDB v3 API key (ForYou rail); free key at themoviedb.org */
    var tmdbKey: String
        get() = SecretVault.decrypt(sp.getString("tmdb_k", null)) ?: ""
        set(v) = sp.edit().putString("tmdb_k", SecretVault.encrypt(v.trim())).apply()

    /** YouTube Data v3 API key (optional for online search); free key at console.cloud.google.com */
    var youtubeKey: String
        get() = SecretVault.decrypt(sp.getString("yt_key", null)) ?: ""
        set(v) = sp.edit().putString("yt_key", SecretVault.encrypt(v.trim())).apply()
}