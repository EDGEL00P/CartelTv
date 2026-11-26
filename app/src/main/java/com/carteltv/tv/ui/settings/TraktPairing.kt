package com.carteltv.tv.ui.settings
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.trakt.TraktAuth
import com.carteltv.tv.data.trakt.TraktClient
import com.carteltv.tv.data.trakt.TraktHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class PairStage { NEED_CLIENT_ID, REQUESTING, AWAITING_CODE, LINKED }

data class PairState(
    val stage: PairStage,
    val device: TraktClient.DeviceCode? = null,
    val user: String? = null,
    val error: String? = null,
)

/** What the pairing flow needs from Trakt; production binds [TraktAccountPort], tests bind a fake. */
interface TraktPairingPort {
    suspend fun begin(): TraktClient.DeviceCode
    suspend fun poll(device: TraktClient.DeviceCode): TraktClient.Tokens?
    /** Persists the tokens and returns the linked account name, if known. */
    suspend fun complete(tokens: TraktClient.Tokens): String?
}

object TraktAccountPort : TraktPairingPort {
    private fun client() = TraktClient(AppPreferences.traktClientId, clientSecret = AppPreferences.traktClientSecret)

    override suspend fun begin() = client().beginPairing()
    override suspend fun poll(device: TraktClient.DeviceCode) = client().pollToken(device)
    override suspend fun complete(tokens: TraktClient.Tokens): String? {
        TraktAuth.completePairing(tokens)
        TraktAuth.refreshAccount()
        return TraktAuth.userName
    }
}

/** Device-code pairing state machine, free of Compose so the UI only renders [state]. */
class TraktPairing(
    initial: PairState,
    private val port: TraktPairingPort,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<PairState> = _state

    private fun pollFailure(code: Int) = when (code) {
        410 -> "The code has expired. Start again."
        418 -> "Trakt access was denied. Start again to retry."
        409 -> "That code was already used. Start again."
        else -> "Trakt did not recognise the code. Start again."
    }

    fun authorize() = _state.update { it.copy(stage = PairStage.REQUESTING, error = null) }

    fun cancel(hasToken: Boolean) = _state.update {
        it.copy(stage = if (hasToken) PairStage.LINKED else PairStage.NEED_CLIENT_ID)
    }

    suspend fun request() {
        try {
            val device = port.begin()
            _state.update { it.copy(device = device, stage = PairStage.AWAITING_CODE) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message, stage = PairStage.NEED_CLIENT_ID) }
        }
    }

    suspend fun awaitConfirmation() {
        val device = _state.value.device ?: return
        val expiresAt = now() + device.expiresIn * 1000L
        while (_state.value.stage == PairStage.AWAITING_CODE) {
            if (now() >= expiresAt) {
                _state.update { it.copy(error = "The code has expired. Start again.", stage = PairStage.NEED_CLIENT_ID) }
                return
            }
            pause(maxOf(device.intervalSec, 1) * 1000L)
            if (_state.value.stage != PairStage.AWAITING_CODE) return
            val tokens = try {
                port.poll(device)
            } catch (e: TraktHttpException) {
                if (e.code == 429) {
                    pause(maxOf(device.intervalSec, 1) * 1000L)
                    continue
                }
                _state.update { it.copy(error = pollFailure(e.code), stage = PairStage.NEED_CLIENT_ID) }
                return
            } ?: continue
            val user = port.complete(tokens)
            _state.update { it.copy(user = user, stage = PairStage.LINKED) }
        }
    }
}
