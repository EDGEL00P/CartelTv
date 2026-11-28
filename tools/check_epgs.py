"""Checks every EpgPresets guide: does it download, parse, and still hold current programmes?

Usage: python tools/check_epgs.py [--remove]

A guide is dead when it fails to download or parse, has no programmes, or none of its programmes
end in the future. --remove deletes dead entries from EpgPresets.kt.
"""
import gzip
import os
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

from merge_guides import PRESETS, download  # noqa: E402  (same folder)

LABEL = re.compile(r'^\s*p\("([^"]+)", "([A-Z0-9_]+)"\),\s*$')


def parse_time(s):
    from clean_epg import parse_time as pt
    return pt(s)


def check(code, cache):
    try:
        path = download(code, cache)
        channels = programmes = current = 0
        now = time.time()
        with gzip.open(path, "rb") as f:
            for _, el in ET.iterparse(f, events=("end",)):
                if el.tag == "channel":
                    channels += 1
                elif el.tag == "programme":
                    programmes += 1
                    stop = parse_time(el.get("stop"))
                    if stop and stop > now:
                        current += 1
                el.clear()
        if programmes == 0:
            return False, f"no programmes ({channels} channels)"
        if current == 0:
            return False, f"all {programmes} programmes are in the past"
        return True, f"{channels} channels, {programmes} programmes, {current} current"
    except BaseException as e:  # unreachable, corrupt gzip, bad XML
        return False, f"{type(e).__name__}: {str(e)[:80]}"


def main():
    cache = Path(os.environ.get("TEMP", ".")) / "opencode" / "epg_cache"
    cache.mkdir(parents=True, exist_ok=True)
    lines = PRESETS.read_text(encoding="utf-8").splitlines()
    dead = []
    for line in lines:
        m = LABEL.match(line)
        if not m:
            continue
        ok, why = check(m.group(2), cache)
        print(f"{'OK  ' if ok else 'DEAD'} {m.group(1)} ({m.group(2)}): {why}", flush=True)
        if not ok:
            dead.append(line)
    if "--remove" in sys.argv and dead:
        PRESETS.write_text("\n".join(l for l in lines if l not in dead) + "\n", encoding="utf-8")
        print(f"removed {len(dead)} dead guide(s) from {PRESETS.name}")


if __name__ == "__main__":
    main()
