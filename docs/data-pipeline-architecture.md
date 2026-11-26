# Guide data pipeline — architecture

*Status: current · Owner: repo maintainer · Last verified: 2026-11-27*

## Scope: two pipelines, one set of scripts

There are two ways the EPG (TV guide) XML gets built, and only one of them ships to users:

1. **Production** — the GitHub Actions workflow
   [`.github/workflows/epg.yml`](../.github/workflows/epg.yml) runs on a schedule (every 6 hours),
  builds `dist/epg_<CODE>.xml.gz` guides with the standalone scripts in `tools/`, and publishes
  them to the rolling `epg-latest` GitHub release. The app downloads those URLs directly
  (`app/src/main/java/com/carteltv/tv/data/xtream/EpgPresets.kt`), so this lane is the one users
  depend on. [How it is run and verified](data-pipeline-ops.md).
2. **Local / manual** — the `tools/pipeline` package (`python -m tools.pipeline run`) wraps the
  same scripts in a structured, retry-aware DAG with a dead-letter queue and a quality gate. No
  workflow invokes it; it exists to run a local batch of `tools/guide_sources.json` cleanly and to
  be embedded elsewhere (`PipelineConfig.from_yaml`, `tools/pipeline/pipeline.example.yaml`).

Both lanes use the same cleaners and merger (`tools/clean_epg.py`, `tools/merge_guides.py`), so a
fix to cleaning applies to both. The sports-guide JSON feed is a separate pipeline
([`sports-guide.yml`](../.github/workflows/sports-guide.yml), published to GitHub Pages) and is out
of scope here.

## Production flow

```text
epgshare01.online index ──► clean_epg.py per guide ──► dist/epg_<CODE>.xml.gz
tools/guide_sources.json ──► fetch_guides.py ──► dist/epg_<CODE>.xml.gz
tools/sports_guide.py ──► dist/epg_SPORTS.xml.gz
tools/streaming_guide.py, tools/tvmaze_guide.py ──► dist/epg_<CODE>.xml.gz
dist/epg_*.xml.gz ──► merge_guides.py --codes-json tools/english_guide_codes.json
                    ──► dist/epg_ENGLISH_ALL.xml.gz
dist ──► manifest.json ──► gh release epg-latest (old assets deleted, then upload)
```

The English-merge code list lives in one place, `tools/english_guide_codes.json`, shared by the
workflow; adding a code there before its guide builds is safe (missing codes are skipped). The
publish step deletes the previous generation's assets before uploading, so the app can never mix
a fresh guide with a stale one mid-batch — see
[ADR-0003](adr/0003-epg-one-generation-publishing.md) for why that trade-off was taken.

## Local pipeline flow

```text
guide_sources.json
   -> ingestion  (download with retries, size guard, dead-letter queue, min-sources gate)
   -> clean      (clean_epg.py per source, written as cleaned_raw_<source>.xml.part)
   -> merge      (merge_guides.py over the cleaned files, written as merged.xml.gz.part)
   -> quality    (row count, non-empty files, future programmes)
   -> store      (promote the .part files to their final names, then write manifest.json)
```

## Components (`tools/pipeline/`)

- `config.py` — pydantic config (sources, retry, quality); the defaults live here, not in docs.
- `ingestion.py` — `BatchIngester`: downloads with exponential backoff (2 s doubling, capped by
  `retry.max_backoff_seconds`), a 120 MB per-source size guard (`MAX_BYTES`), dead-letter records.
- `validation.py` — record/batch schema validation.
- `transformation.py` — subprocess wrappers around the standalone cleaner and merger.
- `quality.py` — the checks and `enforce_suite`, the gate that stops a bad batch.
- `storage.py` — atomic writes, the `.part` → final `promote`, size checks.
- `orchestration.py` — the DAG runner with retries and dependency ordering.
- `monitoring.py` — structured logging and the metrics block.
- `exceptions.py`, `pipeline.example.yaml`, `tests/` — error types, an embeddable config example,
  and the unit tests run by CI.

## Storage and the publishing guarantee

- Raw downloads: `out/raw/raw_<name>.xml.gz`
- Cleaned (gzip content): `out/guides/cleaned_raw_<name>.xml`
- Merged: `out/guides/merged.xml.gz`
- Manifest: `out/guides/manifest.json`
- Dead-letter: `out/guides/dead_letter.jsonl`

Artifacts are **staged** as `.part` files and promoted (an atomic rename) only after every gate
has passed; the manifest is written last. A failed run therefore leaves the previous outputs and
the previous manifest untouched — presence of a final name means the batch passed. Failed runs
leave their `.part` files behind for inspection; the next run overwrites them.

## Failure modes and recovery

- A single source failing is dead-lettered and skipped; the run fails when fewer than
  `quality.min_sources_ok` sources succeed or the failed share exceeds `max_failed_ratio`
  (defaults in `config.py`).
- Downloads retry with exponential backoff before a source is declared failed.
- The quality gate — including the freshness check `check_future_programmes`, which rejects a
  merged guide whose every programme already ended — raises before anything is promoted, so a
  stale or empty batch never publishes.

## Scaling and cost

- Sources are independent; ingestion runs them sequentially today and can be parallelized later
  through the orchestrator.
- Per-source downloads are bounded by `ingestion.MAX_BYTES`; split a source that grows past it.
- Runs on CI and developer machines; no cloud compute. Artifacts are gzip XML.
