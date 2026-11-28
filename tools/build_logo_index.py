"""Builds app/src/main/assets/channel_logos.tsv from the iptv-org public API.

The index is plain text on purpose: the Android build strips a ".gz" from asset names, and the APK
compresses it anyway.

Usage: python tools/build_logo_index.py [--check "CA| TSN 1 HD" ...]

Each line is `key<TAB>country<TAB>url`. `key` is fold_name(): the name lower-cased, accents and
punctuation removed, "+" read as "plus", quality tags dropped. The same function exists in Kotlin
(ChannelLogos.fold) and the two must stay identical; ChannelLogosTest pins shared examples.
"""
import gzip
import json
import re
import sys
import unicodedata
import urllib.request
from pathlib import Path

API = "https://iptv-org.github.io/api/"
OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/channel_logos.tsv"
QUALITY = {"8k", "4k", "uhd", "fhd", "hd", "sd", "1080p", "1080", "720p", "720",
           "hevc", "h265", "h264", "raw", "50fps", "60fps", "2160p"}
MAX_WIDTH = 800
MIN_WIDTH = 60


def fold_name(name: str) -> str:
    s = unicodedata.normalize("NFD", name)
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    s = s.lower().replace("+", " plus ")
    tokens = [t for t in re.split(r"[^a-z0-9]+", s) if t and t not in QUALITY]
    return "".join(tokens)


def fetch(name: str):
    with urllib.request.urlopen(API + name, timeout=120) as r:
        return json.load(r)


def best_logo(logos):
    ok = [l for l in logos if l["format"] in ("PNG", "JPEG", "WebP") and l.get("url", "").startswith("http")
          and l.get("width", 0) >= MIN_WIDTH]
    if not ok:
        return None
    ok.sort(key=lambda l: (not l.get("in_use", False), l.get("feed") is not None,
                           l["width"] > MAX_WIDTH, abs(l["width"] - 400)))
    return ok[0]["url"]


def build():
    channels = fetch("channels.json")
    logos = {}
    for l in fetch("logos.json"):
        logos.setdefault(l["channel"], []).append(l)
    rows = {}
    for c in channels:
        if c.get("closed") or c.get("is_nsfw"):
            continue
        url = best_logo(logos.get(c["id"], []))
        if not url:
            continue
        for n in [c["name"], *c.get("alt_names", [])]:
            key = fold_name(n)
            if len(key) >= 2:
                rows.setdefault((key, c["country"].upper()), url)
    return rows


def main():
    rows = build()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        for (key, country), url in sorted(rows.items()):
            f.write(f"{key}\t{country}\t{url}\n")
    print(f"{len(rows)} entries, {OUT.stat().st_size / 1e6:.2f} MB")
    if "--check" in sys.argv:
        for q in sys.argv[sys.argv.index("--check") + 1:]:
            k = fold_name(re.sub(r"^\s*([A-Za-z]{2,4})\s*[:|\-]+\s*", "", q))
            print(q, "->", [(c, u) for (kk, c), u in rows.items() if kk == k][:3])


if __name__ == "__main__":
    main()
