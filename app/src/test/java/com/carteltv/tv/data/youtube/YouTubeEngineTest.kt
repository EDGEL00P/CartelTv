package com.carteltv.tv.data.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.carteltv.tv.ui.components.OverlayCoordinator

/** Unit tests for the Flow/NewPipe/LibreTube-derived YouTube engine in CartelTV. */
class YouTubeEngineTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun stream_denial_classifies_expired_attestation_and_token_rejection() {
        val now = 1_800_000_000L
        val expired = "https://rr1---sn-a.googlevideo.com/videoplayback?expire=${now + 10}&c=VISIONOS&itag=137"
        val gated = "https://rr1---sn-a.googlevideo.com/videoplayback?expire=${now + 3600}&c=ANDROID_VR&itag=137"
        val tokRej = "https://rr1---sn-a.googlevideo.com/videoplayback?expire=${now + 3600}&c=WEB&itag=137&pot=abc123"
        val unknown = "https://example.com/no-expire"

        assertEquals(StreamDenialKind.URL_EXPIRED, YouTubeStreamDenial.classify(expired, now))
        assertEquals(StreamDenialKind.ATTESTATION_GATED, YouTubeStreamDenial.classify(gated, now))
        assertEquals(StreamDenialKind.TOKEN_REJECTED, YouTubeStreamDenial.classify(tokRej, now))
        assertEquals(StreamDenialKind.UNKNOWN, YouTubeStreamDenial.classify(unknown, now))
        assertEquals("ANDROID_VR", YouTubeStreamDenial.clientOf(gated))
        assertEquals("137", YouTubeStreamDenial.itagOf(gated))
        assertTrue(YouTubeStreamDenial.hasPoToken(tokRej))
        assertFalse(YouTubeStreamDenial.hasPoToken(gated))
    }

    @Test
    fun append_param_if_missing_preserves_existing_cpn() {
        val base = "https://rr1---sn-a.googlevideo.com/videoplayback?itag=137"
        val withCpn = YouTubeStreamDenial.appendParamIfMissing(base, "cpn", "abc123")
        assertTrue(withCpn.endsWith("&cpn=abc123"))
        assertEquals(withCpn, YouTubeStreamDenial.appendParamIfMissing(withCpn, "cpn", "other"))
    }

    @Test
    fun client_gate_demotes_immediately_on_attestation_and_lapses_after_ttl() {
        var nowMs = 10_000L
        val callbacks = mutableListOf<String>()
        val gate = YouTubeClientGate(ttlMs = 60_000L, clockMs = { nowMs }, onGated = { callbacks += it })

        val gatedUrl = "https://rr1---sn-a.googlevideo.com/videoplayback?expire=${nowMs / 1000 + 3600}&c=ANDROID_VR"
        assertEquals(StreamDenialKind.ATTESTATION_GATED, gate.reportDenied(gatedUrl))
        assertTrue(gate.isGated("ANDROID_VR"))
        assertEquals(listOf("ANDROID_VR"), callbacks)

        nowMs += 60_001L
        assertFalse(gate.isGated("ANDROID_VR"))
    }

    @Test
    fun client_gate_requires_two_strikes_for_token_rejection() {
        val nowMs = 10_000L
        val gate = YouTubeClientGate(ttlMs = 60_000L, clockMs = { nowMs })
        val tokUrl = "https://rr1---sn-a.googlevideo.com/videoplayback?expire=${nowMs / 1000 + 3600}&c=WEB&pot=xyz"

        gate.reportDenied(tokUrl)
        assertFalse(gate.isGated("WEB"))
        gate.reportDenied(tokUrl)
        assertTrue(gate.isGated("WEB"))
    }

    @Test
    fun host_fallback_reroutes_across_mn_mirrors_and_recovers_after_ttl() {
        var nowMs = 1_000L
        val fb = YouTubeHostFallback(forgetAfterMs = 30_000L, clockMs = { nowMs })
        val url = "https://rr3---sn-aaa.googlevideo.com/videoplayback?itag=137&mn=sn-aaa%2Csn-bbb%2Csn-ccc"

        assertEquals(url, fb.route(url))
        fb.markUnreachable(url)
        val routed = fb.route(url)
        assertEquals(
            "https://rr3---sn-bbb.googlevideo.com/videoplayback?itag=137&mn=sn-aaa%2Csn-bbb%2Csn-ccc",
            routed,
        )

        nowMs += 31_000L
        assertEquals(url, fb.route(url))
    }

    @Test
    fun request_policy_restricts_browser_cors_headers_to_web_family_clients() {
        val visionHeaders = YouTubeRequestPolicy.headersForClient("VISIONOS", allowGzip = false)
        assertEquals("identity", visionHeaders["Accept-Encoding"])
        assertNull(visionHeaders["Origin"])
        assertNull(visionHeaders["Referer"])
        assertNull(visionHeaders["Sec-Fetch-Mode"])

        val tizenHeaders = YouTubeRequestPolicy.headersForClient("TVHTML5", allowGzip = false)
        assertEquals("identity", tizenHeaders["Accept-Encoding"])
        assertEquals("https://www.youtube.com", tizenHeaders["Origin"])
        assertEquals("cors", tizenHeaders["Sec-Fetch-Mode"])

        val hlsHeaders = YouTubeRequestPolicy.headersForClient("VISIONOS", allowGzip = true)
        assertNull(hlsHeaders["Accept-Encoding"])
    }

    @Test
    fun signature_cipher_parses_and_builds_signed_url() {
        val raw = "s=SCRAMBLED%3D%3D&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fitag%3D137"
        val parsed = YouTubeSignatureCipher.parse(raw)
        assertNotNull(parsed)
        assertEquals("SCRAMBLED==", parsed!!.signature)
        assertEquals("sig", parsed.parameter)
        assertEquals(
            "https://rr1.googlevideo.com/videoplayback?itag=137&sig=SOLVED_SIG",
            parsed.signedUrl("SOLVED_SIG"),
        )
    }

    @Test
    fun preroll_wait_uses_eighty_percent_of_start_ad_skip_offset_capped_at_8s() {
        val placements = json.parseToJsonElement(
            """
            [
              {
                "adPlacementRenderer": {
                  "config": { "adPlacementConfig": { "kind": "AD_PLACEMENT_KIND_START" } },
                  "renderer": { "instreamVideoAdRenderer": { "skipOffsetMilliseconds": "5000" } }
                }
              }
            ]
            """.trimIndent(),
        ).jsonArray
        assertEquals(4_000L, YouTubePrerollWait.of(placements))

        val hugePlacements = json.parseToJsonElement(
            """
            [
              {
                "adPlacementRenderer": {
                  "config": { "adPlacementConfig": { "kind": "AD_PLACEMENT_KIND_START" } },
                  "renderer": { "instreamVideoAdRenderer": { "skipOffsetMilliseconds": "30000" } }
                }
              }
            ]
            """.trimIndent(),
        ).jsonArray
        assertEquals(YouTubePrerollWait.MAX_WAIT_MS, YouTubePrerollWait.of(hugePlacements))
    }

    @Test
    fun pick_best_audio_selects_original_track_over_higher_bitrate_foreign_dub() {
        val candidates = listOf(
            YouTubeApiClient.FormatCandidate(
                itag = "140",
                url = "https://a/ar",
                mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                height = 0,
                fps = 0,
                bitrate = 135_000,
                audioTrackId = "ar.3",
                audioTrackName = "Arabic",
                isDefaultAudio = false,
                isOriginalAudio = false,
            ),
            YouTubeApiClient.FormatCandidate(
                itag = "140",
                url = "https://a/en-drc",
                mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                height = 0,
                fps = 0,
                bitrate = 132_000,
                audioTrackId = "en.4",
                audioTrackName = "English original",
                isDefaultAudio = true,
                isOriginalAudio = true,
                isDrc = true,
            ),
            YouTubeApiClient.FormatCandidate(
                itag = "140",
                url = "https://a/en-orig",
                mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                height = 0,
                fps = 0,
                bitrate = 128_000,
                audioTrackId = "en.4",
                audioTrackName = "English original",
                isDefaultAudio = true,
                isOriginalAudio = true,
                isDrc = false,
            ),
        )
        val picked = YouTubeApiClient.pickBestAudio(candidates)
        assertNotNull(picked)
        assertEquals("https://a/en-orig", picked!!.url)
    }

    @Test
    fun pick_best_video_handles_vertical_shorts_short_side_and_prefers_sdr_tiebreak() {
        val candidates = listOf(
            YouTubeApiClient.FormatCandidate(
                itag = "298",
                url = "https://v/short-720",
                mimeType = "video/mp4; codecs=\"avc1.640020\"",
                width = 720,
                height = 1280,
                fps = 30,
                bitrate = 1_500_000,
            ),
            YouTubeApiClient.FormatCandidate(
                itag = "335",
                url = "https://v/short-720-hdr",
                mimeType = "video/webm; codecs=\"vp9.2\"",
                width = 720,
                height = 1280,
                fps = 60,
                bitrate = 2_500_000,
                isHdr = true,
            ),
        )
        val picked = YouTubeApiClient.pickBestVideo(candidates, maxHeight = 720)
        assertNotNull(picked)
        assertEquals("298", picked!!.itag)
    }

    @Test
    fun dearrow_branding_parser_uses_real_hash_endpoint_schema() {
        val raw = """
            {
              "0e3GPea1Tyg": {
                "titles": [
                  {"title": "Downvoted Clickbait", "original": false, "votes": -3, "locked": false},
                  {"title": "Real Squid Game Challenge", "original": false, "votes": 7, "locked": true}
                ],
                "thumbnails": [
                  {"original": true, "votes": 10, "locked": false},
                  {"original": false, "timestamp": 124.5, "votes": 4, "locked": false}
                ]
              }
            }
        """.trimIndent()
        val res = YouTubeApiClient.parseDeArrowBranding("0e3GPea1Tyg", raw)
        assertNotNull(res)
        assertEquals("Real Squid Game Challenge", res!!.first)
        assertEquals(
            "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=0e3GPea1Tyg&time=124.5",
            res.second,
        )
    }

    @Test
    fun sponsorblock_hashed_parser_extracts_matching_video_segments() {
        val raw = """
            [
              {"videoID": "otherVideo1", "segments": [{"segment": [1.0, 9.0], "category": "sponsor", "actionType": "skip"}]},
              {
                "videoID": "targetVid11",
                "segments": [
                  {"segment": [30.5, 65.0], "category": "sponsor", "actionType": "skip"},
                  {"segment": [10.0, 15.0], "category": "intro", "actionType": "mute"}
                ]
              }
            ]
        """.trimIndent()
        val segs = YouTubeApiClient.parseSponsorSegmentsHashed("targetVid11", raw)
        assertEquals(2, segs.size)
        assertEquals("intro", segs[0].category)
        assertEquals("mute", segs[0].action)
        assertEquals("sponsor", segs[1].category)
        assertEquals(30.5, segs[1].startSec, 0.01)
    }

    @Test
    fun lockup_view_model_parser_extracts_modern_youtube_video_cards() {
        val lockup = json.parseToJsonElement(
            """
            {
              "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
              "contentId": "dQw4w9WgXcQ",
              "metadata": {
                "lockupMetadataViewModel": {
                  "title": { "content": "Never Gonna Give You Up" },
                  "metadata": {
                    "contentMetadataViewModel": {
                      "metadataRows": [
                        { "metadataParts": [ { "text": { "content": "Rick Astley" } } ] },
                        { "metadataParts": [
                          { "text": { "content": "1.5B views" } },
                          { "text": { "content": "2 days ago" } }
                        ] }
                      ]
                    }
                  }
                }
              },
              "contentImage": {
                "thumbnailViewModel": {
                  "image": { "sources": [ { "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg" } ] },
                  "overlays": [
                    {
                      "thumbnailOverlayBadgeViewModel": {
                        "thumbnailBadges": [
                          { "thumbnailBadgeViewModel": { "text": "3:33" } }
                        ]
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        ).jsonObject
        val v = YouTubeApiClient.parseLockupViewModel(lockup)
        assertNotNull(v)
        assertEquals("dQw4w9WgXcQ", v!!.videoId)
        assertEquals("Never Gonna Give You Up", v.title)
        assertEquals("Rick Astley", v.channel)
        assertEquals("1.5B views", v.viewsText)
        assertEquals("2 days ago", v.publishedText)
        assertEquals("3:33", v.duration)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", v.thumb)
    }
}
