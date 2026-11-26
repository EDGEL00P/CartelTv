@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.ui.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/**
 * Video and audio codec families, and the spellings that identify each. One table serves the stream-info
 * card and the track picker; kept as two lists they disagreed, the card calling an MP3 object type "AAC".
 *
 * Both inputs are needed. HLS playlists and the MP4/Matroska AVC/HEVC/AAC tracks carry an RFC 6381 codec
 * string (`hvc1.1.6.L93.B0`, `mp4a.40.2`), but Media3's MPEG-TS readers for AC-3, DTS, MPEG audio and MPEG-2
 * video, and its Matroska reader for AC-3 and DTS, set only the sample MIME type.
 */

/** Video codec families, named the way the stream-info card shows them. */
internal enum class VideoCodec(val label: String) {
    AV1("AV1"),
    HEVC("HEVC (H.265)"),
    AVC("AVC (H.264)"),
    VVC("VVC (H.266)"),
    VP9("VP9"),
    VP8("VP8"),
    MPEG2("MPEG-2"),
    MPEG4("MPEG-4 Part 2"),
    MPEG1("MPEG-1"),
    H263("H.263"),
    VC1("VC-1 (WMV)"),
    MJPEG("MJPEG"),
}

/** Audio codec families: [label] is the card's home-theatre name, [short] the track picker's compact one. */
internal enum class AudioCodec(val label: String, val short: String = label) {
    AAC("AAC"),
    MP1("MP1"),
    MP2("MP2"),
    MP3("MP3"),
    AC3("AC-3 (Dolby Digital)", "AC3"),
    EAC3("E-AC-3 (DD+)", "E-AC3"),
    EAC3_ATMOS("E-AC-3 (DD+ Atmos)", "E-AC3"),
    AC4("AC-4"),
    DTS("DTS"),
    DTS_HD("DTS-HD"),
    DTS_EXPRESS("DTS Express"),
    DTS_X("DTS:X"),
    TRUEHD("Dolby TrueHD", "TrueHD"),
    MPEG_H("MPEG-H 3D Audio", "MPEG-H"),
    OPUS("Opus"),
    VORBIS("Vorbis"),
    FLAC("FLAC (lossless)", "FLAC"),
    ALAC("ALAC (lossless)"),
    PCM("PCM"),
    AMR("AMR"),
}

/** Codec family from a track's codec string (HLS variants carry tagged forms like hvc1/av01). */
internal fun videoCodecClass(codecs: String?, sampleMimeType: String? = null): String =
    videoCodecOf(codecs, sampleMimeType)?.label ?: codecs?.uppercase(Locale.ROOT).orEmpty()

/** Audio codec family, classified the way home-theatre specs name them. */
internal fun audioCodecClass(codecs: String?, sampleMimeType: String? = null): String =
    audioCodecOf(codecs, sampleMimeType)?.label ?: codecs?.uppercase(Locale.ROOT).orEmpty()

/** The first codec string that is recognised wins; when none is, the sample MIME type decides. */
internal fun videoCodecOf(codecs: String?, sampleMimeType: String? = null): VideoCodec? =
    codecStrings(codecs).firstNotNullOfOrNull(::videoTag) ?: mimeFamily(sampleMimeType, VIDEO_MIMES)

internal fun audioCodecOf(codecs: String?, sampleMimeType: String? = null): AudioCodec? =
    codecStrings(codecs).firstNotNullOfOrNull(::audioTag) ?: mimeFamily(sampleMimeType, AUDIO_MIMES)

private fun codecStrings(codecs: String?): List<String> =
    codecs?.lowercase(Locale.ROOT)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

/**
 * Only the string's first element is compared. A substring match let the "265" of an AVC profile such as
 * avc1.42651f read as HEVC. `mp4v` strings split again by object type.
 */
private fun videoTag(codec: String): VideoCodec? =
    if (codec.startsWith("mp4v.")) MP4V_NON_MPEG4[codec.substringAfter('.')] ?: VideoCodec.MPEG4 else VIDEO_TAGS[codec.substringBefore('.')]

/** Same first-element rule as [videoTag]; `mp4a` strings split by object type, and "joc" marks Atmos. */
private fun audioTag(codec: String): AudioCodec? {
    if ("joc" in codec) return AudioCodec.EAC3_ATMOS
    return if (codec.startsWith("mp4a.")) MP4A_NON_AAC[codec.substringAfter('.')] ?: AudioCodec.AAC else AUDIO_TAGS[codec.substringBefore('.')]
}

