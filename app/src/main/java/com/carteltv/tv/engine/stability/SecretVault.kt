package com.carteltv.tv.engine.stability

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets vault (Keystore AES-256-GCM envelope):
 * - `enc1:` legacy envelope without AAD (retained for seamless read compatibility).
 * - `enc2:` AAD-bound envelope (`$alias:$aad`) preventing cross-field or cross-profile
 *   ciphertext substitution.
 * - Failure mode on [decrypt]: returns null and leaves the stored value untouched, so a transient
 *   keystore error does not wipe tokens.
 * - [encrypt] fails closed: when the keystore is unavailable it returns null rather than writing
 *   the plaintext value. Callers that persist the result drop the stale preference instead of
 *   leaking a secret at rest.
 */
fun interface KeyProvider {
    fun key(alias: String): SecretKey
}

/** Get-or-create a non-exportable AES-256-GCM key in AndroidKeyStore. */
object AndroidKeystoreKeyProvider : KeyProvider {
    @Synchronized
    override fun key(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return try {
            generate(alias, strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(alias, strongBox = false)
        }
    }

    /** StrongBox keeps the key in a dedicated secure element where the device has one. */
    private fun generate(alias: String, strongBox: Boolean): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setIsStrongBoxBacked(strongBox)
                    .build(),
            )
        }.generateKey()
}

object SecretVault {
    private const val PREFIX_V1 = "enc1:"
    private const val PREFIX_V2 = "enc2:"
    private const val ALIAS = "carteltv_secrets"
    private val memo = ConcurrentHashMap<String, String>()

    internal fun buildAad(alias: String, scope: String): ByteArray =
        "$alias:$scope".toByteArray(Charsets.UTF_8)

    internal fun encodeBase64(bytes: ByteArray): String {
        val encoded = runCatching { Base64.encodeToString(bytes, Base64.NO_WRAP) }.getOrNull()
        return encoded ?: java.util.Base64.getEncoder().encodeToString(bytes)
    }

    internal fun decodeBase64(encoded: String): ByteArray {
        val decoded = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
        return decoded ?: java.util.Base64.getDecoder().decode(encoded)
    }

    /** Source of the AES key; tests bind an in-memory key where AndroidKeyStore is absent. */
    internal var keys: KeyProvider = AndroidKeystoreKeyProvider

    private fun key(alias: String = ALIAS): SecretKey = keys.key(alias)

    /** Pure AES-GCM AAD encrypt/decrypt helper (exercised directly in JVM unit tests). */
    internal fun sealWithKey(plain: String, secretKey: SecretKey, alias: String, aadScope: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(buildAad(alias, aadScope))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "$PREFIX_V2${encodeBase64(cipher.iv)}.${encodeBase64(ct)}"
    }

    internal fun unsealWithKey(stored: String, secretKey: SecretKey, alias: String, aadScope: String): String {
        require(stored.startsWith(PREFIX_V2)) { "Not an enc2 payload" }
        val parts = stored.removePrefix(PREFIX_V2).split('.', limit = 2)
        require(parts.size == 2) { "Malformed enc2 payload" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, decodeBase64(parts[0])))
        cipher.updateAAD(buildAad(alias, aadScope))
        return String(cipher.doFinal(decodeBase64(parts[1])), Charsets.UTF_8)
    }

    fun encrypt(plain: String?): String? {
        if (plain.isNullOrBlank() || plain.startsWith(PREFIX_V1) || plain.startsWith(PREFIX_V2)) return plain
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key())
            }
            PREFIX_V1 + encodeBase64(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
        }.onFailure { Log.w("Vault", "encrypt failed; refusing to store plaintext") }
            .getOrNull()
    }

    /** Encrypts with AAD bound to [aadScope] under `enc2:`. Returns null if KeyStore is unavailable. */
    fun encrypt(plain: String?, aadScope: String): String? {
        if (plain.isNullOrBlank() || plain.startsWith(PREFIX_V2)) return plain
        return runCatching {
            sealWithKey(plain, key(), ALIAS, aadScope)
        }.onFailure { Log.w("Vault", "enc2 encrypt failed for scope=$aadScope; refusing to store plaintext") }
            .getOrNull()
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank() || !stored.startsWith(PREFIX_V1)) return stored
        memo[stored]?.let { return it }
        return runCatching {
            val all = decodeBase64(stored.removePrefix(PREFIX_V1))
            val iv = all.copyOfRange(0, 12)
            val ct = all.copyOfRange(12, all.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            String(c.doFinal(ct), Charsets.UTF_8)
        }.onSuccess { memo[stored] = it }
            .onFailure { Log.w("Vault", "decrypt failed; stored value left untouched, caller sees no secret") }
            .getOrNull()
    }

    /**
     * Decrypts an AAD-bound `enc2:` payload (or seamlessly reads legacy `enc1:` / plaintext).
     * Rejects `enc2:` payloads whose AAD fails authentication (returns null so cross-profile
     * substitution can never silently succeed).
     */
    fun decrypt(stored: String?, aadScope: String): String? {
        if (stored.isNullOrBlank()) return stored
        if (stored.startsWith(PREFIX_V1)) return decrypt(stored)
        if (!stored.startsWith(PREFIX_V2)) return stored
        val cacheKey = "$aadScope|$stored"
        memo[cacheKey]?.let { return it }
        return runCatching {
            unsealWithKey(stored, key(), ALIAS, aadScope)
        }.onSuccess { memo[cacheKey] = it }
            .onFailure { Log.w("Vault", "enc2 decrypt failed for scope=$aadScope") }
            .getOrNull()
    }
}
