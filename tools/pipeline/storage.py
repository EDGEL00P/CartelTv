"""Storage helpers: atomic writes, partitioned paths, file-size checks."""

from __future__ import annotations

import gzip
import os
import shutil
from pathlib import Path
from typing import Iterable


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "wb") as fh:
        fh.write(data)
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, path)


def atomic_write_text(path: Path, text: str, encoding: str = "utf-8") -> None:
    atomic_write(path, text.encode(encoding))


def atomic_write_gzip(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with gzip.open(tmp, "wb") as fh:
        fh.write(data)
    os.replace(tmp, path)


def promote(path: Path) -> Path:
    """Publish a staged ``.part`` artifact: rename it to its final name, atomically."""
    final = path.with_suffix("")
    os.replace(path, final)
    return final


def partition_path(base: Path, *parts: str) -> Path:
    p = base
    for part in parts:
        p = p / part
    p.mkdir(parents=True, exist_ok=True)
    return p


def file_size_ok(path: Path, min_bytes: int = 1, max_bytes: int = 120 * 1024 * 1024) -> bool:
    if not path.exists():
        return False
    sz = path.stat().st_size
    return min_bytes <= sz <= max_bytes


def cleanup_tmp(base: Path) -> None:
    for p in base.rglob("*.tmp"):
        try:
            p.unlink()
        except OSError:
            pass