package com.carteltv.tv.data.network

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.provider.Provider
import com.carteltv.tv.engine.stability.SecretVault
import kotlinx.coroutines.delay
import com.carteltv.tv.system.DnsResumeReceiver

/**
 * Network-wide ad blocking via Android's Private DNS (DNS-over-TLS). "Off" returns the setting to
 * Android's default (automatic), which is how the TV was configured before CartelTV.
 */
class PrivateDnsController(private val context: Context) {
    private val resolver get() = context.contentResolver

    enum class Provider(val label: String) { NEXTDNS("NextDNS"), ADGUARD("AdGuard DNS") }

    val nextDnsAvailable: Boolean get() = nextDnsProfile().isNotBlank()

    val provider: Provider
        get() = when (prefs.getString("provider", null)) {
            "adguard" -> Provider.ADGUARD
            "nextdns" -> if (nextDnsAvailable) Provider.NEXTDNS else Provider.ADGUARD
            else -> if (nextDnsAvailable && Settings.Global.getString(resolver, SPECIFIER) != ADGUARD_HOST) Provider.NEXTDNS
            else Provider.ADGUARD
        }

    fun setProvider(p: Provider) {
        prefs.edit().putString("provider", if (p == Provider.ADGUARD) "adguard" else "nextdns").apply()
    }

    val hostname: String
        get() = if (provider == Provider.NEXTDNS) "${nextDnsProfile()}.dns.nextdns.io" else ADGUARD_HOST

    val canControl: Boolean
        get() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    val isOn: Boolean
        get() = Settings.Global.getString(resolver, MODE) == "hostname" &&
            Settings.Global.getString(resolver, SPECIFIER) == hostname

    fun turnOff() {
        Settings.Global.putString(resolver, MODE, "opportunistic")
    }

    private val prefs get() = context.getSharedPreferences("dns", Context.MODE_PRIVATE)

    val resumeAt: Long get() = prefs.getLong("resumeAt", 0L)
    val isPaused: Boolean get() = resumeAt > 0L && !isOn

    fun pause(minutes: Int) {
        turnOff()
        val at = System.currentTimeMillis() + minutes * 60_000L
        prefs.edit().putLong("resumeAt", at).apply()
        val am = context.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, resumeIntent())
    }

    /** Re-enables ad blocking and ends the pause. Shared by the alarm receiver and the restart reconcile. */
    fun resumeNow() {
        if (canControl) {
            Settings.Global.putString(resolver, SPECIFIER, hostname)
            Settings.Global.putString(resolver, MODE, "hostname")
        }
        prefs.edit().remove("resumeAt").apply()
    }

    /** The pause alarm does not survive a reboot; without this, DNS would stay off with nothing left to turn it back on. */
    fun reconcilePause() {
        val at = resumeAt
        if (at == 0L) return
        if (at - System.currentTimeMillis() <= 60_000) {
            resumeNow()
        } else {
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, resumeIntent())
        }
    }

    val pauseApps: Set<String> get() = prefs.getStringSet("pause_apps", null) ?: DEFAULT_PAUSE_APPS
    val autoPaused: Boolean get() = prefs.getBoolean("auto_paused", false)
    private fun resumeIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 1, Intent(context, DnsResumeReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    suspend fun turnOnSafely(): Boolean {
        Settings.Global.putString(resolver, SPECIFIER, hostname)
        Settings.Global.putString(resolver, MODE, "hostname")
        val cm = context.getSystemService(ConnectivityManager::class.java)
        try {
            repeat(12) {
                delay(1_000)
                val net = cm.activeNetwork
                val caps = net?.let { cm.getNetworkCapabilities(it) }
                val lp = net?.let { cm.getLinkProperties(it) }
                val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                if (validated && lp?.isPrivateDnsActive == true && lp.privateDnsServerName == hostname) {
                    if (runCatching { java.net.InetAddress.getAllByName("api.trakt.tv").isNotEmpty() }.getOrDefault(false)) return true
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            turnOff()
            throw e
        }
        turnOff()
        return false
    }

    // NextDNS profile stored via SecretVault for security
    private fun nextDnsProfile(): String {
        val typed = AppPreferences.nextDnsProfileTyped
        return if (typed.isNotBlank()) typed else AppPreferences.nextDnsProfileBuiltIn
    }

    private companion object {
        const val MODE = "private_dns_mode"
        const val SPECIFIER = "private_dns_specifier"
        const val ADGUARD_HOST = "dns.adguard-dns.com"
        val DEFAULT_PAUSE_APPS = setOf("in.startv.hotstar")
    }
}
