package com.carteltv.tv.companion

import com.carteltv.tv.data.network.isHttpUrl

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.carteltv.tv.AppScope
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.setup.SetupCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zero-dependency LAN Phone Companion web server.
 *
 * Strictly opt-in: runs ONLY while opened in Settings → Configuration Transfer, binds ports
 * 9090–9099, authenticates every request with a constant-time one-time token (`?t=...`),
 * keeps secrets strictly write-only (never echoed back), and lets the operator paste API keys
 * (TMDB, Real-Debrid, TorBox, Simkl), add Stremio addons, or export/restore config codes from a phone.
 */
object PhoneCompanionServer {

    private const val PORT_START = 9090
    private const val PORT_END = 9099
    private const val MAX_CONCURRENT = 6
    private const val MAX_BODY_BYTES = 256 * 1024
    private const val MAX_HEADERS = 64
    private const val READ_DEADLINE_MS = 10_000L
    private const val REQUEST_DEADLINE_MS = 45_000L

    /** At most 30 requests a second per phone, as the phone remote's held-key repeat expects. */
    internal const val RATE_LIMIT = 30
    internal const val RATE_WINDOW_MS = 1_000L
    internal const val MAX_TOKEN_FAILS = 10
    internal const val LOCKOUT_MS = 5 * 60_000L

    /** A session the TV started lives this long; after it, the phone must be re-invited. */
    internal const val SESSION_TTL_MS = 4 * 60 * 60 * 1000L

    @Volatile var running: Boolean = false
        private set
    @Volatile var activePort: Int = 0
        private set
    @Volatile var sessionToken: String = ""
        private set

    @Volatile private var expiresAtMs = 0L
    private var serverSocket: ServerSocket? = null
    private val inFlight = AtomicInteger(0)
    private val rng = SecureRandom()
    private val guard = ClientGuard(RATE_LIMIT, RATE_WINDOW_MS, MAX_TOKEN_FAILS, LOCKOUT_MS)

    // start/stop share one lock: two concurrent starts would each bind a socket and
    // each launch an accept loop, clobbering the token the first one handed out.
    private val lifecycleLock = Any()

    internal fun isSessionExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        running && nowMs >= expiresAtMs

    fun generateToken(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        return (1..20).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
    }

    /** Constant-time query token comparison. */
    fun isAuthorized(provided: String, expected: String): Boolean {
        if (provided.isBlank() || expected.isBlank()) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * A request is served only if its Host header names this TV (its LAN address or loopback). A page on another
     * origin that rebinds its DNS name to the TV's address still sends its own name, so it is turned away.
     */
    internal fun isAllowedHost(hostHeader: String, localIp: String): Boolean {
        val raw = hostHeader.trim()
        if (raw.isEmpty()) return false
        val host = if (raw.startsWith("[")) raw.substringBefore(']').removePrefix("[") else raw.substringBefore(':')
        return host.equals("localhost", true) || host == "127.0.0.1" || (localIp.isNotBlank() && host == localIp)
    }

    fun localIpv4(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm?.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address }
            ?.hostAddress
    }.getOrNull().orEmpty()

    fun activeUrl(ctx: Context): String? {
        if (!running || activePort == 0 || sessionToken.isBlank()) return null
        val ip = localIpv4(ctx).ifBlank { return null }
        return "http://$ip:$activePort/?t=$sessionToken"
    }

