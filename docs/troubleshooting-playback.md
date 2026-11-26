# Troubleshooting playback

*Status: current · Owner: repo maintainer · Last verified: 2026-11-27*

A channel misbehaves — work down this list in order. The machinery behind each answer is in
[playback.md](playback.md); the policies are pure classes with unit tests, so a suspicion can
usually be confirmed in `app/src/test/java/com/carteltv/tv/engine/stability/` before touching a
provider.

## The channel never starts

1. **Is it the provider?** Open the same stream in a desktop player (VLC) with the same URL. If
   it fails there too, the ladder did its job: the app has already tried reloads (jittered 1 s →
   30 s backoff) and the next quality copy. A 404/410/451 link is classified permanent and skips
   straight to the copy; with no copy, the channel is genuinely unavailable.
2. **A 401/403 loop is not a dead link.** Xtream panels answer 401/403 for connection limits and
   lapsed sessions; the app treats them as transient on purpose. Re-authenticate or wait out the
   panel's limit before assuming the app is broken.
3. **"Provider could not be reached"** in the guide is `LiveChannels.refreshFailed`: the panel
   itself did not answer the playlist refresh, so every channel is affected, not just one.

## Playback starts, then stutters

1. **Raise the buffer preset** (Settings → playback). LOW holds 5–15 s; on a provider with jittery
   throughput the cushion is too thin. STD (8–30 s) or XL (15–60 s) also start playback further
   behind the live edge (10 s / 18 s instead of 6 s), which is the intended trade.
2. **Segments that fail repeatedly now surface fast** (two 500 ms tries) instead of grinding
   through Media3's slow in-URL retries — a flaky provider shows as the recovery ladder running
   (brief reload), not a minute-long freeze. If reloads are constant, the provider's edge is the
   problem; no buffer preset fixes a dead origin.
3. **Stalls while it should play** trip `PlaybackStallGuard`'s stall timer on ladder-less
   surfaces (YouTube, multi-view tiles): after bounded retries the tile gives up instead of
   spinning forever. A tile that gave up needs its channel re-selected.

## The wrong thing plays, or the link is "HLS" but is not

1. A link typed HLS that serves an MPEG-TS stream is detected at the primary playlist load
   (`HlsBodyIsTs`) and re-typed as progressive TS automatically. If it still fails, the stream is
   broken in a way leniency cannot fix — check it in VLC.
2. Malformed playlists (missing `#EXTM3U`, absent `BANDWIDTH`, nameless renditions, BOM) are
   repaired silently before parsing. A playlist over 4 MiB or one that is not a playlist at all
   is refused early rather than read endlessly.

## Multi-view specifics

Each tile runs the same engine and policies as the main player; auto-retries are spaced one
second apart so four tiles do not reconnect as a herd. All four failing at once means the
provider or the network dropped, not four app bugs.

## Provider-side checklist, in order

1. Same URL in VLC (rules the app in or out).
2. Panel status page / other channels from the same provider (rules the panel in or out).
3. Provider's own guide URL vs the app's guide (a dead `xmltv.php` leaves the guide empty but
   playback working — see [epg.md](epg.md)).
4. Only after all of the above: file an issue with the stream URL, what the ladder did (reload
   spin, copy switch, "unavailable"), and whether VLC played it.
