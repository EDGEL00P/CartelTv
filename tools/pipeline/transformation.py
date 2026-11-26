"""Transformation helpers wrapping existing clean/merge tools via subprocess."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent


def clean_epg(raw: Path, out: Path, max_days: int = 7, slim: bool = True) -> None:
    cmd = [sys.executable, str(HERE / "clean_epg.py"), str(raw), str(out),
           "--max-days", str(max_days)]
    if slim:
        cmd.append("--slim")
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)


def merge_guides(inputs: list[Path], out: Path) -> None:
    cmd = [sys.executable, str(HERE / "merge_guides.py"), str(out), *[str(p) for p in inputs]]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)