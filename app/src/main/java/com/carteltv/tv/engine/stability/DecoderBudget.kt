package com.carteltv.tv.engine.stability

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.carteltv.tv.data.preferences.AppPreferences

/**
 * Pure hardware-decoder + RAM concurrent-stream budgeter (Reseq `MultiviewDevicePolicy` doctrine):
 * - Excludes software decoders (`OMX.google.*`, `c2.android.*`, API 29+ `isSoftwareOnly`) so
 *   software fallback counts never inflate a TV's real hardware budget.
 * - Reserves 1 hardware decoder instance for system/UI compositing (`min(avc, hevc) - 1`).
 * - Caps by physical RAM / app heap tier (2 on 2 GB sticks, 3 on 3 GB mid-range boxes like
 *   Onn 4K Pro, 4 on 4 GB+ boxes like Shield Pro).
 */
object DecoderBudget {
    fun isSoftwareCodecName(name: String): Boolean =
        name.startsWith("OMX.google.", ignoreCase = true) ||
            name.startsWith("c2.android.", ignoreCase = true)

    fun computeMaxStreams(
        avcInstances: Int?,
        hevcInstances: Int?,
        heapMb: Long,
        lowRam: Boolean,
        physicalRamMb: Long,
    ): Int {
        val decoders = minOf(
            avcInstances?.takeIf { it > 0 } ?: 2,
            hevcInstances?.takeIf { it > 0 } ?: 2,
        )
        val memoryCap = when {
            // RAM that was not reported cannot be assumed roomy, so it counts as the smallest tier.
            lowRam || heapMb < 384L || physicalRamMb !in 2305L..Long.MAX_VALUE -> 2
            heapMb < 1024L || physicalRamMb in 2305L..3584L -> 3
            else -> 4
        }
        // A box with a single decoder still plays one stream; the reserve needs a spare instance to give.
        return minOf(4, (decoders - 1).coerceAtLeast(1), memoryCap)
    }
}

/**
 * Device Assessment (capability-probe → buffer-preset + hardware concurrent-stream budget,
 * extended with per-sink audio-probe doctrine):
 * probes real hardware decoders (never encoders, never software-inflated counts), the routed
 * sink's passthrough codecs, disk and RAM, then recommends a buffer preset and stream budget.
 */
object DeviceBudget {

