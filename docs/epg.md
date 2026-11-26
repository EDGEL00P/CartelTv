# Guide (EPG) in the app

*Status: current · Owner: repo maintainer · Last verified: 2026-11-27*

Where the on-device guide comes from, how it stays fresh, and how channels without usable ids are
matched anyway. The source-model decision is [ADR-0004](adr/0004-guide-source-model.md); how the
feeds themselves are built is [data-pipeline-ops.md](data-pipeline-ops.md).

## Sources

`EpgSync.sources` (`app/src/main/java/com/carteltv/tv/data/xtream/EpgSync.kt`) reads, in priority
order: the provider's own `xmltv.php` (or the custom URL that replaces it), then the extras the
viewer enabled from `EpgPresets` (`data/xtream/EpgPresets.kt`) — this repo's combined English
guide, its streaming and TVmaze guides, and curated country files. Only http(s) links, each once,
capped at `MAX_SOURCES = 6`, because each guide is a full download and parse on a TV box.

The preset URLs were verified answering 200 on 2026-11-21; the weekly
[epg-health.yml](../.github/workflows/epg-health.yml) check keeps watching them and opens an
issue when one dies.

## Sync

A refresh is due every 6 hours (`EpgFreshness.REFRESH_MS`); after a failure the retry backs off
to every 30 minutes and that backoff survives restarts. The download streams into a staging table
and only then replaces the programmes of channels the file actually covers — a failed or empty
download leaves the existing guide untouched, and a channel covered by no guide simply has no
programmes. The stored window keeps the past 24 hours and the coming 7 days.

## Matching channels to guide ids

`EpgMatcher` (`data/xtream/EpgMatcher.kt`) decides which guide channel answers for a provider
channel:

1. **Override** — a viewer-supplied pairing (stream id → guide id), if that guide id exists.
2. **Id stem** — the provider id and a guide id agree once country and quality tags are dropped.
3. **Exact name** — the cleaned channel name equals a guide display-name or id stem.
4. **Fuzzy** — the best token/trigram score, accepted only above 0.86 and clear of the runner-up
   by 0.07.

A provider id that a guide actually declares always wins and is never re-matched. Guards apply to
every name-based tier: both sides naming different countries is refused ("US|Discovery" never
takes another country's schedule); channel numbers are identity ("TSN 1" is never "TSN 2", "CTV +1"
is never "CTV", number words count as digits); time-zone feeds are identity ("CTV East" is never
"CTV West"), and feed ambiguity is refused rather than guessed.

## Display

`GuideModel` (`data/xtream/GuideModel.kt`) is pure guide maths: provider entries become ordered,
non-overlapping slots (a missing end borrows the next start), the grid window opens an hour before
now on a half-hour line, and cells are clipped to it with a minimum width. Sports and big events
render through `EventSlots` (`data/xtream/EventSlots.kt`), which is DST-aware via
`ZoneId.of("America/New_York")` for its venue clocks.

## Where the feeds come from

The guides the app can add as extras are built and published by CI — see
[data-pipeline-ops.md](data-pipeline-ops.md) for the workflow, its schedule, and how a broken feed
is detected and rolled back.
