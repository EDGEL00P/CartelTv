package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.data.network.HttpSupport

private const val PIN_V2 = "v2:"
private const val PIN_ITERATIONS = 40_000

private const val SETTINGS_PIN_KEY = "__settings__"

/**
 * PIN preferences: per-app PIN (PBKDF2 salted SHA-256) and the settings-door PIN.
 *
 * Extracted from [AppPreferences] — PIN verification, lockout, and migration logic has its own responsibility.
 */
class PinPreferences(private val sp: SharedPreferences) {

    /** Salted SHA-256 digest (legacy v1 format). */
    private fun pinDigest(pkg: String, pin: String): String =
        HttpSupport.sha256Hex("$pkg:$pin:cartelpin")

    private fun pbkdf2Hex(pin: String, salt: ByteArray): String {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec)
            .encoded.joinToString("") { "%02x".format(it) }
    }

    private fun pinRecordV2(pin: String): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return PIN_V2 + salt.joinToString("") { "%02x".format(it) } + ":" + pbkdf2Hex(pin, salt)
    }

    private fun matchesPinRecord(record: String, pkg: String, pin: String): Boolean {
        if (!record.startsWith(PIN_V2)) return record.equals(pinDigest(pkg, pin), ignoreCase = true)
        val parts = record.removePrefix(PIN_V2).split(':')
        if (parts.size != 2 || parts[0].length % 2 != 0) return false
        val salt = runCatching { ByteArray(parts[0].length / 2) { parts[0].substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
            .getOrNull() ?: return false
        return java.security.MessageDigest.isEqual(
            pbkdf2Hex(pin, salt).toByteArray(), parts[1].toByteArray(),
        )
    }

    fun appPins(): Map<String, String> =
        sp.getString("app_pins", null)?.split("\n")?.mapNotNull {
            val p = it.split("=")
            if (p.size == 2) p[0] to p[1] else null
        }?.toMap() ?: emptyMap()

    fun setAppPin(pkg: String, pin: String) {
        val cur = appPins().toMutableMap()
        cur[pkg] = pinRecordV2(pin)
        sp.edit().putString("app_pins", cur.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
    }

    fun clearAppPin(pkg: String) {
        val cur = appPins().toMutableMap(); cur.remove(pkg)
        sp.edit().putString("app_pins", cur.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
    }

    /** Milliseconds until another PIN attempt is allowed; 0 when not locked out. */
    fun pinLockRemainingMs(): Long =
        (sp.getLong("pin_lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun notePinFailure() {
        val fails = sp.getInt("pin_fails", 0) + 1
        val ed = sp.edit().putInt("pin_fails", fails)
        if (fails >= 5) {
            val step = (fails - 5).coerceAtMost(5)
            ed.putLong("pin_lock_until", System.currentTimeMillis() + (30_000L shl step))
        }
        ed.apply()
    }

    fun verifyAppPin(pkg: String, pin: String): Boolean {
        if (pinLockRemainingMs() > 0L) return false
        val record = appPins()[pkg] ?: return false
        if (!matchesPinRecord(record, pkg, pin)) {
            notePinFailure()
            return false
        }
        sp.edit().remove("pin_fails").remove("pin_lock_until").apply()
        if (!record.startsWith(PIN_V2)) setAppPin(pkg, pin)
        return true
    }

    /* ── Settings-door PIN: one digest guards the whole settings surface ── */

    fun settingsPinSet(pin: String) = setAppPin(SETTINGS_PIN_KEY, pin)
    fun settingsPinClear() = clearAppPin(SETTINGS_PIN_KEY)
    fun settingsPinIsSet(): Boolean = SETTINGS_PIN_KEY in appPins()
    fun settingsPinVerify(pin: String): Boolean = verifyAppPin(SETTINGS_PIN_KEY, pin)

    var settingsPinOn: Boolean
        get() = sp.getBoolean("settings_pin_on", false)
        set(v) = sp.edit().putBoolean("settings_pin_on", v).apply()
}
