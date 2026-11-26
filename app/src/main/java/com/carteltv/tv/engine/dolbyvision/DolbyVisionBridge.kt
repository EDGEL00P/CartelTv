package com.carteltv.tv.engine.dolbyvision

import android.util.Log
// BuildConfig shimmed for CartelTV
import java.util.concurrent.atomic.AtomicLong

object DolbyVisionBridge {
    private const val TAG = "DolbyVisionBridge"
    private const val LIB_NAME = "dovi_bridge"

    data class RealtimeConversionProbe(
        val supported: Boolean,
        val reason: String,
        val bridgeVersion: String?,
        val extractorHookReady: Boolean,
        val selfTest: SelfTestResult
    )

    data class SelfTestResult(
        val passed: Boolean,
        val reason: String,
        val inputBytes: Int,
        val outputBytes: Int
    )

    private val nativeLoaded: Boolean by lazy { loadNativeLibrary() }
    private var cachedSelfTestResult: SelfTestResult? = null
    private val conversionCallCount = AtomicLong(0L)
    private val conversionSuccessCount = AtomicLong(0L)

    val isNativeEnabledInBuild: Boolean
        get() = true

    val isExtractorHookReadyInBuild: Boolean
        get() = true

    val isLibraryLoaded: Boolean
        get() = nativeLoaded

    fun isAvailable(): Boolean = isNativeEnabledInBuild && nativeLoaded

    fun getBridgeVersionOrNull(): String? {
        if (!isAvailable()) return null
        return runCatching { nativeGetBridgeVersion() }
            .onFailure { Log.w(TAG, "Failed to read bridge version: ${it.message}") }
            .getOrNull()
    }

    fun convertDv7RpuToDv81(payload: ByteArray, mode: Int = 1): ByteArray? {
        if (!isAvailable() || payload.isEmpty()) return null
        conversionCallCount.incrementAndGet()
        val converted = runCatching { nativeConvertDv7RpuToDv81(payload, mode) }
            .onFailure { Log.w(TAG, "Conversion failed: ${it.message}") }
            .getOrNull()
        if (converted != null && converted.isNotEmpty()) {
            conversionSuccessCount.incrementAndGet()
        }
        return converted
    }

    /**
     * Output buffer for the non-allocating native paths. Each track output and transformer owns one and reads it on
     * the thread that made the call: the main player and a multi-view tile convert on different loader threads at
     * once, and a process-wide buffer would hand one of them the other's frame.
     */
    class Scratch(initialCapacity: Int = 4096) {
        /** The bytes the last call wrote. Valid until the next call on this instance. */
        internal var buffer = ByteArray(initialCapacity)
            private set

        internal fun grow(required: Int) {
            buffer = ByteArray(maxOf(required, buffer.size * 2))
        }
    }

    // FEL/MEL detection result codes.
    const val EL_TYPE_FEL = 2
    const val EL_TYPE_MEL = 1
    const val EL_TYPE_NONE = 0

    /**
     * Detects the profile-7 enhancement-layer type from an RPU NAL via
     * libdovi's pre-derived header field. Returns [EL_TYPE_FEL], [EL_TYPE_MEL],
     * [EL_TYPE_NONE] (parsed, not P7), -1 (parse failure) or -2 (bridge
     * unavailable). Intended to run once per stream on the first RPU.
     */
    fun detectRpuElType(sample: ByteArray, offset: Int, len: Int): Int {
        if (!isAvailable() || len <= 0) return -2
        return runCatching { nativeDetectRpuElType(sample, offset, len) }
            .onFailure { Log.w(TAG, "EL-type detection failed: ${it.message}") }
            .getOrDefault(-2)
    }

