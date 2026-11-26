package com.carteltv.tv

import com.carteltv.tv.data.network.HttpTransport
import com.carteltv.tv.data.network.HttpsUpgradeTransport
import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.network.OkHttpTransport
import com.carteltv.tv.engine.stability.AndroidKeystoreKeyProvider
import com.carteltv.tv.engine.stability.KeyProvider
import com.carteltv.tv.engine.stability.SecretVault

/** Core composition root: centralizes production implementation bindings to their seams. */
class AppContainer(
    val http: HttpTransport = HttpsUpgradeTransport(OkHttpTransport),
    val keys: KeyProvider = AndroidKeystoreKeyProvider,
) {
    fun install() {
        HttpSupport.transport = http
        SecretVault.keys = keys
    }
}
