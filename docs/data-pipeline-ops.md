# Guide data pipeline — operations

*Status: current · Owner: repo maintainer · Last verified: 2026-11-27*

How the guide feeds are built, published, verified, and rolled back. The production lane is the
GitHub Actions workflow; the `python -m tools.pipeline` CLI is a local/manual lane over the same
scripts — see [the architecture doc](data-pipeline-architecture.md) for how the two relate.

## Production run (what users get)

[`.github/workflows/epg.yml`](../.github/workflows/epg.yml) — schedule `17 */6 * * *` (every 6 h),
manual dispatch available, concurrency group `epg` (a run already in flight is not cancelled),
45-minute timeout. Steps: clean every `epgshare01.online` guide (fails under 20 built), build the
sports, streaming and TVmaze guides, fetch the extra free guides from `tools/guide_sources.json`,
merge the English set per `tools/english_guide_codes.json`, write `manifest.json`, publish.

Publishing replaces the release **in one generation**: every old asset is deleted first, then each
file uploads with retries. A fetch during that window finds no asset; the app keeps its stored
guide and retries in 30 minutes, so it never mixes guides from two generations
([ADR-0003](adr/0003-epg-one-generation-publishing.md)). There is no automatic rollback generation
— see Rollback.

**Verify a run** (after a scheduled run or a fix):

```bash
gh run list --workflow epg.yml --limit 3
gh release view epg-latest --json assets --jq '.assets | length'   # expect the full set
gh release download epg-latest --pattern manifest.json --output - # generated: now-ish
```

## Local pipeline run

The only part of `tools/` with dependencies (pinned):

```bash
python -m pip install -r tools/pipeline/requirements.txt
python -m tools.pipeline run tools/guide_sources.json --out-dir out/guides --raw-dir out/raw
```

Options: `--max-days N` (default 7), `--log-level DEBUG|INFO|WARNING` (default INFO). A YAML
config for embedding the modules: `PipelineConfig.from_yaml`
(`tools/pipeline/pipeline.example.yaml`).

**Verify:** exit code 0 plus a metrics block, `out/guides/manifest.json` refreshed, no `*.part`
files left, and `out/guides/dead_letter.jsonl` empty or explainable.

## Gates

- **Ingestion** fails the run when fewer than `quality.min_sources_ok` sources succeed or the
  failed share exceeds `max_failed_ratio` (defaults in `tools/pipeline/config.py`). A single failed
  source among many is dead-lettered and skipped.
- **Quality** fails the run when a cleaned output is empty or missing, or when the merged guide
  contains no programme that ends in the future (`check_future_programmes` — a fresh download of
  dead data still fails this).
- **Publishing** happens only after the gates: outputs are staged as `.part` and promoted, then the
  manifest is written. A failed run leaves the previous outputs and manifest in place; its `.part`
  files stay behind for inspection.

## Outputs

- Local: `out/guides/cleaned_raw_<source>.xml` (gzip content), `out/guides/merged.xml.gz`,
  `out/guides/manifest.json`, `out/guides/dead_letter.jsonl`.
- Production: `dist/epg_<CODE>.xml.gz` on the `epg-latest` release; `manifest.json` lists every
  file with its size and generation time. GitHub Pages mirrors the release
  ([`pages.yml`](../.github/workflows/pages.yml)).

## Is it broken? — triage

| Symptom | Check | Likely cause and action |
|---|---|---|
| App guides stale | `gh run list --workflow epg.yml`; release `manifest.json` age | Workflow failed or publish broke — read the run log, fix, re-run |
| "too few guides built" in the run log | count of `guides built:` | epgshare01 index trouble or mass cleaning failures; the log names skipped codes |
| One guide missing from the release | `english_guide_codes.json` vs the log's skipped codes | Its clean failed (log names the code) or it is not in the merge list yet |
| Combined guide empty or absent | merge step log | Codes present but sources dead; verify with `python tools/check_epgs.py` |
| Preset URLs dead | open issues labeled `epg-health` | The weekly [epg-health.yml](../.github/workflows/epg-health.yml) check already filed it; fix or drop the preset |
| Local run exits 1 | the printed failure and `dead_letter.jsonl` | Named source failed or a gate tripped; inspect the named `.part` file |

Do not relax `min_sources_ok` or `max_failed_ratio` to make a run pass — they are the guard against
publishing a mostly-dead guide. Fix the source or remove it from the list instead.

## Rollback

- **Local:** nothing to do — a failed run never replaced the previous outputs or manifest. Rerun
  after fixing the cause; ingestion is idempotent.
- **Production:** there is no previous generation kept on the release (deleted before upload, by
  design). Roll back by re-running `epg.yml` from a known-good commit; if a guide is fundamentally
  broken, the last resort is removing it from the merge list or presets and re-running. A bad
  generation is at most 6 hours old.

## Monitoring

- [epg-health.yml](../.github/workflows/epg-health.yml) — weekly (Mon 05:43 UTC) checks every
  `EpgPresets` URL and opens or updates an issue labeled `epg-health` when guides die.
- Local runs print structured logs and a metrics block on success and failure.
- `pages.yml` republishes the Pages mirror after each `epg` and `release` workflow; a stale mirror
  means one of those failed, not the release.

## Testing the pipeline itself

`python -m unittest discover -s tools -p "test_*.py"` (CI's `python-tools` job runs this plus
`compileall` and `tools/bughunt.py`). See [testing.md](testing.md).
