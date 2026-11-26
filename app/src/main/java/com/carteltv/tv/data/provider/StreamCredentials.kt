package com.carteltv.tv.data.provider

/** Xtream URLs embed username/password; persisted history keeps placeholders instead. */
internal object StreamCredentials {
    const val USER = "%CRED_USER%"
    const val PASS = "%CRED_PASS%"
    private val pattern = Regex("""(https?://[^\s|]*?)/(live|movie|series)/[^/|\s]+/[^/|\s]+/""")

    fun redact(text: String): String =
        pattern.replace(text) { "${it.groupValues[1]}/${it.groupValues[2]}/$USER/$PASS/" }

    fun restore(url: String, user: String, pass: String): String =
        url.replace(USER, user).replace(PASS, pass)
}
