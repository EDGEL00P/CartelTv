package com.carteltv.tv.data.xtream

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProviderSessionTest {
    @After fun clearSession() = ProviderSession.clear()

    @Test fun currentFlowTracksSessionReplacementAndClear() {
        val first = XtreamClient("http://provider-one.local", "user", "pass")
        val second = XtreamClient("http://provider-two.local", "user", "pass")

        ProviderSession.current = first
        assertSame(first, ProviderSession.currentFlow.value)

        ProviderSession.current = second
        assertSame(second, ProviderSession.currentFlow.value)

        ProviderSession.clear()
        assertNull(ProviderSession.currentFlow.value)
    }
}