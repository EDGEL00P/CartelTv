package com.carteltv.tv.data.trakt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktRefreshTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun unknown_expiry_is_refreshed() {
        assertTrue(TraktAuth.needsRefresh(expiresAtMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun token_with_days_left_is_left_alone() {
        val now = 10 * day
        assertFalse(TraktAuth.needsRefresh(expiresAtMs = now + 5 * day, nowMs = now))
    }

    @Test
    fun token_inside_the_last_day_or_expired_is_refreshed() {
        val now = 10 * day
        assertTrue(TraktAuth.needsRefresh(expiresAtMs = now + day / 2, nowMs = now))
        assertTrue(TraktAuth.needsRefresh(expiresAtMs = now - 1, nowMs = now))
    }
}
