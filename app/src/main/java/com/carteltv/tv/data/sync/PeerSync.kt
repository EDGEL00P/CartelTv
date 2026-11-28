package com.carteltv.tv.data.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.carteltv.tv.AppScope
import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Multi-TV LAN Peer Sync.
 *
 * Keeps multiple CartelTV boxes aligned over the local network:
 *  - Main TV binds ports 9110–9119, advertises `_carteltv._tcp.` via NSD/mDNS, and opens a
 *    4-digit 5-minute pairing window (with brute-force lockout after 5 misses) that issues a
 *    24-char [SecureRandom] sync key.
 *  - Followers request snapshots filtered by user-selected sync groups (`look`, `layout`,
 *    `playback`, `addons`, `remote`).
 *  - Secrets (SecretVault credentials, API keys, PINs) and device-local hardware tuning are NEVER synced.
 */
object PeerSync {

    const val SERVICE_TYPE = "_carteltv._tcp."
    private const val PORT_START = 9110
    private const val PORT_END = 9119
    private const val MAX_PAIR_MISSES = 5

    val GROUPS: Map<String, String> = linkedMapOf(
        "look" to "Theme & Surface Density",
        "layout" to "Home Rows, Widgets & Clock",
        "playback" to "Buffer & YouTube Preferences",
        "addons" to "Stremio Addon URLs",
        "remote" to "Key Remap Bindings",
    )

    private val NEVER_SYNC_KEYS: Set<String> = setOf(
        "creds", "providers", "active_provider", "tk_tok", "tk_rfr", "rd_tok", "tb_tok", "tmdb_k", "yt_refresh",
        "simkl_cid", "simkl_tok", "simkl_rfr", "stremio_auth", "ha_tok", "hue_user", "doorbell_tok",
        "cameras_raw", "peer_key", "peer_role", "peer_host", "peer_port",
        "app_pins", "settings_pin_on", "sched_jobs", "auth_ledger", "assess_notes", "assessed",
        "dns_nd_profile",
    )

    /** Pure key-to-group classifier: returns null for secrets & device-local keys. */
    fun groupOf(key: String): String? = when {
        key in NEVER_SYNC_KEYS -> null
        key.startsWith("glass_") || key in setOf("wp_src", "wp_rot", "wp_blur", "wp_bright", "wp_subreddit", "icon_pack") -> "look"
        key in setOf("home_rows", "widget_belt", "board_on", "board_spec", "clock24", "cw_columns", "cw_hide_titles", "bar_clusters", "bar_pos", "bar_huf", "bar_tabs", "hidden_apps", "show_watch_next", "show_app_channels") -> "layout"
        key in setOf("buf", "dialogue_boost", "yt_sb", "yt_catpreset", "yt_quality", "play_in_streaming_app", "watch_country") -> "playback"
        key == "addons" -> "addons"
        key in setOf("remap_map", "override_home_button", "override_back_button") -> "remote"
        else -> null
    }

    private val rng = SecureRandom()
    @Volatile var pairCode: String = ""
        private set
    @Volatile private var pairExpiresAt: Long = 0L
    @Volatile private var pairMisses: Int = 0
    @Volatile var running: Boolean = false
        private set
    @Volatile var activePort: Int = 0
        private set

    private const val MAX_CONCURRENT = 8
    private const val MAX_REQUEST_LINE = 2048
    private const val REQUEST_DEADLINE_MS = 10_000L
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    fun ensureSyncKey(): String {
        val existing = AppPreferences.peerSyncKey
        if (existing.isNotBlank()) return existing
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val generated = (1..24).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
        AppPreferences.peerSyncKey = generated
        return generated
    }

    /** @Synchronized: writers must share verifyPairCode's lock or a verify landing between
     *  two field writes can accept a just-revoked (or half-written) code. */
    @Synchronized
    fun openPairing(windowMs: Long = 5 * 60_000L): String {
        pairMisses = 0
        pairCode = (1000 + rng.nextInt(9000)).toString()
        pairExpiresAt = System.currentTimeMillis() + windowMs
        return pairCode
    }

    @Synchronized
    fun closePairing() {
        pairExpiresAt = 0L
        pairCode = ""
    }

    /** Pure pairing code verification with expiry and brute-force lockout. */
    @Synchronized
    fun verifyPairCode(candidate: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (pairCode.isBlank() || nowMs > pairExpiresAt || pairMisses >= MAX_PAIR_MISSES) return false
        val ok = MessageDigest.isEqual(candidate.trim().toByteArray(), pairCode.toByteArray())
        if (!ok) {
            pairMisses++
            if (pairMisses >= MAX_PAIR_MISSES) closePairing()
        }
        return ok
    }