    /**
     * The DV RPU's static HDR mastering
     * metadata. Fields absent from the RPU are null (L6 is optional; the
     * source_*_pq pair is always present when DM metadata exists). Values are
     * raw as libdovi reports them: source_*_pq are 12-bit PQ codes; the L6
     * luminance and light-level values follow the ST2086/CTA-861.3 conventions.
     */
    data class RpuStaticMetadata(
        val sourceMinPq: Int,
        val sourceMaxPq: Int,
        val l6MinMasteringLuminance: Int?,
        val l6MaxMasteringLuminance: Int?,
        val maxCll: Int?,
        val maxFall: Int?
    ) {
        /**
         * A one-line human-readable summary for the Diagnostics card. MaxCLL /
         * MaxFALL are shown as libdovi reports them (nits); the mastering-display
         * peak is derived from [sourceMaxPq] via the ST 2084 (PQ) EOTF, which is
         * always available.
         */
        fun toDiagnosticLine(): String {
            if (maxCll == 0 && maxFall == 0) return "CLL/FALL not set · MDL ${pqCodeToNits(sourceMaxPq)} nits"
            val parts = ArrayList<String>(3)
            // A present L6 block with a zero content-light value means "unknown"
            // (common in WEB-DL masters), not literally zero nits - show a dash
            // rather than a misleading "0". A wholly absent L6 stays omitted.
            maxCll?.let { parts += "CLL ${if (it > 0) it.toString() else "-"}" }
            maxFall?.let { parts += "FALL ${if (it > 0) it.toString() else "-"}" }
            parts += "MDL ${pqCodeToNits(sourceMaxPq)} nits"
            return parts.joinToString(" · ")
        }

        private fun pqCodeToNits(pq12: Int): Int = Math.round(pq12ToNits(pq12)).toInt()
    }

    /** SMPTE ST 2084 (PQ) EOTF applied to a 12-bit RPU code value, in nits (cd/m²). */
    fun pq12ToNits(pq12: Int): Double {
        val e = pq12.coerceIn(0, 4095).toDouble() / 4095.0
        val m1 = 0.1593017578125
        val m2 = 78.84375
        val c1 = 0.8359375
        val c2 = 18.8515625
        val c3 = 18.6875
        val ep = Math.pow(e, 1.0 / m2)
        val num = (ep - c1).coerceAtLeast(0.0)
        val den = c2 - c3 * ep
        val l = if (den <= 0.0) 0.0 else Math.pow(num / den, 1.0 / m1)
        return l * 10000.0
    }

    /**
     * One RPU's display-management summary (see [getRpuDmInfo]). Absent values are null; [levels]
     * holds bit n for metadata level n (1..11), bit 12 for L254 and bit 13 for L255.
     */
    data class RpuDmInfo(
        /** 40 = CM v4.0 (L254 present), 29 = CM v2.9, null = no DM metadata in this RPU. */
        val cmVersion: Int?,
        val levels: Int,
        val l1MinPq: Int?,
        val l1MaxPq: Int?,
        val l1AvgPq: Int?,
        val l5Left: Int?,
        val l5Right: Int?,
        val l5Top: Int?,
        val l5Bottom: Int?,
        val guessedProfile: Int?,
        /** [EL_TYPE_FEL], [EL_TYPE_MEL], [EL_TYPE_NONE] or null. */
        val elType: Int?,
        val blBitDepth: Int?,
        val elBitDepth: Int?,
        /** True: the EL is coded at half the BL resolution (upsampled by the Dolby core). */
        val elHalfResolution: Boolean?,
        val level2Count: Int,
        val level8Count: Int,
        val level10Count: Int
    ) {
        fun hasLevel(level: Int): Boolean = when (level) {
            in 1..11 -> levels and (1 shl level) != 0
            254 -> levels and (1 shl 12) != 0
            255 -> levels and (1 shl 13) != 0
            else -> false
        }

    }

    @Volatile private var dmInfoUnavailable = false

