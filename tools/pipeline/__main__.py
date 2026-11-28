"""CLI entrypoint: python -m tools.pipeline ..."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .config import PipelineConfig
from .exceptions import IngestionError
from .ingestion import BatchIngester
from .monitoring import configure_logging, Metrics
from .orchestration import Orchestrator, Task
from .storage import atomic_write_text, promote
from .transformation import clean_epg, merge_guides
from .quality import enforce_suite


def build_default_config(args) -> PipelineConfig:
    data = {
        "sources": json.loads(Path(args.sources).read_text(encoding="utf-8"))["sources"],
        "output_dir": args.out_dir,
        "raw_dir": args.raw_dir,
        "max_days": args.max_days,
    }
    return PipelineConfig.from_dict(data)


def cmd_run(args) -> int:
    configure_logging(args.log_level)
    cfg = build_default_config(args)
    metrics = Metrics()
    ingester = BatchIngester(cfg, metrics)
    out_dir = Path(cfg.output_dir)
    raw_dir = Path(cfg.raw_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    raw_dir.mkdir(parents=True, exist_ok=True)

    orch = Orchestrator(metrics)

    def fetch(ctx):
        raws = ingester.fetch_all(raw_dir)
        total = len(cfg.sources)
        failed = total - len(raws)
        if len(raws) < cfg.quality.min_sources_ok:
            raise IngestionError(
                f"only {len(raws)} of {total} source(s) succeeded; at least {cfg.quality.min_sources_ok} required"
            )
        if total and failed / total > cfg.quality.max_failed_ratio:
            raise IngestionError(
                f"{failed} of {total} sources failed (limit {cfg.quality.max_failed_ratio:.0%})"
            )
        return raws

    def clean(ctx):
        raws = ctx["fetch"]
        cleaned = []
        for r in raws:
            # Staged as .part: a failed run must never replace a published output.
            # r.stem keeps the raw file's ".xml", so the promoted name is cleaned_raw_<source>.xml.
            out = out_dir / f"cleaned_{r.stem}.part"
            clean_epg(r, out, max_days=cfg.max_days)
            cleaned.append(out)
        return cleaned

    def merged(ctx):
        out = out_dir / "merged.xml.gz.part"
        merge_guides(ctx["clean"], out)
        return out

    def quality(ctx):
        recs = [{"id": i, "source": p.name} for i, p in enumerate(ctx["clean"])]
        return enforce_suite(
            recs,
            [
                {"type": "row_count", "min": 1, "name": "cleaned_outputs"},
                {"type": "files_non_empty", "paths": ctx["clean"], "name": "cleaned_files"},
                # Fresh download or not, a merged guide with nothing airing is stale data.
                {"type": "future_programmes", "path": ctx["merge"], "name": "merged_guide_fresh"},
            ],
        )

    def store(ctx):
        # Publishing is one move: the staged artifacts are renamed into place only after
        # every gate has passed, and the manifest lands last. A failed run leaves the
        # previous outputs and the previous manifest untouched; its .part files stay
        # behind for inspection and are overwritten by the next run.
        cleaned = [promote(p) for p in ctx["clean"]]
        merged = promote(ctx["merge"])
        manifest = {
            "merged": str(merged),
            "cleaned": [str(p) for p in cleaned],
            "quality": ctx["quality"],
            "dead_letter": len(ingester.dead_letter),
            "metrics": metrics.to_dict(),
        }
        atomic_write_text(out_dir / "manifest.json", json.dumps(manifest, indent=2))
        return out_dir / "manifest.json"

    orch.add(Task("fetch", fetch, retries=1))
    orch.add(Task("clean", clean, deps=["fetch"], retries=1))
    orch.add(Task("merge", merged, deps=["clean"], retries=1))
    orch.add(Task("quality", quality, deps=["merge"], retries=0))
    orch.add(Task("store", store, deps=["quality"], retries=1))
    try:
        orch.run()
    except Exception as exc:  # noqa: BLE001
        print(f"pipeline failed: {exc}")
        ingester.save_dead_letter_queue(out_dir / "dead_letter.jsonl")
        print(metrics.to_json())
        return 1
    ingester.save_dead_letter_queue(out_dir / "dead_letter.jsonl")
    print(metrics.to_json())
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="carteltv-pipeline")
    sub = parser.add_subparsers(dest="cmd", required=True)

    run = sub.add_parser("run", help="run the guide pipeline")
    run.add_argument("sources", help="path to guide_sources.json")
    run.add_argument("--out-dir", default="out/guides", help="output directory")
    run.add_argument("--raw-dir", default="out/raw", help="raw download directory")
    run.add_argument("--max-days", type=int, default=7)
    run.add_argument("--log-level", default="INFO")
    run.set_defaults(func=cmd_run)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())