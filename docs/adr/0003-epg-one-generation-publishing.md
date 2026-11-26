# 3. Guide releases publish one generation at a time

*Status: Accepted · Date: recorded 2026-11-27 (the choice predates this record)*

## Context

The app fetches its EPG guides directly from the `epg-latest` GitHub release
(`EpgPresets.kt` builds the URLs). A release "upload" is per-asset: replacing twenty-odd guide
files one by one means a client fetching mid-update can combine a fresh guide with a stale one —
channels matched against one generation's ids, programmes from another. Mixed generations are
worse than a short wait, because they corrupt the guide silently.

Alternatives considered: a per-generation tag (churns every URL the app stores, and every preset
would need tag plumbing) and a single combined archive for everything (one giant download on a TV
box, and per-country guides disappear).

## Decision

Publish in one generation (`.github/workflows/epg.yml`):

1. Delete every existing asset on `epg-latest` first.
2. Upload the new assets one by one, each with retries, so one flaky upload cannot leave the
   release half-empty.

Accept the brief window in which the release has no assets. The app is built for it: a failed
fetch keeps the last stored guide and retries in 30 minutes (`EpgFreshness.RETRY_MS`), so the
window costs at most a delayed refresh, never a mixed generation.

## Consequences

- No client can ever mix generations; freshness degrades gracefully instead.
- There is no automatic rollback generation — the previous assets are gone by design. A bad
  generation is repaired by re-running the workflow; it is at most 6 hours old.
- `gh release upload` flakes ("HTTP 400: Bad Content-Length") on empty files and mid-batch
  hiccups, so the workflow skips empty outputs and retries each file — both documented in the
  workflow itself.