    /** Builds a filtered JSON snapshot of safe, non-secret preferences for the requested [groups]. */
    fun buildSnapshot(ctx: Context, groups: Set<String> = GROUPS.keys): JSONObject {
        val sp = ctx.getSharedPreferences("carteltv", Context.MODE_PRIVATE)
        val entries = JSONObject()
        for ((k, v) in sp.all) {
            val g = groupOf(k) ?: continue
            if (g !in groups) continue
            val wrapped = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "set").put("v", org.json.JSONArray(v.map { it.toString() }))
                else -> null
            } ?: continue
            entries.put(k, wrapped)
        }
        return JSONObject()
            .put("carteltv_sync", 1)
            .put("device", Build.MODEL)
            .put("at", System.currentTimeMillis())
            .put("prefs", entries)
    }

    fun startMainServer(ctx: Context): Int? {
        synchronized(this) {
            if (running) return activePort
            // Listen on the LAN address only (twin of PhoneCompanionServer.start): the
            // main-role server runs permanently and advertises via NSD, so binding all
            // interfaces would also expose /pair on VPN and hotspot interfaces.
            val ip = com.carteltv.tv.companion.PhoneCompanionServer.localIpv4(ctx).ifBlank { return null }
            val key = ensureSyncKey()
            for (port in PORT_START..PORT_END) {
                val s = runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(java.net.InetAddress.getByName(ip), port), 8)
                    }
                }.getOrNull() ?: continue
                serverSocket = s
                activePort = port
                running = true
                val appCtx = ctx.applicationContext
                registerMdns(appCtx, port)
                AppScope.io.launch {
                    while (running) {
                        val client = runCatching { s.accept() }.getOrNull() ?: break
                        if (inFlight.incrementAndGet() > MAX_CONCURRENT) {
                            inFlight.decrementAndGet()
                            runCatching { client.close() }
                            continue
                        }
                        AppScope.io.launch {
                            try { handleClient(appCtx, client, key) } finally { inFlight.decrementAndGet() }
                        }
                    }
                }
                return port
            }
            return null
        }
    }

    fun stopMainServer() {
        synchronized(this) {
            running = false
            activePort = 0
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
        nsdListener?.let { l -> runCatching { nsdManager?.unregisterService(l) } }
        nsdListener = null
    }

    /** First request line, bounded in size and time so an unauthenticated LAN peer cannot pin memory or a worker thread. */
    private fun readRequestLine(inp: java.io.InputStream): String? {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + REQUEST_DEADLINE_MS
        while (true) {
            if (sb.length >= MAX_REQUEST_LINE || System.currentTimeMillis() > deadline) return null
            val b = inp.read()
            if (b < 0 || b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString().trimEnd('\r').takeIf { it.isNotEmpty() }
    }

    private fun handleClient(ctx: Context, client: Socket, expectedKey: String) {
        runCatching {
            client.soTimeout = 6_000
            client.use { s ->
                val line = readRequestLine(s.getInputStream().buffered()) ?: return@use
                val target = line.split(' ').getOrNull(1).orEmpty()
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "")
                fun param(k: String) = query.split('&').firstOrNull { it.startsWith("$k=") }?.substringAfter('=').orEmpty()
                var macHeader: String? = null
                val (code, body) = when (path) {
                    "/pair" -> {
                        if (verifyPairCode(param("code"))) {
                            200 to JSONObject().put("ok", true).put("key", expectedKey).put("device", Build.MODEL).toString()
                        } else {
                            403 to """{"ok":false,"error":"invalid_code"}"""
                        }
                    }
                    "/sync" -> {
                        val nonce = param("n")
                        val ok = NONCE_SHAPE.matches(nonce) &&
                            constantTimeEquals(param("p"), hmacHex(expectedKey, "req:$nonce")) &&
                            claimNonce(nonce)
                        if (ok) {
                            val snapshot = buildSnapshot(ctx).toString()
                            macHeader = hmacHex(expectedKey, "res:$nonce:$snapshot")
                            200 to snapshot
                        } else 403 to """{"ok":false,"error":"unauthorized"}"""
                    }
                    else -> 404 to """{"ok":false}"""
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                s.getOutputStream().apply {
                    val macLine = macHeader?.let { "X-Cartel-Mac: $it\r\n" }.orEmpty()
                    write("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n${macLine}Connection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
    }

    private val NONCE_SHAPE = Regex("[0-9a-f]{32}")
    private val seenNonces = object : LinkedHashMap<String, Unit>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 256
    }

    /** One-shot nonces: a sniffed request cannot be replayed to pull the snapshot again. */
    private fun claimNonce(nonce: String): Boolean = synchronized(seenNonces) {
        if (seenNonces.containsKey(nonce)) false else { seenNonces[nonce] = Unit; true }
    }

    private fun hmacHex(key: String, message: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun registerMdns(ctx: Context, port: Int) {
        runCatching {
            val mgr = ctx.getSystemService(NsdManager::class.java) ?: return
            nsdManager = mgr
            val info = NsdServiceInfo().apply {
                serviceName = "CartelTV-${Build.MODEL}"
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
            }
            nsdListener = l
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        }
    }
}