/** Sample MIME types, most specific first: the profile parameter tells the DTS variants apart. */
private fun <T> mimeFamily(mime: String?, table: List<Pair<String, T>>): T? =
    table.firstOrNull { (type, _) -> mime?.startsWith(type, ignoreCase = true) == true }?.second

/** First element of each codec string, plus the bare names providers write (`h264`, `ac3`). */
private val VIDEO_TAGS = mapOf(
    // Dolby Vision strings name their base layer: dvhe/dvh1 are HEVC, dvav is AVC, dva1/dav1 are AV1.
    "av01" to VideoCodec.AV1, "dva1" to VideoCodec.AV1, "dav1" to VideoCodec.AV1, "av1" to VideoCodec.AV1,
    "hvc1" to VideoCodec.HEVC, "hev1" to VideoCodec.HEVC, "dvhe" to VideoCodec.HEVC, "dvh1" to VideoCodec.HEVC,
    "hevc" to VideoCodec.HEVC, "h265" to VideoCodec.HEVC, "x265" to VideoCodec.HEVC,
    "avc1" to VideoCodec.AVC, "avc3" to VideoCodec.AVC, "dvav" to VideoCodec.AVC,
    "avc" to VideoCodec.AVC, "h264" to VideoCodec.AVC, "x264" to VideoCodec.AVC,
    "vvc1" to VideoCodec.VVC, "vvi1" to VideoCodec.VVC, "vvc" to VideoCodec.VVC, "h266" to VideoCodec.VVC,
    "vp09" to VideoCodec.VP9, "vp9" to VideoCodec.VP9, "vp08" to VideoCodec.VP8, "vp8" to VideoCodec.VP8,
    "mp2v" to VideoCodec.MPEG2, "mpeg2" to VideoCodec.MPEG2,
    "mp4v" to VideoCodec.MPEG4, "mpeg4" to VideoCodec.MPEG4, "xvid" to VideoCodec.MPEG4, "divx" to VideoCodec.MPEG4,
    "mp1v" to VideoCodec.MPEG1, "mpeg1" to VideoCodec.MPEG1,
    "s263" to VideoCodec.H263, "h263" to VideoCodec.H263,
    "vc-1" to VideoCodec.VC1, "vc1" to VideoCodec.VC1, "wvc1" to VideoCodec.VC1, "wmv3" to VideoCodec.VC1, "wmva" to VideoCodec.VC1,
    "mjpeg" to VideoCodec.MJPEG, "mjpg" to VideoCodec.MJPEG,
)

private val AUDIO_TAGS = mapOf(
    "ec+3" to AudioCodec.EAC3_ATMOS,
    "ec-3" to AudioCodec.EAC3, "dec3" to AudioCodec.EAC3, "eac3" to AudioCodec.EAC3, "ec3" to AudioCodec.EAC3,
    "ac-3" to AudioCodec.AC3, "dac3" to AudioCodec.AC3, "ac3" to AudioCodec.AC3,
    "ac-4" to AudioCodec.AC4, "dac4" to AudioCodec.AC4, "ac4" to AudioCodec.AC4,
    "truehd" to AudioCodec.TRUEHD, "true-hd" to AudioCodec.TRUEHD,
    "dtsx" to AudioCodec.DTS_X, "dtse" to AudioCodec.DTS_EXPRESS,
    "dtsh" to AudioCodec.DTS_HD, "dtsl" to AudioCodec.DTS_HD, "dts-hd" to AudioCodec.DTS_HD,
    "dtsc" to AudioCodec.DTS, "dts" to AudioCodec.DTS,
    "mha1" to AudioCodec.MPEG_H, "mhm1" to AudioCodec.MPEG_H,
    "opus" to AudioCodec.OPUS, "vorbis" to AudioCodec.VORBIS,
    "flac" to AudioCodec.FLAC, "alac" to AudioCodec.ALAC,
    "mp4a" to AudioCodec.AAC, "aac" to AudioCodec.AAC,
    "mp1" to AudioCodec.MP1, "mp2" to AudioCodec.MP2, "mp3" to AudioCodec.MP3,
    "pcm" to AudioCodec.PCM,
    "samr" to AudioCodec.AMR, "sawb" to AudioCodec.AMR,
)

/**
 * mp4a object types outside the AAC family (40, 66-68), resolved the way Media3 resolves them: 69 and 6B are
 * MPEG-2/MPEG-1 audio (MP3), the rest are the Dolby, DTS, Opus and Vorbis types registered under mp4a.
 */
