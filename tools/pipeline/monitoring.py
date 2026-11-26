"""Structured logging + lightweight metrics for the pipeline."""

from __future__ import annotations

import json
import logging
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Dict


def configure_logging(level: str = "INFO") -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


@dataclass
class Metrics:
    started_at: float = field(default_factory=time.time)
    records_processed: int = 0
    records_failed: int = 0
    sources_ok: int = 0
    sources_failed: int = 0
    durations: Dict[str, float] = field(default_factory=dict)

    def record(self, name: str, duration: float) -> None:
        self.durations[name] = duration

    def to_dict(self) -> dict:
        return {
            "started_at": self.started_at,
            "elapsed_s": round(time.time() - self.started_at, 3),
            "records_processed": self.records_processed,
            "records_failed": self.records_failed,
            "sources_ok": self.sources_ok,
            "sources_failed": self.sources_failed,
            "durations": self.durations,
        }

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), indent=2)


@contextmanager
def timed(metrics: Metrics, name: str):
    t0 = time.time()
    try:
        yield
    finally:
        metrics.record(name, round(time.time() - t0, 3))