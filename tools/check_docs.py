"""Docs hygiene: links resolve, every doc is indexed, fences carry a language tag, prose stays
within 120 columns, and external links answer.

Checks the repo README plus every *.md under docs/. Exit code 1 with one line per problem.

    python tools/check_docs.py [--skip-external]
"""

from __future__ import annotations

import argparse
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Iterator, List, Tuple

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
MAX_COLS = 120
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
EXTERNAL_TIMEOUT_S = 20
# Servers that refuse bots or HEAD outright; a live page behind these is not a broken link.
SOFT_CODES = {401, 403, 405, 429}


def iter_prose(lines: List[str]) -> Iterator[Tuple[int, str]]:
    """(1-based line number, line) pairs outside fenced code, refusing untagged fences."""
    in_fence = False
    for no, line in enumerate(lines, start=1):
        if line.startswith("```"):
            if in_fence:
                in_fence = False
            else:
                in_fence = True
                if not line[3:].strip():
                    yield no, "\x00FENCE_NO_LANGUAGE"
            continue
        if not in_fence:
            yield no, line


def fence_problems(lines: List[str]) -> List[str]:
    return [f"line {no}: fenced code block without a language tag" for no, line in iter_prose(lines)
            if line == "\x00FENCE_NO_LANGUAGE"]


def line_problems(lines: List[str], max_cols: int = MAX_COLS) -> List[str]:
    out = []
    for no, line in iter_prose(lines):
        if line == "\x00FENCE_NO_LANGUAGE":
            continue
        # Tables wrap poorly in prose form and a URL cannot be wrapped at all; both are exempt.
        if len(line) > max_cols and not line.lstrip().startswith("|") and "http" not in line:
            out.append(f"line {no}: {len(line)} columns (limit {max_cols})")
    return out


def links_in(lines: List[str]) -> Iterator[Tuple[int, str]]:
    for no, line in iter_prose(lines):
        if line == "\x00FENCE_NO_LANGUAGE":
            continue
        for m in LINK_RE.finditer(line):
            yield no, m.group(1)


def internal_link_problems(path: Path, lines: List[str]) -> List[str]:
    out = []
    for no, target in links_in(lines):
        if target.startswith(("http://", "https://", "mailto:")):
            continue
        resolved = (path.parent / target.split("#", 1)[0]).resolve()
        if not resolved.exists():
            out.append(f"line {no}: broken internal link {target}")
    return out


def external_links(files: List[Tuple[Path, List[str]]]) -> List[str]:
    seen = set()
    for _path, lines in files:
        for _no, target in links_in(lines):
            if target.startswith(("http://", "https://")) and target not in seen:
                seen.add(target)
    return sorted(seen)


def url_ok(url: str) -> str | None:
    """None when the link answers, a one-line reason when it does not."""
    req = urllib.request.Request(url, method="HEAD",
                                 headers={"User-Agent": "Mozilla/5.0 CartelTV-docs"})
    for attempt in (0, 1):
        try:
            with urllib.request.urlopen(req, timeout=EXTERNAL_TIMEOUT_S) as resp:
                return None if 200 <= resp.status < 400 else f"HTTP {resp.status}"
        except urllib.error.HTTPError as exc:
            if exc.code in SOFT_CODES:
                return None
            return f"HTTP {exc.code}"
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            if attempt == 1:
                return str(exc)
    return "unreachable"


def index_problems(files: List[Tuple[Path, List[str]]]) -> List[str]:
    """Every docs/**/*.md must be linked from docs/README.md; the index is its own entry point."""
    by_path = dict(files)
    index_lines = by_path.get(DOCS / "README.md")
    if index_lines is None:
        return ["docs/README.md: the index is missing"]
    linked = set()
    for _no, target in links_in(index_lines):
        if target.startswith(("http://", "https://", "mailto:")):
            continue
        linked.add((DOCS / target.split("#", 1)[0]).resolve())
    out = []
    for path in sorted(DOCS.rglob("*.md")):
        if path.resolve() == (DOCS / "README.md").resolve():
            continue
        if path.resolve() not in linked:
            out.append(f"{path.relative_to(ROOT)}: not indexed in docs/README.md")
    return out


def main(argv: List[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="check_docs")
    ap.add_argument("--skip-external", action="store_true",
                    help="do not fetch external links (offline runs)")
    args = ap.parse_args(argv)

    files: List[Tuple[Path, List[str]]] = []
    for path in [ROOT / "README.md", *sorted(DOCS.rglob("*.md"))]:
        if not path.is_file():
            print(f"{path.relative_to(ROOT)}: missing")
            return 1
        files.append((path, path.read_text(encoding="utf-8").splitlines()))

    problems = []
    for path, lines in files:
        rel = path.relative_to(ROOT)
        for msg in fence_problems(lines):
            problems.append(f"{rel}: {msg}")
        for msg in line_problems(lines):
            problems.append(f"{rel}: {msg}")
        for msg in internal_link_problems(path, lines):
            problems.append(f"{rel}: {msg}")
    problems.extend(index_problems(files))

    if not args.skip_external:
        for url in external_links(files):
            verdict = url_ok(url)
            if verdict:
                problems.append(f"external link dead ({verdict}): {url}")

    if problems:
        print("\n".join(problems))
        print(f"\n{len(problems)} problem(s)")
        return 1
    print(f"docs OK: {len(files)} files checked, "
          f"{len(external_links(files))} external links answered")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
