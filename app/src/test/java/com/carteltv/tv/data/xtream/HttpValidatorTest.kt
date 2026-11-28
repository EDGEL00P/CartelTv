package com.carteltv.tv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HttpValidatorTest {
    @Test fun `etag wins over last-modified`() {
        assertEquals("E:\"abc\"", ProviderUrlValidator.of("\"abc\"", "Mon, 01 Jan 2024 00:00:00 GMT"))
        assertEquals("M:Mon, 01 Jan 2024 00:00:00 GMT", ProviderUrlValidator.of(null, "Mon, 01 Jan 2024 00:00:00 GMT"))
        assertEquals("", ProviderUrlValidator.of(null, " "))
    }

    @Test fun `request header matches the stored kind`() {
        assertEquals("If-None-Match" to "\"abc\"", ProviderUrlValidator.requestHeader("E:\"abc\""))
        assertEquals("If-Modified-Since" to "x", ProviderUrlValidator.requestHeader("M:x"))
        assertNull(ProviderUrlValidator.requestHeader(""))
    }

    @Test fun `limited stream passes data up to the limit and fails past it`() {
        val ok = LimitedInputStream(ByteArray(10).inputStream(), 10)
        assertEquals(10, ok.readBytes().size)
        try {
            LimitedInputStream(ByteArray(11).inputStream(), 10).readBytes()
            fail("expected the limit to trip")
        } catch (e: IOException) {
            assertEquals(true, e.message!!.contains("too large"))
        }
    }
}