    /**
     * Reads [RpuDmInfo] from an RPU NAL (type 62 incl. its 2-byte header, as for [detectRpuElType]).
     * Returns null on a stub build, when the bridge is unavailable or on parse failure. Cheap enough to run a few
     * times per second (one libdovi parse of a ~0.3-1 KB NAL).
     */
    fun getRpuDmInfo(sample: ByteArray, offset: Int, len: Int): RpuDmInfo? {
        if (offset < 0 || len <= 0 || len > 65_536 || offset > sample.size - len) return null
        if (!isAvailable() || len <= 0 || dmInfoUnavailable) return null
        val v = runCatching { nativeGetRpuDmInfo(sample, offset, len) }
            .onFailure {
                // A native library without this entry point (stale build): stop asking, log once.
                if (it is UnsatisfiedLinkError) dmInfoUnavailable = true
                Log.w(TAG, "RPU DM info read failed: ${it.message}")
            }
            .getOrNull() ?: return null
        if (v.size < 26) return null
        fun opt(i: Int): Int? = v[i].takeIf { it >= 0 }
        return RpuDmInfo(
            cmVersion = opt(0),
            levels = v[1].coerceAtLeast(0),
            l1MinPq = opt(2),
            l1MaxPq = opt(3),
            l1AvgPq = opt(4),
            l5Left = opt(5),
            l5Right = opt(6),
            l5Top = opt(7),
            l5Bottom = opt(8),
            guessedProfile = opt(15),
            elType = opt(16),
            blBitDepth = opt(17),
            elBitDepth = opt(18),
            elHalfResolution = opt(20)?.let { it == 1 },
            level2Count = v[21].coerceAtLeast(0),
            level8Count = v[22].coerceAtLeast(0),
            level10Count = v[23].coerceAtLeast(0)
        )
    }

    /**
     * Reads [RpuStaticMetadata] from an RPU NAL via the bundled libdovi 3.3.2
     * readers (no library bump). Returns null on a stub build, when the bridge
     * is unavailable, on parse failure, or when the RPU carries no DM metadata.
     * Intended to run once per stream on the first RPU, like [detectRpuElType].
     */
    fun getRpuStaticMetadata(sample: ByteArray, offset: Int, len: Int): RpuStaticMetadata? {
        if (!isAvailable() || len <= 0) return null
        val v = runCatching { nativeGetRpuStaticMetadata(sample, offset, len) }
            .onFailure { Log.w(TAG, "RPU metadata read failed: ${it.message}") }
            .getOrNull() ?: return null
        if (v.size < 6) return null
        fun opt(i: Int): Int? = v[i].takeIf { it >= 0 }
        return RpuStaticMetadata(
            sourceMinPq = v[0],
            sourceMaxPq = v[1],
            l6MinMasteringLuminance = opt(2),
            l6MaxMasteringLuminance = opt(3),
            maxCll = opt(4),
            maxFall = opt(5)
        )
    }

    /** Converts a DV7 RPU NAL to DV8.1 into [out] without per-call allocations; returns bytes written, or 0. */
    fun convertDv7RpuToDv81NonAllocating(
        sample: ByteArray,
        offset: Int,
        len: Int,
        mode: Int = 1,
        out: Scratch
    ): Int {
        if (!isAvailable() || len <= 0) return 0
        conversionCallCount.incrementAndGet()
        var written = runCatching {
            nativeConvertDv7RpuToDv81NonAllocating(sample, offset, len, out.buffer, mode)
        }.onFailure { Log.w(TAG, "Non-allocating conversion failed: ${it.message}") }
            .getOrDefault(0)
        if (written < 0) {
            // Output didn't fit: grow the reusable buffer to the required size and retry once.
            out.grow(-written)
            written = runCatching {
                nativeConvertDv7RpuToDv81NonAllocating(sample, offset, len, out.buffer, mode)
            }.onFailure { Log.w(TAG, "Non-allocating retry failed: ${it.message}") }
                .getOrDefault(0)
        }
        if (written > 0) {
            conversionSuccessCount.incrementAndGet()
        }
        return written
    }

