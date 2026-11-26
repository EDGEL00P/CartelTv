# 4. Provider guide first, a few curated extras, matching with guards

*Status: Accepted · Date: recorded 2026-11-27 (the choice predates this record)*

## Context

Every IPTV provider ships some XMLTV guide via `xmltv.php`, but coverage varies wildly: some
providers name almost nothing, others point their `epg_channel_id` at guides they do not
actually carry. The app can add extra guides — this repo publishes curated ones — but each guide
is a full download and parse on a TV box, so the list must stay small and prioritized. Matching
channels to guide ids by name is error-prone: "US|Discovery" must not take another country's
schedule, "TSN 1" is not "TSN 2", "CTV East" is not "CTV West".

## Decision

- **Sources.** `EpgSync.sources` reads the provider's guide (or the custom URL that replaces it)
  first, then the extras the viewer enabled from `EpgPresets` — http(s) only, de-duplicated,
  capped at `MAX_SOURCES = 6` in priority order.
- **Sync.** A refresh is due every 6 hours (`EpgFreshness.REFRESH_MS`), backed off to 30-minute
  retries after a failure that survives restarts. The download streams into a staging table and
  only then replaces the programmes of channels the file actually covers; a failed or empty
  download leaves the existing guide untouched.
- **Matching** (`EpgMatcher`): a direct provider id that exists in the guide always wins and is
  never touched. The rest go through tiers, strongest first — a viewer-supplied override, an id
  stem match with country/quality tags dropped, an exact cleaned-name match, then a fuzzy match
  accepted only above a floor (0.86) and clear of its runner-up (margin 0.07). Country, number
  and time-zone-feed guards apply to every name-based tier; ambiguous matches are refused rather
  than guessed.

## Consequences

- Guide quality is dominated by the provider's own ids; name matching only fills the gaps and
  cannot override a working pairing.
- Six-guide cap plus 6-hour cadence bounds the TV box's work per refresh.
- The fuzzy tier can still mis-pair genuinely ambiguous channels; its confidence is computed but
  not yet surfaced in the UI (deferred).
- Dead extra guides waste slots until removed; the weekly `epg-health` check catches them by
  opening an issue.
