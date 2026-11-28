"""Data-quality checks for guide batches."""

from __future__ import annotations

import gzip
import re
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List

from .exceptions import QualityError


def check_row_count(records: List[Any], min_rows: int = 1) -> None:
    if len(records) < min_rows:
        raise QualityError(f"row count {len(records)} < {min_rows}")


def check_null_rate(records: List[Dict[str, Any]], field: str, max_null_rate: float = 0.0) -> None:
    if not records:
        return
    nulls = sum(1 for r in records if r.get(field) is None)
    rate = nulls / len(records)
    if rate > max_null_rate:
        raise QualityError(f"{field} null rate {rate:.3f} > {max_null_rate}")


def check_unique(records: List[Dict[str, Any]], field: str) -> None:
    seen = set()
    for r in records:
        v = r.get(field)
        if v in seen:
            raise QualityError(f"duplicate {field}: {v}")
        seen.add(v)


_XMLTV_STOP = re.compile(r"^(\d{14})")


def _stop_epoch_utc(attr: str | None) -> float | None:
    """An XMLTV stop stamp's epoch seconds, zone-less read as UTC (the cleaner's convention)."""
    if not attr:
        return None
    m = _XMLTV_STOP.match(attr.strip())
    if not m:
        return None
    try:
        return datetime.strptime(m.group(1), "%Y%m%d%H%M%S").replace(tzinfo=timezone.utc).timestamp()
    except ValueError:
        return None


def check_future_programmes(path: Path, now: float | None = None) -> None:
    """A guide whose every programme is in the past is stale, whatever its timestamps say.

    This is the freshness gate that cannot be fooled by a fresh download of dead data: the
    merged guide must contain at least one programme that ends in the future.
    """
    now = now if now is not None else datetime.now(timezone.utc).timestamp()
    with gzip.open(path, "rb") as f:
        for _, el in ET.iterparse(f, events=("end",)):
            if el.tag == "programme":
                stop = _stop_epoch_utc(el.get("stop"))
                el.clear()
                if stop is not None and stop > now:
                    return
    raise QualityError(f"{path.name}: no programme ends in the future; the guide is stale")


def check_files_non_empty(paths: List[Path]) -> None:
    """The cleaner can exit 0 with an empty file; an empty guide is not a guide."""
    empty = [str(p) for p in paths if not Path(p).is_file() or Path(p).stat().st_size == 0]
    if empty:
        raise QualityError(f"{len(empty)} cleaned file(s) are empty or missing: {', '.join(empty)}")


def run_suite(records: List[Dict[str, Any]], checks: List[dict]) -> dict:
    results = {"passed": 0, "failed": 0, "errors": []}
    for c in checks:
        name = c.get("name", "check")
        try:
            if c["type"] == "row_count":
                check_row_count(records, c.get("min", 1))
            elif c["type"] == "null_rate":
                check_null_rate(records, c["field"], c.get("max", 0.0))
            elif c["type"] == "unique":
                check_unique(records, c["field"])
            elif c["type"] == "files_non_empty":
                check_files_non_empty(c["paths"])
            elif c["type"] == "future_programmes":
                check_future_programmes(Path(c["path"]))
            results["passed"] += 1
        except QualityError as exc:
            results["failed"] += 1
            results["errors"].append(f"{name}: {exc}")
    return results


def enforce_suite(records: List[Dict[str, Any]], checks: List[dict]) -> dict:
    """Run the checks and raise when any fails, so the gate stops the pipeline before publishing."""
    results = run_suite(records, checks)
    if results["failed"]:
        raise QualityError("; ".join(results["errors"]))
    return results