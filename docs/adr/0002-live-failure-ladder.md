# 2. Fail fast on failing loads; recover through a bounded ladder

*Status: Accepted · Date: recorded 2026-11-27 (the choice predates this record)*

## Context

Media3's stock `DefaultLoadErrorHandlingPolicy` retries a failing load up to three times (a
progressive live stream up to six) with growing delays, all inside the same URL. On a flaky IPTV
provider, one dead segment burns the whole retry budget while the viewer stares at a spinner —
even though the app has better options the player cannot see: reloading the stream, or switching
to another quality copy of the same channel. Conversely, some failures never heal (a 404 link, an
unreadable stream), and retrying them six times only delays the inevitable switch.

## Decision

Split the work between two pure components, and keep each bounded:

- **Fail fast where a ladder exists.** `LiveLoadErrorPolicy`
  (`engine/stability/LiveLoadErrorPolicy.kt`) gives live media segments two quick tries (500 ms
  apart) and a progressive live TS stream three, then surfaces the error to the player. VOD keeps
  the stock policy — it has no ladder to catch a surfaced failure.
- **Let the ladder decide.** `ChannelTuneRecoveryPolicy` classifies each failure — 400/404/410/451,
  parse and decoder-setup errors are permanent and go straight to the next copy; 401/403 are
  *not* permanent because Xtream panels use them for connection limits and lapsed sessions — and
  otherwise reloads the same stream on `StreamReconnectPolicy`'s jittered backoff (1 s doubling to
  a 30 s cap), within a 30-second overall budget and a 10-second startup timeout. Thirty seconds
  of sustained playback restores the budget, so a healthy stream keeps its retries.
- **Cushion instead of catch-up.** Live playback starts and re-lands 6/10/18 s behind the edge
  (`PlaybackBufferPolicy.liveTargetOffsetMs`) with playback speed pinned at 1.0, so gradual
  catch-up stays the job of exactly one component, `LiveBufferCushion`.
- **Surfaces without a ladder** (YouTube, multi-view tiles) use `PlaybackStallGuard`: a bounded
  retry count, then an honest give-up instead of an eternal spinner.

## Consequences

- A dead segment costs at most ~1 s before the ladder takes over; zapping failures resolve by
  copy-switching rather than patience.
- Failures surface to UI state quickly, so "provider down" reads as provider down
  (`LiveChannels.refreshFailed`) instead of an empty playlist.
- The behavior is only JVM-verified (unit tests over the pure policies); on-device tuning of the
  live offset and retry pacing on the Shield TV is still pending.
- The policy must be armed on both factories (`LenientMediaSourceFactory` and the stock DASH/TS
  lane), or formats silently recover differently.
