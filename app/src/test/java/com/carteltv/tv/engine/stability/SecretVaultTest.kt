package com.carteltv.tv.engine.stability

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class SecretVaultTest {

    private val originalKeys = SecretVault.keys

    @After
    fun restoreKeys() {
        SecretVault.keys = originalKeys
    }

    @Test
    fun `encrypt fails closed when the keystore is unavailable`() {
        SecretVault.keys = KeyProvider { error("keystore unavailable") }
        assertNull(SecretVault.encrypt("rd-token-abc123!@#"))
        assertNull(SecretVault.encrypt("rd-token-abc123!@#", "profile"))
    }

    @Test
    fun `encrypt round-trips with a working key`() {
        SecretVault.keys = KeyProvider { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        val secret = "rd-token-abc123!@#"

        val legacy = checkNotNull(SecretVault.encrypt(secret))
        assertTrue(legacy.startsWith("enc1:"))
        assertEquals(secret, SecretVault.decrypt(legacy))

        val scoped = checkNotNull(SecretVault.encrypt(secret, "profile"))
        assertTrue(scoped.startsWith("enc2:"))
        assertEquals(secret, SecretVault.decrypt(scoped, "profile"))
        assertNull(SecretVault.decrypt(scoped, "other-profile"))
    }

    @Test
    fun `decrypt of unknown ciphertext returns original not null`() {
        val legacy = "plaintext-legacy-token"
        assertEquals(legacy, SecretVault.decrypt(legacy))
        assertEquals(null, SecretVault.decrypt(null))
        assertEquals("", SecretVault.decrypt(""))
    }

    @Test
    fun `encrypt is idempotent for already-prefixed values`() {
        val already = "enc1:NotRealCipherButPrefixed"
        assertEquals(already, SecretVault.encrypt(already))
    }
}
