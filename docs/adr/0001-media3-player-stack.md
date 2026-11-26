# 1. Media3 as the playback engine, with one shared player recipe

*Status: Accepted · Date: recorded 2026-11-27 (the choice predates this record)*

## Context

CartelTV plays live IPTV and VOD from three delivery formats — HLS playlists, progressive MPEG-TS,
and MPEG-DASH — served by providers whose streams range from pristine to barely conforming. The
app also needs behaviour Android's stock player does not offer: Dolby Vision profile handling in
TS containers, TrueHD/MAT bitstream packing for passthrough, in-player captions for live TS, and
lenient HLS playlist parsing for providers whose playlists are malformed in ways Media3's stock
parser rejects. Several surfaces play video at once (the main player, multi-view tiles, embedded
YouTube), and they must not drift apart in renderers, tracks or buffering.

## Decision

Use Media3/ExoPlayer as the engine, and route **every** video surface through one builder,
`buildCartelPlayer` (`app/src/main/java/com/carteltv/tv/ui/player/CartelPlayerBuilder.kt`), so
renderers, track selection, buffering and focus are configured exactly once. Custom behaviour is
layered as factories and policies around the stock components rather than forks:

- `LenientMediaSourceFactory` (`engine/playback/HlsPlaylistRepair.kt`) keeps stock factories for
  DASH and progressive TS and adds a lenient HLS lane; both can be armed with a
  `LoadErrorHandlingPolicy`.
- Custom extractor and renderer factories (Dolby Vision, MAT audio) delegate to Media3 defaults.
- The FFmpeg audio and dav1d AV1 decoder modules are local AARs built from Media3 sources,
  hash-verified by the `verifyLocalLibs` build task.

## Consequences

- One place to change buffering, retries or renderers; multi-view and YouTube cannot silently
  diverge from the main player.
- The code opts into `@UnstableApi` — Media3 internals can shift on upgrades, and the string/type
  matching that detects e.g. `HlsBodyIsTs` is a known isolation debt (tracked as deferred).
- Custom factories must forward every `MediaSource.Factory` method they override or behavior
  silently differs per content type.
