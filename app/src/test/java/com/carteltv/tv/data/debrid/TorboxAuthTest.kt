package com.carteltv.tv.data.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorboxAuthTest {
    // Captured from GET https://api.torbox.app/v1/api/user/auth/device/start on 2026-11-25.
    private val start = """{"success":true,"error":null,"detail":"Successfully created your device code authorization.",
        "data":{"device_code":"dc123","code":"250684","interval":5,"expires_in":600,"expires_at":"2026-11-25T05:38:20Z",
        "verification_url":"https://torbox.app/oauth/device?app=CartelTV",
        "verification_url_complete":"https://torbox.app/oauth/device?app=CartelTV&code=250684",
        "friendly_verification_url":"https://tor.box/link"}}"""

    // Captured from POST .../device/token before approval: HTTP 400.
    private val pending = """{"success":false,"error":"DEVICE_CODE_NOT_USED","detail":"x","data":null}"""

    @Test fun `start reply gives the code and the prefilled link`() {
        val d = TorboxAuth.parseDeviceCode(start)
        assertEquals("dc123", d.deviceCode)
        assertEquals("250684", d.userCode)
        assertEquals("https://torbox.app/oauth/device?app=CartelTV&code=250684", d.verificationUrl)
        assertEquals(5, d.intervalSec)
        assertEquals(600, d.expiresIn)
    }

    @Test fun `not used yet means keep polling`() {
        assertEquals(TorboxAuth.Poll.Pending, TorboxAuth.parsePoll(400, pending))
    }

    @Test fun `expired and redeemed codes fail with a message`() {
        val gone = TorboxAuth.parsePoll(404, """{"success":false,"error":"ITEM_NOT_FOUND","data":null}""")
        val used = TorboxAuth.parsePoll(400, """{"success":false,"error":"DEVICE_CODE_USED","data":null}""")
        assertTrue(gone is TorboxAuth.Poll.Failed && gone.message.contains("expired"))
        assertTrue(used is TorboxAuth.Poll.Failed && used.message.contains("already used"))
    }

    @Test fun `rate limits and server errors keep polling`() {
        assertEquals(TorboxAuth.Poll.Pending, TorboxAuth.parsePoll(429, """{"success":false,"error":"RATE_LIMIT_EXCEEDED"}"""))
        assertEquals(TorboxAuth.Poll.Pending, TorboxAuth.parsePoll(503, "oops"))
        assertEquals(TorboxAuth.Poll.Pending, TorboxAuth.parsePoll(-1, "timeout"))
    }

    @Test fun `success accepts the key as a string or an object field`() {
        assertEquals(TorboxAuth.Poll.Token("k1"), TorboxAuth.parsePoll(200, """{"success":true,"data":"k1"}"""))
        assertEquals(TorboxAuth.Poll.Token("k2"), TorboxAuth.parsePoll(200, """{"success":true,"data":{"token":"k2"}}"""))
        assertTrue(TorboxAuth.parsePoll(200, """{"success":true,"data":{"other":1}}""") is TorboxAuth.Poll.Failed)
    }
}
