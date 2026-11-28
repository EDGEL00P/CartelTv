package com.carteltv.tv.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Free keyless AI answers over OpenAI-compatible chat completions, no API key or account.
 * Two independent keyless providers are tried in order so one rate limit or outage doesn't
 * take the feature down on any box; anonymous tiers are rate limited per IP, so [ask]
 * returns null when both fail and callers fall back to their normal path.
 */
internal object AiAnswerFormatter {

    private const val TIMEOUT_MS = 15_000

    // Both endpoints accept fully anonymous requests: no key, no token, no header.
    // llm7 models must stay in its free "turbo" tier — anything else answers pro_access_required.
    private val providers = listOf(
        Provider("https://text.pollinations.ai/openai", "openai"), // anonymous tier: gpt-oss-20b
        Provider("https://api.llm7.io/v1/chat/completions", "DeepSeek-V4-Flash-0731"),
        Provider("https://api.llm7.io/v1/chat/completions", "gpt-oss:20b"),
    )

    private data class Provider(val url: String, val model: String)

    private const val SYSTEM_PROMPT =
        "You are Cartel, the voice assistant of a smart TV launcher. " +
            "Answer in one or two short sentences, plain text only — no markdown, no lists. " +
            "The answer is shown on a TV overlay and may be read aloud."

    /** Asks the AI [question]; null when every provider is unreachable, rate limited or returned nothing. */
    suspend fun ask(question: String): String? = withContext(Dispatchers.IO) {
        for (provider in providers) {
            val answer = runCatching { post(provider, question) }.getOrNull()
            if (answer != null) return@withContext answer
        }
        null
    }

    private fun post(provider: Provider, question: String): String? {
        val body = buildString {
            append("{\"model\":\"").append(provider.model).append("\",\"messages\":[")
            append("{\"role\":\"system\",\"content\":\"").append(escape(SYSTEM_PROMPT)).append("\"},")
            append("{\"role\":\"user\",\"content\":\"").append(escape(question)).append("\"}]}")
        }
        val conn = (URL(provider.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return parseContent(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Pulls choices[0].message.content without pulling in a JSON dependency. */
    private fun parseContent(json: String): String? {
        val key = "\"content\":\""
        val start = json.indexOf(key)
        if (start < 0) return null
        val sb = StringBuilder()
        var i = start + key.length
        while (i < json.length) {
            val c = json[i]
            when {
                c == '"' && json[i - 1] != '\\' -> return sb.toString().ifBlank { null }
                c == '\\' && i + 1 < json.length -> {
                    when (val next = json[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        else -> sb.append(next)
                    }
                    i++
                }
                else -> sb.append(c)
            }
            i++
        }
        return null
    }

    private fun escape(raw: String): String = buildString {
        for (c in raw) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
