package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.engine.stability.SecretVault

/**
 * Trakt.tv credentials (SecretVault-backed).
 *
 * Extracted from [AppPreferences] — third-party auth has its own responsibility.
 */
class TraktPreferences(private val sp: SharedPreferences) {

    var traktClientId: String
        get() = sp.getString("tk_cid", "") ?: ""
        set(v) = sp.edit().putString("tk_cid", v.trim()).apply()

    var traktClientSecret: String
        get() = SecretVault.decrypt(sp.getString("tk_csec", null)) ?: ""
        set(v) = sp.edit().putString("tk_csec", SecretVault.encrypt(v.trim())).apply()

    var traktToken: String
        get() = SecretVault.decrypt(sp.getString("tk_tok", null)) ?: ""
        set(v) = sp.edit().putString("tk_tok", SecretVault.encrypt(v)).apply()

    var traktRefresh: String
        get() = SecretVault.decrypt(sp.getString("tk_rfr", null)) ?: ""
        set(v) = sp.edit().putString("tk_rfr", SecretVault.encrypt(v)).apply()

    /** When the stored Trakt access token stops working; 0 means unknown, treated as due for a refresh. */
    var traktExpiresAtMs: Long
        get() = sp.getLong("tk_exp", 0L)
        set(v) = sp.edit().putLong("tk_exp", v).apply()
}
