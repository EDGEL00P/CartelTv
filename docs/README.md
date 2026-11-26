# CartelTV docs

*Owner: repo maintainer · Last verified: 2026-11-27*

Every document in `docs/` (including archives) is listed here; `tools/check_docs.py` enforces that
in CI, together with link integrity and the style rules at the bottom of this page. Diátaxis types
are noted per entry: explanation (why it is built this way), how-to (a task, step by step),
reference (facts to look up), status (what exists today).

## Index

| Document | Type | Answers |
|---|---|---|
| [playback.md](playback.md) | explanation | how the playback engine survives bad providers (HLS / TS / DASH, live, recovery) |
| [troubleshooting-playback.md](troubleshooting-playback.md) | how-to | a channel misbehaves — what to check, in order |
| [epg.md](epg.md) | explanation | where guide data comes from and how channels are matched to it |
| [data-pipeline-architecture.md](data-pipeline-architecture.md) | explanation | how the guide pipeline is built (production and local lanes) |
| [data-pipeline-ops.md](data-pipeline-ops.md) | how-to / runbook | building, publishing, verifying, and rolling back the guide feeds |
| [nuvio-on-demand-parity.md](nuvio-on-demand-parity.md) | status | what On Demand can do today, and what is still open |
| [naming-conventions.md](naming-conventions.md) | reference | how names are chosen in this codebase |
| [changelog/naming.md](changelog/naming.md) | changelog | the dated rename ledger |
| [testing.md](testing.md) | how-to | how to run every test suite and what CI runs |
| [adr/0001-media3-player-stack.md](adr/0001-media3-player-stack.md) | decision | Media3/ExoPlayer as the engine, one shared recipe |
| [adr/0002-live-failure-ladder.md](adr/0002-live-failure-ladder.md) | decision | fail-fast segment retries under a bounded recovery ladder |
| [adr/0003-epg-one-generation-publishing.md](adr/0003-epg-one-generation-publishing.md) | decision | guide releases publish one generation at a time |
| [adr/0004-guide-source-model.md](adr/0004-guide-source-model.md) | decision | provider guide first, curated extras, guarded matching |
| [archive/nuvio-parity-plan-2026-11.md](archive/nuvio-parity-plan-2026-11.md) | archive | the original On Demand parity plan, superseded by the status doc |

## Conventions

- **Update docs with code.** A change that moves a documented fact ships in the same commit as the
  doc update; reviewers treat a stale doc as a bug.
- **Every claim is checkable.** Prefer naming the code (`file.kt`, or a path) over paraphrasing
  numbers that drift. Default values live in code, not in prose.
- **One state column, dated.** Status docs say what *is*, with a state per item; superseded plans
  move to `archive/` with the date in the filename.
- **Style:** sentence-case headings, fenced code with a language tag, prose wrapped at ≤ 120
  columns (tables exempt), internal links relative and resolving.
- **Metadata:** each doc carries an owner and a `Last verified:` date, refreshed when its facts are
  re-checked against the code.
