package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.engine.stability.SecretVault

/**
 * Simkl tracker credentials (SecretVault-backed).
 *
 * Extracted from [AppPreferences] — third-party auth has its own responsibility.
 */
class SimklPreferences(private val sp: SharedPreferences) {

    var simklClientId: String
        get() = SecretVault.decrypt(sp.getString("simkl_cid", null)) ?: ""
        set(v) = sp.edit().putString("simkl_cid", SecretVault.encrypt(v.trim())).apply()

    var simklToken: String
        get() = SecretVault.decrypt(sp.getString("simkl_tok", null)) ?: ""
        set(v) = sp.edit().putString("simkl_tok", SecretVault.encrypt(v.trim())).apply()

    var simklRefresh: String
        get() = SecretVault.decrypt(sp.getString("simkl_rfr", null)) ?: ""
        set(v) = sp.edit().putString("simkl_rfr", SecretVault.encrypt(v.trim())).apply()

    var simklUser: String
        get() = sp.getString("simkl_user", "") ?: ""
        set(v) = sp.edit().putString("simkl_user", v).apply()

    var simklExpiresAt: Long
        get() = sp.getLong("simkl_exp", 0L)
        set(v) = sp.edit().putLong("simkl_exp", v).apply()

    var simklBlockedUntil: Long
        get() = sp.getLong("simkl_blocked", 0L)
        set(v) = sp.edit().putLong("simkl_blocked", v).apply()
}
