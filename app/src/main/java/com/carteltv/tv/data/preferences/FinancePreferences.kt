package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.data.finance.ExchangeRateService

/**
 * Currency / finance preferences.
 *
 * Extracted from [AppPreferences] — currency settings have their own responsibility.
 */
class FinancePreferences(private val sp: SharedPreferences) {

    var currencyBase: String
        get() = sp.getString("fx_base", "") ?: ""
        set(v) = sp.edit().putString("fx_base", v.trim().uppercase()).apply()

    var currencyWatch: List<String>
        get() = (sp.getString("fx_watch", null) ?: "").split(",").map { it.trim().uppercase() }
            .filter { it.length == 3 }.distinct().ifEmpty { ExchangeRateService.defaultWatch }
        set(v) = sp.edit().putString("fx_watch", v.map { it.trim().uppercase() }.filter { it.length == 3 }.distinct().joinToString(",")).apply()
}
