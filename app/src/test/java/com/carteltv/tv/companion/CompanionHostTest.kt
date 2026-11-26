package com.carteltv.tv.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionHostTest {
    private val ip = "192.168.1.20"

    @Test
    fun serves_the_tv_address_with_or_without_a_port() {
        assertTrue(PhoneCompanionServer.isAllowedHost("192.168.1.20:9090", ip))
        assertTrue(PhoneCompanionServer.isAllowedHost("192.168.1.20", ip))
    }

    @Test
    fun serves_loopback() {
        assertTrue(PhoneCompanionServer.isAllowedHost("localhost:9090", ip))
        assertTrue(PhoneCompanionServer.isAllowedHost("127.0.0.1:9090", ip))
    }

    @Test
    fun turns_away_a_rebinding_name_a_blank_host_and_other_addresses() {
        assertFalse(PhoneCompanionServer.isAllowedHost("evil.example:9090", ip))
        assertFalse(PhoneCompanionServer.isAllowedHost("", ip))
        assertFalse(PhoneCompanionServer.isAllowedHost("192.168.1.99:9090", ip))
        assertFalse(PhoneCompanionServer.isAllowedHost("192.168.1.20.evil.example", ip))
    }

    @Test
    fun an_unknown_local_address_does_not_match_a_blank_host() {
        assertFalse(PhoneCompanionServer.isAllowedHost("evil.example", ""))
    }

    @Test
    fun a_live_session_is_not_expired() {
        setSessionState(running = true, expiresAt = 10_000L)
        assertFalse(PhoneCompanionServer.isSessionExpired(9_999))
    }

    @Test
    fun a_session_expires_at_its_deadline() {
        setSessionState(running = true, expiresAt = 10_000L)
        assertTrue(PhoneCompanionServer.isSessionExpired(10_000))
    }

    @Test
    fun a_stopped_server_is_never_expired() {
        setSessionState(running = false, expiresAt = 0L)
        assertFalse(PhoneCompanionServer.isSessionExpired(50_000))
    }

    @org.junit.After
    fun resetServerState() {
        setSessionState(running = false, expiresAt = 0L)
    }

    private fun setSessionState(running: Boolean, expiresAt: Long) {
        // running is read-only to the world; reflection keeps this test honest without a real socket.
        PhoneCompanionServer::class.java.getDeclaredField("running").apply { isAccessible = true }.set(PhoneCompanionServer, running)
        PhoneCompanionServer::class.java.getDeclaredField("expiresAtMs").apply { isAccessible = true }.setLong(PhoneCompanionServer, expiresAt)
    }
}
