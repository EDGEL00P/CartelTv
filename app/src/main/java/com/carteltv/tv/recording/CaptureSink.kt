package com.carteltv.tv.recording

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Recording sink abstraction (q51 / og1 / h50 triangle):
 * a capture target that yields one write-stream plus governors
 * (size-so-far, free space). Two implementations, exactly as theirs:
 *  - [LocalCaptureSink] — plain device file (their h50)
 *  - [SmbCaptureSink]   — SMB2 share via SMBJ (their og1), with
 *    Signature fallback: network failure at open → caller
 *    swaps in the local sink and tells the user ("network folder
 *    failed, recording on this device").
 *
 * SMB URL grammar (parsed by [SmbPaths.parse], unit-tested):
 *   smb://[DOMAIN;]user:password@host[:port]/share/dir/file.ts
 */
interface CaptureSink {
    /** Human/display path (goes into the status sidecar + notifications). */
    val displayPath: String

    /** Bytes physically committed so far (per-sink counting for SMB). */
    fun bytesSoFar(): Long

    /** Usable bytes on the target volume; MAX_VALUE when unknowable (skip governor). */
    fun freeBytes(): Long

    /** Open (truncate-create) the sink; single stream for the whole capture. */
    fun open(): OutputStream

    /** Flush + close underlying streams and connections. Never throws. */
    fun close()
}

/** h50 equivalent. */
class LocalCaptureSink(private val file: File) : CaptureSink {
    private var out: FileOutputStream? = null
    override val displayPath: String get() = file.absolutePath
    override fun bytesSoFar(): Long = file.length()
    override fun freeBytes(): Long = file.parentFile?.usableSpace ?: 0L
    override fun open(): OutputStream =
        FileOutputStream(file, false).also { out = it }
    override fun close() {
        try { out?.flush() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        out = null
    }
}
