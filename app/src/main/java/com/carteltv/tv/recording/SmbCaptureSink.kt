package com.carteltv.tv.recording

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.OutputStream

/**
 * SMB2 sink over SMBJ. One Connection/Session/Share
 * held for the capture's lifetime; free-space honored via the share's disk
 * info when the server reports it (failure ⇒ MAX_VALUE, governor skips).
 *
 * Auth: anonymous when no user in URL; NTLM domain context otherwise.
 */
class SmbCaptureSink(rawUrl: String) : CaptureSink {

    private val spec = SmbPaths.parse(rawUrl)
    override val displayPath: String = rawUrl
    private var client: SMBClient? = null
    private var conn: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var out: OutputStream? = null

    override fun bytesSoFar(): Long = written
    private var written = 0L

    private fun freeSpaceFromServer(): Long = try {
        // Verified smbj 0.14 API (javap DiskShare): FS_FULL_SIZE_INFO query.
        val info = share?.shareInformation
        val free = info?.callerFreeSpace ?: -1L
        if (free >= 0) free else Long.MAX_VALUE
    } catch (_: Exception) { Long.MAX_VALUE }

    override fun freeBytes(): Long = freeSpaceFromServer()

    override fun open(): OutputStream {
        val c = SMBClient().also { client = it }
        val connection = c.connect(spec.host, spec.port).also { conn = it }
        val ses = if (spec.user != null) {
            connection.authenticate(
                AuthenticationContext(spec.user, spec.pass?.toCharArray() ?: charArrayOf(), spec.domain ?: "")
            )
        } else {
            connection.authenticate(AuthenticationContext.anonymous())
        }.also { session = it }
        val sh = ses.connectShare(spec.share) as DiskShare
        share = sh
        // Create-or-overwrite; single stream for the whole capture.
        val f = sh.openFile(
            spec.path,
            setOf(com.hierynomus.msdtyp.AccessMask.GENERIC_WRITE),
            setOf(com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_NORMAL),
            com.hierynomus.mssmb2.SMB2ShareAccess.ALL,
            com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OVERWRITE_IF,
            emptySet(),
        )
        // Counting wrapper: governor reads written bytes without an extra FS query.
        val inner = f.outputStream
        return object : OutputStream() {
            override fun write(b: Int) { inner.write(b); written++ }
            override fun write(b: ByteArray) { inner.write(b); written += b.size }
            override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); written += len }
            override fun flush() = inner.flush()
            override fun close() = inner.close()
        }.also { out = it }
    }

    override fun close() {
        try { out?.close() } catch (_: Exception) {}
        try { share?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        share = null; session = null; conn = null; client = null
    }
}

/** Pure parser — unit-tested. smb://[DOMAIN;]user:pass@host[:port]/share/dir/file.ts */
object SmbPaths {

    /** One parsed smb:// endpoint: where the sink writes, and with which credentials. */
    data class SmbTarget(
        val host: String,
        val port: Int = 445,
        val domain: String? = null,
        val user: String? = null,
        val pass: String? = null,
        val share: String,
        /** Path INSIDE the share, forward-slashed, no leading slash. */
        val path: String,
    )

    fun parse(raw: String): SmbTarget {
        require(raw.startsWith("smb://")) { "not an smb URL: $raw" }
        var rest = raw.removePrefix("smb://")
        // strip optional fragment/query
        rest = rest.substringBefore('#').substringBefore('?')

        // authority = [userinfo@]host[:port]
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        val shareAndPath = if (slash < 0) "" else rest.substring(slash + 1)

        var userinfo: String? = null
        var hostPort: String = authority
        val at = authority.lastIndexOf('@')
        if (at >= 0) { userinfo = authority.substring(0, at); hostPort = authority.substring(at + 1) }

        var domain: String? = null
        var user: String? = null
        var pass: String? = null
        if (userinfo != null) {
            val colon = userinfo.indexOf(':')
            val idPart = if (colon < 0) userinfo else userinfo.substring(0, colon)
            if (colon >= 0) pass = userinfo.substring(colon + 1)
            val semi = idPart.indexOf(';')
            if (semi >= 0) { domain = idPart.substring(0, semi); user = idPart.substring(semi + 1) }
            else user = idPart
            if (user.isNullOrEmpty()) user = null
        }

        val host: String
        val port: Int
        val hpColon = hostPort.lastIndexOf(':')
        if (hpColon >= 0 && !hostPort.substring(hpColon + 1).any { !it.isDigit() }) {
            host = hostPort.substring(0, hpColon)
            port = hostPort.substring(hpColon + 1).toInt()
        } else { host = hostPort; port = 445 }

        require(host.isNotBlank()) { "no host in $raw" }
        val segments = shareAndPath.split('/').filter { it.isNotBlank() }
        require(segments.isNotEmpty()) { "no share in $raw" }
        val share = segments.first()
        val path = segments.drop(1).joinToString("\\")
        require(path.isNotBlank()) { "no file path in $raw" }
        return SmbTarget(host, port, domain, user, pass, share, path)
    }
}
