# CartelTV

An Android TV app for live TV and on-demand streaming: Xtream / Stalker / M3U playlists for live
channels, Stremio-style add-ons with Real-Debrid and Torbox for movies and shows, a full EPG guide,
multi-view, and a custom launcher surface — built around a Media3 playback engine tuned for the
flaky-reality of IPTV providers. Developed against an NVIDIA Shield TV; GPL-3.0 (see `LICENSE`,
with derived work attributed in `NOTICE`).

- **Platform:** Android TV (leanback), Android 11+ (`minSdk 30`, `targetSdk 36`)
- **Engine:** Kotlin, Jetpack Compose for TV, Media3/ExoPlayer 1.11.1 with custom renderers and
  extractors (Dolby Vision, MAT passthrough, lenient HLS)
- **Guide feeds:** built by CI and published as release assets — see
  [docs/data-pipeline-ops.md](docs/data-pipeline-ops.md)

## Build and test

Requires JDK 21 and an Android SDK (`local.properties` pointing at it). From the repo root:

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # Kotlin unit + Robolectric + screenshot tests
python -m unittest discover -s tools -p "test_*.py"   # Python guide-pipeline tests
python tools/check_docs.py            # docs hygiene: links, index, style
```

Details and expected results: [docs/testing.md](docs/testing.md).

## Documentation

The docs live in [`docs/`](docs/README.md) and follow a few written conventions (sentence-case
headings, verified links, a dated index). Start points:

- [docs/README.md](docs/README.md) — the docs index
- [docs/playback.md](docs/playback.md) — how the playback engine is built to survive bad providers
- [docs/epg.md](docs/epg.md) — how the app gets and matches its guide data
- [docs/data-pipeline-ops.md](docs/data-pipeline-ops.md) — how the guide feeds are built and published
- [docs/adr/](docs/adr/0001-media3-player-stack.md) — records of the significant design decisions

Update a doc in the same change as the code it describes; CI checks that every doc is indexed and
every link resolves.

## Feeds this repo publishes

| Feed | URL | Rebuilt |
|---|---|---|
| EPG guides | `https://github.com/EDGEL00P/CartelTv/releases/download/epg-latest/epg_<CODE>.xml.gz` | every 6 h ([epg.yml](.github/workflows/epg.yml)) |
| EPG index | same release, `manifest.json` (also mirrored to GitHub Pages) | every 6 h |
| Sports guide | `https://edgel00p.github.io/CartelTv/sports-guide.json` | every 30 min ([sports-guide.yml](.github/workflows/sports-guide.yml)) |

The app consumes these URLs directly; a weekly health check
([epg-health.yml](.github/workflows/epg-health.yml)) opens an issue when a guide preset dies.