private val MP4A_NON_AAC = mapOf(
    "69" to AudioCodec.MP3, "6b" to AudioCodec.MP3,
    "a5" to AudioCodec.AC3, "a6" to AudioCodec.EAC3,
    "a9" to AudioCodec.DTS, "ac" to AudioCodec.DTS, "aa" to AudioCodec.DTS_HD, "ab" to AudioCodec.DTS_HD,
    "ad" to AudioCodec.OPUS, "ae" to AudioCodec.AC4, "dd" to AudioCodec.VORBIS,
)

/** mp4v object types for MPEG-2 (60-65) and MPEG-1 (6A) video; every other mp4v string is MPEG-4 Part 2. */
private val MP4V_NON_MPEG4 = mapOf(
    "60" to VideoCodec.MPEG2, "61" to VideoCodec.MPEG2, "62" to VideoCodec.MPEG2, "63" to VideoCodec.MPEG2,
    "64" to VideoCodec.MPEG2, "65" to VideoCodec.MPEG2, "6a" to VideoCodec.MPEG1,
)

private val VIDEO_MIMES = listOf(
    // video/mpeg is a prefix of video/mpeg2, so MPEG-2 has to come first.
    MimeTypes.VIDEO_MPEG2 to VideoCodec.MPEG2,
    MimeTypes.VIDEO_MPEG to VideoCodec.MPEG1,
    MimeTypes.VIDEO_H264 to VideoCodec.AVC,
    MimeTypes.VIDEO_H265 to VideoCodec.HEVC,
    MimeTypes.VIDEO_AV1 to VideoCodec.AV1,
    MimeTypes.VIDEO_H266 to VideoCodec.VVC,
    MimeTypes.VIDEO_VP9 to VideoCodec.VP9,
    MimeTypes.VIDEO_VP8 to VideoCodec.VP8,
    MimeTypes.VIDEO_MP4V to VideoCodec.MPEG4,
    MimeTypes.VIDEO_DIVX to VideoCodec.MPEG4,
    MimeTypes.VIDEO_H263 to VideoCodec.H263,
    MimeTypes.VIDEO_VC1 to VideoCodec.VC1,
    MimeTypes.VIDEO_MJPEG to VideoCodec.MJPEG,
)

private val AUDIO_MIMES = listOf(
    // audio/mpeg is a prefix of audio/mpeg-L1 and audio/mpeg-L2, and audio/vnd.dts of the hd and uhd types.
    MimeTypes.AUDIO_MPEG_L1 to AudioCodec.MP1,
    MimeTypes.AUDIO_MPEG_L2 to AudioCodec.MP2,
    MimeTypes.AUDIO_MPEG to AudioCodec.MP3,
    MimeTypes.AUDIO_AAC to AudioCodec.AAC,
    MimeTypes.AUDIO_AC3 to AudioCodec.AC3,
    MimeTypes.AUDIO_E_AC3_JOC to AudioCodec.EAC3_ATMOS,
    MimeTypes.AUDIO_E_AC3 to AudioCodec.EAC3,
    MimeTypes.AUDIO_AC4 to AudioCodec.AC4,
    MimeTypes.AUDIO_DTS_UHD_P2 to AudioCodec.DTS_X,
    MimeTypes.AUDIO_DTS_EXPRESS to AudioCodec.DTS_EXPRESS,
    MimeTypes.AUDIO_DTS_HD to AudioCodec.DTS_HD,
    MimeTypes.AUDIO_DTS to AudioCodec.DTS,
    MimeTypes.AUDIO_TRUEHD to AudioCodec.TRUEHD,
    MimeTypes.AUDIO_MPEGH_MHA1 to AudioCodec.MPEG_H,
    MimeTypes.AUDIO_MPEGH_MHM1 to AudioCodec.MPEG_H,
    MimeTypes.AUDIO_OPUS to AudioCodec.OPUS,
    MimeTypes.AUDIO_VORBIS to AudioCodec.VORBIS,
    MimeTypes.AUDIO_FLAC to AudioCodec.FLAC,
    MimeTypes.AUDIO_ALAC to AudioCodec.ALAC,
    MimeTypes.AUDIO_RAW to AudioCodec.PCM,
    MimeTypes.AUDIO_AMR_NB to AudioCodec.AMR,
    MimeTypes.AUDIO_AMR_WB to AudioCodec.AMR,
)
