package com.carteltv.tv.data.simkl

import org.junit.Assert.assertEquals
import org.junit.Test

class SimklHttpsUrlTest {
    @Test fun schemelessUriBecomesHttpsLink() {
        assertEquals("https://simkl.com/pin?user_code=BDWP-HQPK", SimklClient.httpsUrl("simkl.com/pin?user_code=BDWP-HQPK"))
    }

    @Test fun httpsUriUnchanged() {
        assertEquals("https://simkl.com/pin", SimklClient.httpsUrl("https://simkl.com/pin"))
    }

    @Test fun blankStaysBlank() {
        assertEquals("", SimklClient.httpsUrl("  "))
    }
}