    /**
     * Processes an HEVC video sample in native C++ layer.
     * Optionally converts or strips Dolby Vision RPUs, and strips HDR10+ SEIs.
     * Returns the size of the rewritten sample, or 0 if no changes were made.
     * If the output buffer was too small, grows the buffer and retries once.
     */
    fun processVideoSampleNonAllocating(
        sample: ByteArray,
        sampleLen: Int,
        nalFormat: Int, // 0 for Annex-B, 1 for Length-Delimited
        nalLengthFieldLength: Int,
        convertDovi: Boolean,
        doviMode: Int,
        doviProfile: Int,
        stripDoviRpu: Boolean,
        stripHdr10Plus: Boolean,
        out: Scratch
    ): Int {
        if (!isAvailable() || sampleLen <= 0) return 0

        var written = runCatching {
            nativeProcessVideoSample(
                sample = sample,
                sampleLen = sampleLen,
                nalFormat = nalFormat,
                nalLengthFieldLength = nalLengthFieldLength,
                outBuffer = out.buffer,
                convertDovi = convertDovi,
                doviMode = doviMode,
                doviProfile = doviProfile,
                stripDoviRpu = stripDoviRpu,
                stripHdr10Plus = stripHdr10Plus
            )
        }.onFailure { Log.w(TAG, "nativeProcessVideoSample failed: ${it.message}") }
            .getOrDefault(0)

        if (written < 0) {
            out.grow(-written)
            written = runCatching {
                nativeProcessVideoSample(
                    sample = sample,
                    sampleLen = sampleLen,
                    nalFormat = nalFormat,
                    nalLengthFieldLength = nalLengthFieldLength,
                    outBuffer = out.buffer,
                    convertDovi = convertDovi,
                    doviMode = doviMode,
                    doviProfile = doviProfile,
                    stripDoviRpu = stripDoviRpu,
                    stripHdr10Plus = stripHdr10Plus
                )
            }.onFailure { Log.w(TAG, "nativeProcessVideoSample retry failed: ${it.message}") }
                .getOrDefault(0)
        }
        return written
    }

    private fun loadNativeLibrary(): Boolean {
        if (!isNativeEnabledInBuild) {
            return false
        }
        return try {
            System.loadLibrary(LIB_NAME)
            Log.i(TAG, "Loaded native library: $LIB_NAME")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load native library $LIB_NAME: ${t.message}")
            false
        }
    }

    private fun String.safeHost(): String {
        return runCatching { android.net.Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")
    }

    @JvmStatic
    private external fun nativeGetBridgeVersion(): String

    @JvmStatic
    private external fun nativeIsConversionPathReady(): Boolean

    @JvmStatic
    private external fun nativeConvertDv7RpuToDv81(payload: ByteArray, mode: Int): ByteArray?

    private external fun nativeDetectRpuElType(sample: ByteArray, offset: Int, length: Int): Int

    // RPU static HDR metadata read (MDL + MaxCLL/MaxFALL). Returns
    // int[6] or null; see [getRpuStaticMetadata].
    private external fun nativeGetRpuStaticMetadata(
        sample: ByteArray,
        offset: Int,
        length: Int
    ): IntArray?

    // RPU display-management summary for the HUD. Returns int[26] or null; see [getRpuDmInfo].
    private external fun nativeGetRpuDmInfo(
        sample: ByteArray,
        offset: Int,
        length: Int
    ): IntArray?

    @JvmStatic
    private external fun nativeConvertDv7RpuToDv81NonAllocating(
        sample: ByteArray,
        offset: Int,
        len: Int,
        outBuffer: ByteArray,
        mode: Int
    ): Int

    @JvmStatic
    private external fun nativeProcessVideoSample(
        sample: ByteArray,
        sampleLen: Int,
        nalFormat: Int,
        nalLengthFieldLength: Int,
        outBuffer: ByteArray,
        convertDovi: Boolean,
        doviMode: Int,
        doviProfile: Int,
        stripDoviRpu: Boolean,
        stripHdr10Plus: Boolean
    ): Int
}
