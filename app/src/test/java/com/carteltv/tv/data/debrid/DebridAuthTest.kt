package com.carteltv.tv.data.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DebridAuthTest {
    @Test fun parsesDeviceCode() {
        val d = DebridAuth.parseDeviceCode(
            """{"device_code":"dev123","user_code":"ABCDEF","interval":5,"expires_in":600,"verification_url":"https://real-debrid.com/device"}""",
        )
        assertEquals("dev123", d.deviceCode)
        assertEquals("ABCDEF", d.userCode)
        assertEquals(5, d.intervalSec)
        assertEquals(600, d.expiresIn)
    }

    @Test fun fallsBackToDefaultPageAndClampsInterval() {
        val d = DebridAuth.parseDeviceCode("""{"device_code":"d","user_code":"U","interval":0}""")
        assertEquals("https://real-debrid.com/device", d.verificationUrl)
        assertEquals(1, d.intervalSec)
    }

    @Test fun rejectsIncompleteCode() {
        assertThrows(IllegalArgumentException::class.java) { DebridAuth.parseDeviceCode("""{"user_code":"U"}""") }
    }
}