    data class Verdict(
        val ramMb: Long,
        val ramClass: String,
        val hdHw: Boolean,
        val max4k: Boolean,
        val av1Hw: Boolean,
        val av1Sw: Boolean,
        val audioSummary: String,
        val freeDiskGb: Double,
        val recommendedPreset: AppPreferences.BufferPreset,
        val notes: List<String>,
        val hwAvcInstances: Int? = null,
        val hwHevcInstances: Int? = null,
        val maxConcurrentStreams: Int = 1,
    ) {
        /** Multi-line text persisted for the Settings card. */
        val summary: String
            get() = (listOf(
                "RAM: $ramClass (${ramMb / 1024L} GB)",
                "VIDEO: ${listOfNotNull(
                    "HEVC 4K ${tick(max4k)}",
                    "AVC HD ${tick(hdHw)}",
                    if (av1Hw || av1Sw) "AV1 ${if (av1Hw) "hw" else "sw"} ✓" else "AV1 ✗",
                ).joinToString(" · ")}",
                "STREAMS: up to $maxConcurrentStreams concurrent (HW AVC=${hwAvcInstances ?: "?"} · HEVC=${hwHevcInstances ?: "?"})",
                "AUDIO OUT: $audioSummary",
                "DISK: %.1f GB free".format(freeDiskGb),
                "BUFFER: ${recommendedPreset.label}",
            ) + notes).joinToString("\n")

        val summaryAndNotes: String get() = summary
    }

    private fun tick(ok: Boolean) = if (ok) "✓" else "✗"

    private fun isHardwareDecoder(info: MediaCodecInfo): Boolean =
        !info.isSoftwareOnly

    @OptIn(UnstableApi::class)
    fun probe(ctx: Context): Verdict {
        val notes = mutableListOf<String>()

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val ramMb = mi.totalMem / (1024L * 1024L)
        val heapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        val ramClass = when {
            ramMb >= 2800 -> "High"
            ramMb >= 1500 -> "Mid"
            else -> "Low"
        }

        val mcl = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var hdHw = false
        var max4k = false
        var av1Hw = false
        var av1Sw = false
        var hwAvcInstances: Int? = null
        var hwHevcInstances: Int? = null

        fun preferredInstances(mime: String): Int? = runCatching {
            // Hardware only: a software decoder's instance count says nothing about how many streams the box decodes.
            val preferred = mcl.codecInfos.asSequence()
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
                .firstOrNull(::isHardwareDecoder)
            preferred?.getCapabilitiesForType(mime)?.maxSupportedInstances?.takeIf { it > 0 }
        }.getOrNull()

        hwAvcInstances = preferredInstances(MediaFormat.MIMETYPE_VIDEO_AVC)
        hwHevcInstances = preferredInstances(MediaFormat.MIMETYPE_VIDEO_HEVC)

        for (info in mcl.codecInfos) {
            if (info.isEncoder) continue
            val hw = isHardwareDecoder(info)
            for (type in info.supportedTypes) {
                when (type) {
                    MediaFormat.MIMETYPE_VIDEO_AVC -> if (hw) runCatching {
                        if (info.getCapabilitiesForType(type).videoCapabilities?.isSizeSupported(1920, 1088) == true) hdHw = true
                    }
                    MediaFormat.MIMETYPE_VIDEO_HEVC -> if (hw) runCatching {
                        if (info.getCapabilitiesForType(type).videoCapabilities?.isSizeSupported(3840, 2160) == true) max4k = true
                    }
                    MediaFormat.MIMETYPE_VIDEO_AV1 -> {
                        if (hw) av1Hw = true else av1Sw = true
                    }
                }
            }
        }

        val maxConcurrent = DecoderBudget.computeMaxStreams(
            avcInstances = hwAvcInstances,
            hevcInstances = hwHevcInstances,
            heapMb = heapMb,
            lowRam = am.isLowRamDevice,
            physicalRamMb = ramMb,
        )

        val audioSummary = runCatching {
            @Suppress("DEPRECATION")
            val caps = AudioCapabilities.getCapabilities(ctx)
            listOf(
                "DD" to caps.supportsEncoding(C.ENCODING_AC3),
                "DD+ Atmos" to caps.supportsEncoding(C.ENCODING_E_AC3_JOC),
                "DD+" to caps.supportsEncoding(C.ENCODING_E_AC3),
                "TrueHD" to caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD),
                "DTS" to caps.supportsEncoding(C.ENCODING_DTS),
                "DTS-HD" to caps.supportsEncoding(C.ENCODING_DTS_HD),
                "AC-4" to caps.supportsEncoding(C.ENCODING_AC4),
            ).joinToString(" · ") { (label, ok) -> "$label ${tick(ok)}" }
        }.getOrElse { "sink probe unavailable" }

        val freeGb = runCatching {
            StatFs(ctx.getExternalFilesDir(null)?.absolutePath ?: ctx.filesDir.absolutePath).availableBytes / 1e9
        }.getOrElse { -1.0 }

        val preset = when {
            ramMb >= 2800 && hdHw -> AppPreferences.BufferPreset.XL.also {
                notes += "Big RAM + HW 1080p decode: XL marathon buffer safe"
            }
            ramMb >= 1500 -> AppPreferences.BufferPreset.STD
            else -> AppPreferences.BufferPreset.LOW.also {
                notes += "Tight memory: low-latency buffer prevents eviction pauses"
            }
        }
        if (!max4k) notes += "No HEVC 4K decoder: 4K streams may fall back"
        if (av1Sw && !av1Hw) notes += "AV1 software-decoded only: thermals favor HEVC at equal quality"
        if (freeGb in 0.0..2.0) notes += "Low disk: recordings capped"
        Log.i(
            "Assessment",
            "ram=$ramMb($ramClass) heap=${heapMb}MB hd=$hdHw 4k=$max4k av1hw=$av1Hw av1sw=$av1Sw " +
                "hwInst(avc=$hwAvcInstances,hevc=$hwHevcInstances)->maxStreams=$maxConcurrent " +
                "audio=$audioSummary free=${freeGb}GB preset=$preset",
        )
        return Verdict(
            ramMb = ramMb,
            ramClass = ramClass,
            hdHw = hdHw,
            max4k = max4k,
            av1Hw = av1Hw,
            av1Sw = av1Sw,
            audioSummary = audioSummary,
            freeDiskGb = freeGb,
            recommendedPreset = preset,
            notes = notes,
            hwAvcInstances = hwAvcInstances,
            hwHevcInstances = hwHevcInstances,
            maxConcurrentStreams = maxConcurrent,
        )
    }

    /** Applies the recommended preset once, honoring user override afterward. */
    fun autoTuneOnce(ctx: Context): Boolean {
        if (AppPreferences.assessedTuned) return false
        val v = probe(ctx)
        AppPreferences.bufferPreset = v.recommendedPreset
        AppPreferences.assessedTuned = true
        return true
    }
}
