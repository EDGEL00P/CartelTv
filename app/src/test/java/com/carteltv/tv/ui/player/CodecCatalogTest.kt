package com.carteltv.tv.ui.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class StreamCodecsTest {

    @Test fun dolbyVisionStringsNameTheirBaseLayerCodec() {
        assertEquals(VideoCodec.HEVC, videoCodecOf("dvhe.05.01"))
        assertEquals(VideoCodec.HEVC, videoCodecOf("dvh1.08.18"))
        assertEquals(VideoCodec.AVC, videoCodecOf("dvav.09.07"))
        assertEquals(VideoCodec.AV1, videoCodecOf("dva1.10.01"))
    }

    @Test fun everyVideoFamilyIsRecognisedByCodecString() {
        val examples = mapOf(
            "av01.0.13M.08" to VideoCodec.AV1,
            "hvc1.2.4.L153.B0" to VideoCodec.HEVC,
            "hev1.1.6.L93.B0" to VideoCodec.HEVC,
            "avc1.640028" to VideoCodec.AVC,
            "avc3.42E01E" to VideoCodec.AVC,
            "vvc1.9.L1.100.CB.0.0.0.1.0.0" to VideoCodec.VVC,
            "vp09.00.31.08" to VideoCodec.VP9,
            "vp08.00.31.08" to VideoCodec.VP8,
            "mp2v" to VideoCodec.MPEG2,
            "mp4v.20.9" to VideoCodec.MPEG4,
            "mp4v.60" to VideoCodec.MPEG2,
            "mp1v" to VideoCodec.MPEG1,
            "s263" to VideoCodec.H263,
            "vc-1" to VideoCodec.VC1,
            "mjpeg" to VideoCodec.MJPEG,
        )
        examples.forEach { (codecs, family) -> assertEquals(codecs, family, videoCodecOf(codecs)) }
    }

    @Test fun everyAudioFamilyIsRecognisedByCodecString() {
        val examples = mapOf(
            "aac" to AudioCodec.AAC,
            "mp4a.40.2" to AudioCodec.AAC,
            "mp4a.40.5" to AudioCodec.AAC,
            "mp4a.40.29" to AudioCodec.AAC,
            "mp4a.6B" to AudioCodec.MP3,
            "mp3" to AudioCodec.MP3,
            "mp2" to AudioCodec.MP2,
            "mp1" to AudioCodec.MP1,
            "ac-3" to AudioCodec.AC3,
            "ec-3" to AudioCodec.EAC3,
            "ec+3" to AudioCodec.EAC3_ATMOS,
            "ac-4.01.02.01" to AudioCodec.AC4,
            "dtsc" to AudioCodec.DTS,
            "dtsh" to AudioCodec.DTS_HD,
            "dtsl" to AudioCodec.DTS_HD,
            "dtse" to AudioCodec.DTS_EXPRESS,
            "dtsx" to AudioCodec.DTS_X,
            "truehd" to AudioCodec.TRUEHD,
            "mha1" to AudioCodec.MPEG_H,
            "mhm1" to AudioCodec.MPEG_H,
            "opus" to AudioCodec.OPUS,
            "vorbis" to AudioCodec.VORBIS,
            "fLaC" to AudioCodec.FLAC,
            "alac" to AudioCodec.ALAC,
            "pcm" to AudioCodec.PCM,
            "samr" to AudioCodec.AMR,
        )
        examples.forEach { (codecs, family) -> assertEquals(codecs, family, audioCodecOf(codecs)) }
    }

    @Test fun mpeg2AacObjectTypesAreNotMpegAudio() {
        assertEquals(AudioCodec.AAC, audioCodecOf("mp4a.66"))
        assertEquals(AudioCodec.AAC, audioCodecOf("mp4a.67"))
        assertEquals(AudioCodec.AAC, audioCodecOf("mp4a.68"))
        assertEquals(AudioCodec.MP3, audioCodecOf("mp4a.69"))
        assertEquals(AudioCodec.MP3, audioCodecOf("mp4a.6b"))
    }

    @Test fun transportStreamTracksAreClassifiedByMimeType() {
        // Media3's MPEG-TS readers for these codecs set no codec string.
        assertEquals(VideoCodec.MPEG2, videoCodecOf(null, MimeTypes.VIDEO_MPEG2))
        assertEquals(VideoCodec.MPEG4, videoCodecOf(null, MimeTypes.VIDEO_MP4V))
        assertEquals(AudioCodec.MP2, audioCodecOf(null, MimeTypes.AUDIO_MPEG_L2))
        assertEquals(AudioCodec.MP3, audioCodecOf(null, MimeTypes.AUDIO_MPEG))
        assertEquals(AudioCodec.AC3, audioCodecOf(null, MimeTypes.AUDIO_AC3))
        assertEquals(AudioCodec.EAC3, audioCodecOf(null, MimeTypes.AUDIO_E_AC3))
        assertEquals(AudioCodec.EAC3_ATMOS, audioCodecOf(null, MimeTypes.AUDIO_E_AC3_JOC))
        assertEquals(AudioCodec.AC4, audioCodecOf(null, MimeTypes.AUDIO_AC4))
        assertEquals(AudioCodec.DTS, audioCodecOf(null, MimeTypes.AUDIO_DTS))
        assertEquals(AudioCodec.DTS_HD, audioCodecOf(null, MimeTypes.AUDIO_DTS_HD))
        assertEquals(AudioCodec.DTS_EXPRESS, audioCodecOf(null, MimeTypes.AUDIO_DTS_EXPRESS))
        assertEquals(AudioCodec.DTS_X, audioCodecOf(null, MimeTypes.AUDIO_DTS_UHD_P2))
        assertEquals(AudioCodec.TRUEHD, audioCodecOf(null, MimeTypes.AUDIO_TRUEHD))
        assertEquals(AudioCodec.MPEG_H, audioCodecOf(null, MimeTypes.AUDIO_MPEGH_MHM1))
        assertEquals(AudioCodec.OPUS, audioCodecOf(null, MimeTypes.AUDIO_OPUS))
        assertEquals(AudioCodec.VORBIS, audioCodecOf(null, MimeTypes.AUDIO_VORBIS))
        assertEquals(AudioCodec.FLAC, audioCodecOf(null, MimeTypes.AUDIO_FLAC))
    }

    @Test fun digitsInsideAProfileStringAreNotAnotherCodec() {
        // "265" sits across the profile and constraint bytes of this AVC string; a substring match read it as HEVC.
        assertEquals(VideoCodec.AVC, videoCodecOf("avc1.42651f"))
        assertEquals("AVC (H.264)", videoCodecClass("avc1.42651f"))
    }

    @Test fun theFirstRecognisedCodecStringWins() {
        assertEquals(VideoCodec.HEVC, videoCodecOf("zz01.1,hvc1.1.6.L93.B0"))
        assertEquals(AudioCodec.EAC3, audioCodecOf("ec-3,mp4a.40.2"))
    }

    @Test fun unrecognisedCodecsShowTheStringAsWritten() {
        assertEquals("", videoCodecClass(null))
        assertEquals("", audioCodecClass(null, MimeTypes.AUDIO_UNKNOWN))
        assertEquals("ZZ01.1", videoCodecClass("zz01.1"))
        assertEquals("SOMENEWCODEC", audioCodecClass("somenewcodec"))
    }

    @Test fun cardLabelsNameTheCodecFamily() {
        assertEquals("HEVC (H.265)", videoCodecClass("dvhe.05.01"))
        assertEquals("Dolby TrueHD", audioCodecClass("truehd"))
        assertEquals("MP2", audioCodecClass(null, MimeTypes.AUDIO_MPEG_L2))
        assertEquals("AC-3 (Dolby Digital)", audioCodecClass(null, MimeTypes.AUDIO_AC3))
        assertEquals("DTS-HD", audioCodecClass("dtsh"))
    }
}
