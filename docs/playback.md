# Playback engine

*Status: current · Owner: repo maintainer · Last verified: 2026-11-27*

How the engine is built to keep video playing through providers whose streams range from pristine
to barely conforming. The decision trail is in [ADR-0001](adr/0001-media3-player-stack.md) (the
stack) and [ADR-0002](adr/0002-live-failure-ladder.md) (failure handling); for a live incident read
[troubleshooting-playback.md](troubleshooting-playback.md) instead.

## One player recipe

Every surface that shows video — the main player, embedded YouTube, multi-view tiles — builds its
ExoPlayer through `buildCartelPlayer`
(`app/src/main/java/com/carteltv/tv/ui/player/CartelPlayerBuilder.kt`). Buffer sizes come from
`PlaybackBufferPolicy` (`engine/stability/PlaybackBufferPolicy.kt`), the initial bitrate estimate
is 20 Mbps so multi-variant HLS does not start on its lowest rendition, and TS extractors get
access-unit detection and non-IDR keyframes because IPTV transport streams routinely omit both.
Low-RAM boxes get an explicit 48 MB media-buffer cap.

Live buffer presets (`AppPreferences.BufferPreset`): LOW 5–15 s, STD 8–30 s, XL 15–60 s — and a
live target offset of 6/10/18 s behind the edge, so playback starts cushioned instead of at the
edge and re-lands there after a stall. Playback speed is pinned at 1.0 in the live configuration:
gradual reserve is owned solely by `LiveBufferCushion`.

## Sources and formats

`LenientMediaSourceFactory` (`engine/playback/HlsPlaylistRepair.kt`) splits by content type:

| Format | Lane | Load-error policy (live) |
|---|---|---|
| HLS (`.m3u8`) | lenient parser + repairs | segments: 2 quick tries (500 ms), then surface |
| Progressive TS (`.ts`) | stock factory, custom extractors | 3 tries, then surface |
| MPEG-DASH | stock factory | segments: 2 quick tries, then surface |

The same `LiveLoadErrorPolicy` arms both lanes, so DASH and TS fail as fast as HLS. VOD keeps the
stock policy — it has no recovery ladder to catch a surfaced failure. DRM comes from the
playlist-declared metadata via `DrmPolicy` (`cartelMediaItem`), not from global settings.

## HLS leniency

`HlsPlaylistRepair` fixes what Media3's parser rejects, as plain text in and out: a UTF-8 BOM, a
missing `#EXTM3U`, a variant without `BANDWIDTH`, a rendition without `NAME`/`GROUP-ID`. The
lenient parser refuses early (512 bytes) when the link is actually an MPEG-TS stream —
`HlsBodyIsTs` — and the channel is re-typed as progressive TS instead of parsing garbage. A
playlist body over 4 MiB is refused rather than read endlessly. Only the *primary* playlist load
marks the TS case: a media playlist sending TS is a broken variant, not a TS link.

## Failure handling: the ladder

`ChannelTuneRecoveryPolicy` (`engine/stability/ChannelTuneRecoveryPolicy.kt`) classifies each
failure:

- **Permanent** — 400, 404, 410, 451, parse errors (3001–3004), decoder-setup errors
  (4001, 4002, 4004, 4005): go straight to the next quality copy. 401/403 are *not* permanent;
  Xtream panels answer with them for connection limits and lapsed sessions, which clear on
  their own.
- **Transient** — everything else: reload the same stream after `StreamReconnectPolicy`'s backoff
  (1 s doubling to a 30 s cap, ±jitter so many clients dropped by one outage do not reconnect on
  the same tick), until the budget is spent; then the next copy; with none left, the channel is
  unavailable.

Budgets keep the ladder honest: no READY within 10 s of an attempt is a failure; the whole ladder
stops reloading 30 s after the tune began; and 30 s of sustained playback restores a fresh budget,
so one bad segment after an hour does not cost a stream its retries.

## Surfaces without a ladder

`PlaybackStallGuard` (`ui/player/PlaybackStallGuard.kt`) serves YouTube and multi-view tiles: a
startup timer (no READY within 10 s) and a stall timer (buffering while it should play), each
surfacing bounded retries and then an honest give-up instead of an eternal spinner. Multi-view
tiles deliberately space auto-retries one second apart so four tiles dropped by one outage do not
reconnect as a herd.

## Data layer

- `HttpRetryPolicy` (`data/network/HttpRetryPolicy.kt`) retries only what can heal — connection
  failures and 5xx — with two attempts on a 400 ms doubling, jittered backoff. A working server's
  4xx is an answer, not a hiccup; 429 (Xtream connection limit) is never retried.
- `LiveRetryDataSource` re-opens a live data source without discarding player state.
- `StreamCopyFailover` / `AlternateSourceSelector` pick the next quality copy when the ladder
  calls for one; `ChannelDuplicates` knows which copies exist.
- Provider-down is a state, not an empty playlist: `LiveChannels.refreshFailed` marks it and the
  guide says the provider could not be reached.

## Dolby Vision, HDR and audio

Dolby Vision in TS is handled by `DolbyVisionExtractorsFactory` + `DvPolicy` (profile-dependent,
with unlabelled-DV detection and RPU guarding in `engine/dolbyvision/`); TrueHD MAT bitstream
packing for passthrough lives in `engine/audio/` (`MatPacker`, `Iec61937MatSink`); HDR10 SEI
injection/removal in `engine/hdr/`. These are thin, well-commented units — read the files; this
page does not restate their tables.
