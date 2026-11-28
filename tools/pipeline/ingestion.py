"""Batch ingestion with retry, validation hook, dead-letter queue, metadata."""

from __future__ import annotations

import json
import os
import time
import urllib.request
from pathlib import Path
from typing import Callable, List, Optional

from .config import PipelineConfig, SourceConfig
from .exceptions import IngestionError
from .monitoring import Metrics

UA = "Mozilla/5.0 CartelTV-epg"
MAX_BYTES = 120 * 1024 * 1024


def download(url: str, dest: Path, attempts: int = 3, backoff: float = 2.0, max_backoff: float = 30.0, max_bytes: int = MAX_BYTES) -> int:
    last: Optional[Exception] = None
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            size = 0
            with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as out:
                while chunk := resp.read(1 << 20):
                    size += len(chunk)
                    if size > max_bytes:
                        raise OSError(f"larger than {max_bytes // (1 << 20)} MB")
                    out.write(chunk)
            if size == 0:
                raise OSError("empty response")
            return size
        except (OSError, urllib.error.URLError) as exc:
            last = exc
            time.sleep(min(backoff * 2 ** attempt, max_backoff))
    raise IngestionError(str(last))


class BatchIngester:
    def __init__(self, config: PipelineConfig, metrics: Optional[Metrics] = None):
        self.config = config
        self.metrics = metrics or Metrics()
        self.dead_letter: List[dict] = []

    def fetch_one(self, source: SourceConfig, raw_dir: Path, cleaner: Optional[Callable] = None) -> Path:
        raw_dir.mkdir(parents=True, exist_ok=True)
        dest = raw_dir / f"raw_{source.name}.xml.gz"
        try:
            download(source.url, dest, attempts=self.config.retry.attempts,
                     backoff=self.config.retry.backoff_seconds,
                     max_backoff=self.config.retry.max_backoff_seconds)
        except IngestionError as exc:
            self.dead_letter.append({"source": source.name, "error": str(exc), "stage": "download"})
            self.metrics.sources_failed += 1
            raise
        self.metrics.sources_ok += 1
        return dest

    def fetch_all(self, raw_dir: Path, cleaner: Optional[Callable] = None) -> List[Path]:
        raw_dir.mkdir(parents=True, exist_ok=True)
        out: List[Path] = []
        for src in self.config.sources:
            try:
                out.append(self.fetch_one(src, raw_dir, cleaner))
            except IngestionError:
                continue
        return out

    def save_dead_letter_queue(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            for rec in self.dead_letter:
                fh.write(json.dumps(rec) + "\n")