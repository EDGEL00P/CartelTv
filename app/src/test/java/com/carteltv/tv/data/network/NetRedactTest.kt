package com.carteltv.tv.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NetRedactTest {
    @Test
    fun hides_credentials_in_http_error_messages() {
        val msg = "Server returned HTTP response code: 401 for URL: http://h:8080/player_api.php?username=bob&password=s3cret&action="
        val out = HttpSupport.redact(msg)
        assertFalse(out.contains("s3cret"))
        assertEquals(
            "Server returned HTTP response code: 401 for URL: http://h:8080/player_api.php?username=bob&password=[hidden]&action=",
            out,
        )
    }

    @Test
    fun hides_api_key_and_token_and_leaves_plain_text() {
        assertEquals("x?api_key=[hidden]&q=1", HttpSupport.redact("x?api_key=abc&q=1"))
        assertEquals("a?b=1&token=[hidden]", HttpSupport.redact("a?b=1&token=zzz"))
        assertEquals("Unable to resolve host", HttpSupport.redact("Unable to resolve host"))
        assertEquals("", HttpSupport.redact(null))
    }
}