    fun start(ctx: Context): Int? = synchronized(lifecycleLock) {
        if (running) return activePort
        // Listen on the LAN address only, so a VPN or hotspot interface on the same box is not exposed.
        val ip = localIpv4(ctx).ifBlank { return null }
        val token = generateToken()
        for (port in PORT_START..PORT_END) {
            val s = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(ip), port), 8)
                }
            }.getOrNull() ?: continue
            serverSocket = s
            activePort = port
            sessionToken = token
            expiresAtMs = System.currentTimeMillis() + SESSION_TTL_MS
            running = true
            val appCtx = ctx.applicationContext
            AppScope.io.launch { acceptLoop(appCtx, s, token) }
            return port
        }
        return null
    }

    fun stop() {
        synchronized(lifecycleLock) {
            running = false
            activePort = 0
            sessionToken = ""
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    private fun acceptLoop(ctx: Context, socket: ServerSocket, token: String) {
        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            if (inFlight.incrementAndGet() > MAX_CONCURRENT) {
                inFlight.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            AppScope.io.launch {
                // soTimeout only bounds a single read; this caps the whole exchange so trickled bytes cannot hold a slot.
                val watchdog = AppScope.io.launch {
                    delay(REQUEST_DEADLINE_MS)
                    runCatching { client.close() }
                }
                try {
                    client.soTimeout = 15_000
                    handle(ctx, client, token)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    watchdog.cancel()
                    runCatching { client.close() }
                    inFlight.decrementAndGet()
                }
            }
        }
    }

    private fun readLine(inp: InputStream, deadline: Long): String? {
        val out = ByteArrayOutputStream()
        while (out.size() < 8192) {
            if (System.currentTimeMillis() > deadline) return null
            val b = inp.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            out.write(b)
        }
        return null
    }

    private fun queryParam(query: String, key: String): String =
        query.split('&')
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            .orEmpty()

    private fun handle(ctx: Context, client: Socket, token: String) {
        val clientId = client.inetAddress?.hostAddress ?: "unknown"
        if (!guard.allow(clientId)) {
            respond(client, 429, "text/plain", "Too many requests")
            return
        }
        val inp = client.getInputStream().buffered()
        val deadline = System.currentTimeMillis() + READ_DEADLINE_MS
        val requestLine = readLine(inp, deadline) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]
        var contentLength = 0
        var headerToken = ""
        var hostHeader = ""
        var headerCount = 0
        while (true) {
            val line = readLine(inp, deadline) ?: return
            if (line.isEmpty()) break
            if (++headerCount > MAX_HEADERS) return
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            if (name.equals("content-length", true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
            } else if (name.equals("x-cartel-token", true)) {
                headerToken = line.substring(colon + 1).trim()
            } else if (name.equals("host", true)) {
                hostHeader = line.substring(colon + 1).trim()
            }
        }
        if (!isAllowedHost(hostHeader, localIpv4(ctx))) {
            respond(client, 403, "text/plain", "Forbidden")
            return
        }
        if (isSessionExpired()) {
            stop()
            respond(client, 403, "text/plain", "Session expired")
            return
        }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val provided = headerToken.ifBlank { queryParam(query, "t") }
        if (!isAuthorized(provided, token)) {
            guard.fail(clientId)
            respond(client, 403, "text/plain", "Forbidden")
            return
        }

        if (method == "GET") {
            when (path) {
                "/" -> respond(client, 200, "text/html; charset=utf-8", COMPANION_HTML, securePageHeaders)
                "/api/state" -> respond(client, 200, "application/json", stateJson().toString())
                "/api/export" -> {
                    val code = SetupCodec.encode(SetupCodec.capture())
                    respond(client, 200, "application/json", JSONObject().put("code", code).toString())
                }
                else -> respond(client, 404, "text/plain", "Not found")
            }
            return
        }

        if (method != "POST" || contentLength !in 0..MAX_BODY_BYTES) {
            respond(client, 400, "application/json", """{"ok":false,"error":"Invalid request"}""")
            return
        }
        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            if (System.currentTimeMillis() > deadline) return
            val n = inp.read(buf, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        val json = runCatching { JSONObject(String(buf, 0, read, Charsets.UTF_8)) }.getOrDefault(JSONObject())
        val result = runCatching { handlePost(ctx, path, json) }
        val payload = result.fold(
            onSuccess = { msg -> JSONObject().put("ok", true).put("message", msg).toString() },
            onFailure = { err -> JSONObject().put("ok", false).put("error", err.message ?: "Failed").toString() },
        )
        respond(client, 200, "application/json", payload)
    }

    private fun stateJson(): JSONObject = JSONObject()
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("tmdbSet", AppPreferences.tmdbKey.isNotBlank())
        .put("debridSet", AppPreferences.debridToken.isNotBlank())
        .put("torboxSet", AppPreferences.torboxToken.isNotBlank())
        .put("simklSet", AppPreferences.simklClientId.isNotBlank())
        .put("traktSet", AppPreferences.traktClientId.isNotBlank())
        .put("xtreamSet", AppPreferences.credentials != null)
        .put("addons", JSONArray(AppPreferences.addons()))
        .put("watchCountry", AppPreferences.watchCountry)

    private fun handlePost(ctx: Context, path: String, j: JSONObject): String = when (path) {
        "/api/keys" -> {
            var count = 0
            j.optString("tmdb").trim().takeIf { it.isNotBlank() }?.let { AppPreferences.tmdbKey = it; count++ }
            j.optString("debrid").trim().takeIf { it.isNotBlank() }?.let { AppPreferences.debridToken = it; count++ }
            j.optString("torbox").trim().takeIf { it.isNotBlank() }?.let { AppPreferences.torboxToken = it; count++ }
            j.optString("simklClientId").trim().takeIf { it.isNotBlank() }?.let { AppPreferences.simklClientId = it; count++ }
            j.optString("traktClientId").trim().takeIf { it.isNotBlank() }?.let { AppPreferences.traktClientId = it; count++ }
            j.optString("traktClientSecret").trim().takeIf { it.isNotBlank() }?.let { AppPreferences.traktClientSecret = it; count++ }
            require(count > 0) { "No keys provided" }
            "Saved $count vaulted key(s)"
        }
        "/api/xtream" -> {
            val host = j.optString("host").trim()
            val user = j.optString("user").trim()
            val pass = j.optString("pass").trim()
            require(host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) { "Host, user, and password are required" }
            com.carteltv.tv.data.preferences.AppPreferences.saveCredentials(com.carteltv.tv.data.preferences.Credentials(host, user, pass))
            "Xtream credentials saved"
        }
        "/api/provider" -> {
            val typeRaw = j.optString("type").trim().ifBlank { "xtream" }
            val type = com.carteltv.tv.data.provider.ProviderType.of(typeRaw)
            val host = j.optString("host").trim()
            require(host.isNotBlank()) { "Host/URL is required" }
            val user = j.optString("user").trim()
            val pass = j.optString("pass").trim()
            when (type) {
                com.carteltv.tv.data.provider.ProviderType.XTREAM -> {
                    require(user.isNotBlank() && pass.isNotBlank()) { "User and password are required" }
                }
                com.carteltv.tv.data.provider.ProviderType.STALKER -> {
                    require(user.isNotBlank()) { "MAC address is required" }
                }
                com.carteltv.tv.data.provider.ProviderType.M3U -> { /* host only */ }
            }
            // Build a provider directly preserving the kind (not via saveCredentials which forces XTREAM for new).
            val cleanHost = if (type == com.carteltv.tv.data.provider.ProviderType.XTREAM)
                com.carteltv.tv.data.xtream.ProviderSession.normalizeHost(host) else host.trim()
            val provider = com.carteltv.tv.data.provider.Provider(
                id = com.carteltv.tv.data.provider.Provider.newId(),
                name = com.carteltv.tv.data.provider.Provider.hostLabel(cleanHost),
                host = cleanHost,
                user = if (type == com.carteltv.tv.data.provider.ProviderType.M3U) "" else (if (type == com.carteltv.tv.data.provider.ProviderType.STALKER) user.uppercase() else user),
                pass = if (type == com.carteltv.tv.data.provider.ProviderType.XTREAM) pass else "",
                type = type.name,
            )
            com.carteltv.tv.data.preferences.AppPreferences.upsertProvider(provider)
            com.carteltv.tv.data.preferences.AppPreferences.activeProviderId = provider.id
            com.carteltv.tv.data.xtream.ProviderSession.reload()
            "${type.label} provider saved"
        }
        "/api/trakt" -> {
            var count = 0
            j.optString("clientId").trim().takeIf { it.isNotBlank() }?.let { AppPreferences.traktClientId = it; count++ }
            j.optString("clientSecret").trim().takeIf { it.isNotBlank() }?.let { AppPreferences.traktClientSecret = it; count++ }
            require(count > 0) { "No Trakt credentials provided" }
            "Saved $count Trakt credential(s)"
        }
        "/api/addon/add" -> {
            val url = j.optString("url").trim()
            require(url.isHttpUrl()) { "Addon URL must start with http" }
            if (AppPreferences.addAddon(url)) "Addon added" else "Addon already installed"
        }
        "/api/import" -> {
            val code = j.optString("code").trim()
            require(code.isNotBlank()) { "Configuration code is empty" }
            SetupCodec.apply(SetupCodec.decode(code))
            "Configuration imported"
        }
        "/api/type" -> {
            val n = PhoneKeys.type(j.optString("text"), j.optInt("del", 0))
            "Typed $n"
        }
        "/api/key" -> {
            PhoneKeys.key(j.optString("key"), j.optString("action", "press"))
            "OK"
        }
        "/api/voice" -> {
            val text = j.optString("text").trim().take(300)
            require(text.isNotBlank()) { "Say or type a command" }
            // This runs on a dedicated IO worker; the timeout stops a hung recognizer from holding one of the few slots.
            kotlinx.coroutines.runBlocking { // bughunt:ignore kt-run-blocking
                kotlinx.coroutines.withTimeout(20_000) { com.carteltv.tv.voice.VoicePipeline.submit(ctx, text) }
            }
        }
        "/api/prefs" -> {
            if (j.has("watchCountry")) AppPreferences.watchCountry = j.optString("watchCountry")
            "Preferences updated"
        }
        else -> error("Unknown endpoint")
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        else -> "Error"
    }

    /**
     * The page may run its own inline script and styles and talk to this server, and nothing
     * else; no other site may frame it.
     */
    private val securePageHeaders =
        "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'\r\n" +
            "X-Frame-Options: DENY\r\n"

    private fun respond(client: Socket, status: Int, contentType: String, body: String, extraHeaders: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status ${reason(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            extraHeaders +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

}
