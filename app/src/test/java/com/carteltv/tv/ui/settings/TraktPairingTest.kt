package com.carteltv.tv.ui.settings

import com.carteltv.tv.data.trakt.TraktClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.carteltv.tv.data.trakt.TraktHttpException
import org.junit.Test

class TraktPairingTest {
    private val device = TraktClient.DeviceCode("dc", "ABCD", expiresIn = 60, intervalSec = 5)
    private val tokens = TraktClient.Tokens("a", "r")

    private class FakePort(
        val device: TraktClient.DeviceCode?,
        private val polls: MutableList<TraktClient.Tokens?>,
    ) : TraktPairingPort {
        var completed = 0
        override suspend fun begin() = device ?: throw IllegalStateException("Trakt HTTP 401")
        override suspend fun poll(device: TraktClient.DeviceCode) = polls.removeFirst()
        override suspend fun complete(tokens: TraktClient.Tokens): String? { completed++; return "matt" }
    }

    private fun flow(port: FakePort, clock: () -> Long = { 0L }, stage: PairStage = PairStage.REQUESTING) =
        TraktPairing(PairState(stage), port, clock) {}

    @Test fun `request success moves to awaiting with the device code`() = runBlocking {
        val f = flow(FakePort(device, mutableListOf()))
        f.request()
        assertEquals(PairStage.AWAITING_CODE, f.state.value.stage)
        assertEquals("ABCD", f.state.value.device?.userCode)
    }

    @Test fun `request failure returns to client-id entry with the message`() = runBlocking {
        val f = flow(FakePort(null, mutableListOf()))
        f.request()
        assertEquals(PairStage.NEED_CLIENT_ID, f.state.value.stage)
        assertEquals("Trakt HTTP 401", f.state.value.error)
    }

    @Test fun `polls until tokens arrive then links and records the user`() = runBlocking {
        val port = FakePort(device, mutableListOf(null, null, tokens))
        val f = flow(port)
        f.request(); f.awaitConfirmation()
        assertEquals(PairStage.LINKED, f.state.value.stage)
        assertEquals("matt", f.state.value.user)
        assertEquals(1, port.completed)
    }

    @Test fun `expired code returns to entry with an error and never completes`() = runBlocking {
        var t = 0L
        val port = FakePort(device, mutableListOf())
        val f = flow(port, { t.also { t += 61_000 } })
        f.request(); f.awaitConfirmation()
        assertEquals(PairStage.NEED_CLIENT_ID, f.state.value.stage)
        assertEquals("The code has expired. Start again.", f.state.value.error)
        assertEquals(0, port.completed)
    }

    private class ThrowingPort(val device: TraktClient.DeviceCode, val code: Int) : TraktPairingPort {
        var polls = 0
        override suspend fun begin() = device
        override suspend fun poll(device: TraktClient.DeviceCode): TraktClient.Tokens? {
            polls++
            throw TraktHttpException(code)
        }
        override suspend fun complete(tokens: TraktClient.Tokens): String? = null
    }

    @Test fun `denied expired or used codes stop polling with a message`() = runBlocking {
        for ((code, text) in mapOf(410 to "expired", 418 to "denied", 409 to "already used", 404 to "recognise")) {
            val port = ThrowingPort(device, code)
            val f = TraktPairing(PairState(PairStage.REQUESTING), port, { 0L }) {}
            f.request(); f.awaitConfirmation()
            assertEquals(PairStage.NEED_CLIENT_ID, f.state.value.stage)
            assertEquals(1, port.polls)
            assertTrue(code.toString(), f.state.value.error!!.contains(text))
        }
    }

    @Test fun `slow down keeps polling until the code expires`() = runBlocking {
        var t = 0L
        val port = ThrowingPort(device, 429)
        val f = TraktPairing(PairState(PairStage.REQUESTING), port, { t.also { t += 20_000 } }) {}
        f.request(); f.awaitConfirmation()
        assertEquals("The code has expired. Start again.", f.state.value.error)
        assertTrue(port.polls > 1)
    }

    @Test fun `cancel during the wait stops without polling`() = runBlocking {
        val port = FakePort(device, mutableListOf(tokens))
        lateinit var f: TraktPairing
        f = TraktPairing(PairState(PairStage.REQUESTING), port, { 0L }) { f.cancel(hasToken = false) }
        f.request(); f.awaitConfirmation()
        assertEquals(PairStage.NEED_CLIENT_ID, f.state.value.stage)
        assertEquals(0, port.completed)
    }

    @Test fun `authorize clears the previous error and cancel honours an existing token`() {
        val f = TraktPairing(PairState(PairStage.NEED_CLIENT_ID, error = "x"), FakePort(device, mutableListOf()))
        f.authorize()
        assertEquals(PairStage.REQUESTING, f.state.value.stage)
        assertNull(f.state.value.error)
        f.cancel(hasToken = true)
        assertEquals(PairStage.LINKED, f.state.value.stage)
    }
}
