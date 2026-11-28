package com.carteltv.tv.engine.stability

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/** Why a stream failed to open, as far as the response lets us tell. */
enum class PlaybackFailure {
    /** Provider refused because the account has no free connection slot. */
    SessionLimit,
    Other;

    companion object {
        /** 458 is the Xtream "account in use" lockout; 429 is the portal's rate limit. */
        fun fromHttpCode(code: Int?): PlaybackFailure =
            if (code == 458 || code == 429) SessionLimit else Other

        /** Connection-level drops worth retrying; HTTP and decode errors are not. */
        fun isTransientNetwork(errorCode: Int): Boolean =
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

        /** The HTTP status the server answered with, when the failure carries one. */
        fun httpCodeOf(error: Throwable): Int? {
            var t: Throwable? = error
            while (t != null) {
                if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
                t = t.cause
            }
            return null
        }

        /** What to tell the viewer. The raw error name stays available as a small detail line. */
        fun userMessage(errorCode: Int, httpCode: Int? = null): String = when {
            httpCode == 404 || errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "This channel is not available from your provider right now."
            httpCode == 401 || httpCode == 403 ->
                "Your provider refused the sign-in. Check your account in Settings."
            isTransientNetwork(errorCode) ->
                "Could not reach the stream. Check your network connection."
            errorCode in 3000..4999 ->
                "This video format could not be played on this device."
            else -> "The stream could not be played."
        }

        fun userMessage(error: PlaybackException): String = userMessage(error.errorCode, httpCodeOf(error))

        fun of(error: Throwable): PlaybackFailure {
            var t: Throwable? = error
            while (t != null) {
                if (t is HttpDataSource.InvalidResponseCodeException) return fromHttpCode(t.responseCode)
                t = t.cause
            }
            return Other
        }
    }
}
