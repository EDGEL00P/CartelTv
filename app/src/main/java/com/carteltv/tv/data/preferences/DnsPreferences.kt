package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.engine.stability.SecretVault

/**
 * Private DNS / ad-blocking preferences.
 *
 * Extracted from [AppPreferences] — DNS settings have their own responsibility.
 */
class DnsPreferences(private val sp: SharedPreferences) {

    var nextDnsProfileTyped: String
        get() = SecretVault.decrypt(sp.getString("dns_nd_profile", null)) ?: ""
        set(v) = sp.edit().putString("dns_nd_profile", SecretVault.encrypt(v.trim())).apply()

    var nextDnsProfileBuiltIn: String
        get() = sp.getString("dns_nd_builtin", "") ?: ""
        set(v) = sp.edit().putString("dns_nd_builtin", v.trim()).apply()

    var dnsProvider: String
        get() = sp.getString("dns_provider", "") ?: ""
        set(v) = sp.edit().putString("dns_provider", v).apply()

    var dnsPauseApps: Set<String>
        get() = sp.getStringSet("dns_pause_apps", null) ?: setOf("in.startv.hotstar")
        set(v) = sp.edit().putStringSet("dns_pause_apps", v).apply()
}
