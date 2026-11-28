#!/usr/bin/env python3
"""Download the free guides listed in tools/guide_sources.json and slim them for the app.

Usage: python tools/fetch_guides.py SOURCES.json OUT_DIR RAW_DIR [--max-days 7]

Each source becomes OUT_DIR/epg_<NAME>.xml.gz, cleaned the same way as the EPG Share guides. A source
that fails to download or clean is skipped and reported; the run only fails when none succeed, so one
provider being down never stops the other guides from publishing.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

UA = "Mozilla/5.0 CartelTV-epg"
MAX_BYTES = 120 * 1024 * 1024
HERE = os.path.dirname(os.path.abspath(__file__))


def load_sources(path):
    with open(path, encoding="utf-8") as fh:
        sources = json.load(fh)["sources"]
    for s in sources:
        if s.get("kind") not in ("linear", "fast"):
            raise ValueError(f"{s.get('name')}: kind must be 'linear' or 'fast'")
    return sources


def download(url, dest, attempts=3):
    """Fetch url into dest, retrying; raises OSError after the last attempt or if the file is too large."""
    last = None
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as out:
                size = 0
                while chunk := resp.read(1 << 20):
                    size += len(chunk)
                    if size > MAX_BYTES:
                        raise OSError(f"larger than {MAX_BYTES // (1 << 20)} MB")
                    out.write(chunk)
            if size == 0:
                raise OSError("empty response")
            return size
        except OSError as exc:
            last = exc
            time.sleep(2 * (attempt + 1))
    raise OSError(str(last))


def clean(raw, out, max_days):
    subprocess.run(
        [sys.executable, os.path.join(HERE, "clean_epg.py"), raw, out, "--max-days", str(max_days), "--slim"],
        check=True, stdout=subprocess.DEVNULL,
    )


def fetch_all(sources, out_dir, raw_dir, max_days=7, fetch=download, cleaner=clean):
    """Return (built, failed): names of the guides written and (name, reason) for the ones skipped."""
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(raw_dir, exist_ok=True)
    built, failed = [], []
    for s in sources:
        raw, out = os.path.join(raw_dir, f"{s['name']}.xml.gz"), os.path.join(out_dir, f"epg_{s['name']}.xml.gz")
        try:
            fetch(s["url"], raw)
            cleaner(raw, out, max_days)
            if not (os.path.exists(out) and os.path.getsize(out) > 0):
                raise OSError("cleaned guide is empty")
            built.append(s["name"])
        except (OSError, subprocess.CalledProcessError) as exc:
            failed.append((s["name"], str(exc)))
            if os.path.exists(out):
                os.remove(out)
    return built, failed


def main(argv):
    if len(argv) < 4:
        print(__doc__)
        return 2
    max_days = int(argv[argv.index("--max-days") + 1]) if "--max-days" in argv else 7
    built, failed = fetch_all(load_sources(argv[1]), argv[2], argv[3], max_days)
    for name in built:
        print(f"built   {name}")
    for name, reason in failed:
        print(f"skipped {name}: {reason}")
    print(f"{len(built)} guides built, {len(failed)} skipped")
    return 0 if built else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
