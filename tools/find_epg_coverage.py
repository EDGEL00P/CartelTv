"""Measures which online XMLTV guides contain the channel ids your provider uses.

Usage: python tools/find_epg_coverage.py provider_epg_ids.txt [--min 3] [--out report.tsv] [--fold] [--cache ids.json]

--fold compares ids ignoring spelling ("A&E.us" = "aande.us", ".us2" = ".us", "@SD" dropped). --cache keeps each
guide's channel ids on disk so a second run (for example with --fold) needs no downloads.

Candidates are every country file on iptv-epg.org and every EPG Share (epgshare01.online) file. Each is read
only as far as its channel list (guides declare every channel before the first programme), so a 200 MB file
costs a few megabytes. For each one the report shows how many of your ids it holds, then picks guides greedily:
the one covering the most still-uncovered ids first, so a handful of files cover as much as possible.
"""
import json
import os
import re
import sys
import time
import urllib.request
import zlib
from concurrent.futures import ThreadPoolExecutor

UA = {"User-Agent": "CartelTV-epg-coverage/1.0"}
CHANNEL_ID = re.compile(rb'<channel\s+id="([^"]*)"')
MAX_HEAD = 40 * 1024 * 1024


def listing(url, pattern):
    try:
        req = urllib.request.Request(url, headers=UA)
        html = urllib.request.urlopen(req, timeout=60).read().decode("utf-8", "ignore")
    except Exception as e:
        print(f"could not list {url}: {e}", file=sys.stderr)
        return []
    return sorted(set(re.findall(pattern, html)))


def candidates():
    out = []
    for f in listing("https://iptv-epg.org/", r"files/(epg-[A-Za-z0-9_\-]+\.xml\.gz)"):
        out.append(("iptv-epg.org", f, "https://iptv-epg.org/files/" + f))
    for f in listing("https://epgshare01.online/epgshare01/", r"(epg_ripper_[A-Z0-9_]+\.xml\.gz)"):
        if "ALL_SOURCES" in f:
            continue  # 200 MB, and just the union of the others
        out.append(("epgshare01", f, "https://epgshare01.online/epgshare01/" + f))
    return out


def channel_ids(url):
    """Ids declared before the first <programme>, streaming and stopping early."""
    d = zlib.decompressobj(16 + zlib.MAX_WBITS)
    buf, ids, total = b"", set(), 0
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        while total < MAX_HEAD:
            chunk = r.read(1 << 16)
            if not chunk:
                break
            total += len(chunk)
            try:
                data = d.decompress(chunk)
            except zlib.error:
                data = chunk  # served as plain XML
            buf += data
            cut = buf.find(b"<programme")
            if cut >= 0:
                ids.update(m.decode("utf-8", "ignore") for m in CHANNEL_ID.findall(buf[:cut]))
                return ids
            last = buf.rfind(b"<channel")
            if last > 0:
                ids.update(m.decode("utf-8", "ignore") for m in CHANNEL_ID.findall(buf[:last]))
                buf = buf[last:]
    ids.update(m.decode("utf-8", "ignore") for m in CHANNEL_ID.findall(buf))
    return ids


QUALITY = {"hd", "sd", "fhd", "uhd", "4k"}


def norm(channel_id):
    """Spelling-insensitive id: 'A&E.us', 'aande.us' and 'AandE.us2@SD' all become 'aande.us'."""
    s = channel_id.lower().split("@", 1)[0].strip()
    parts = [p for p in s.split(".") if p]
    if not parts:
        return ""
    country = re.sub(r"\d+$", "", parts[-1]) if len(parts) > 1 else ""
    country = {"gb": "uk"}.get(country, country)
    stem_parts = parts[:-1] if len(parts) > 1 else parts
    stem = "".join(p for p in stem_parts if p not in QUALITY).replace("&", "and").replace("+", "plus")
    stem = re.sub(r"[^a-z0-9]+", "", stem)
    return f"{stem}.{country}" if country else stem


def main():
    args = sys.argv[1:]
    mine = {l.strip().lower() for l in open(args[0], encoding="utf-8-sig") if l.strip() and not l.startswith("#")}
    min_hits = int(args[args.index("--min") + 1]) if "--min" in args else 3
    out = args[args.index("--out") + 1] if "--out" in args else None
    fold = "--fold" in args
    cache_path = args[args.index("--cache") + 1] if "--cache" in args else None
    cache = {}
    if cache_path and os.path.exists(cache_path):
        cache = {k: set(v) for k, v in json.load(open(cache_path, encoding="utf-8")).items()}
    cands = candidates()
    print(f"{len(mine)} provider ids; {len(cands)} candidate guides; {'normalised' if fold else 'exact'} matching", flush=True)

    def one(c):
        src, name, url = c
        if url in cache:
            return c, cache[url]
        for attempt in range(3):
            try:
                return c, {i for i in channel_ids(url)}
            except Exception:
                time.sleep(2 * (attempt + 1))
        return c, None

    with ThreadPoolExecutor(6) as ex:
        results = list(ex.map(one, cands))
    if cache_path:
        for (src, name, url), ids in results:
            if ids is not None:
                cache[url] = ids
        json.dump({k: sorted(v) for k, v in cache.items()}, open(cache_path, "w", encoding="utf-8"))
    key = norm if fold else str.lower
    by_key = {}
    for m in mine:
        by_key.setdefault(key(m), set()).add(m)
    rows = []
    for (src, name, url), ids in results:
        if ids is None:
            print(f"  failed: {src}/{name}", flush=True)
            continue
        hit_keys = set(by_key) & {key(i) for i in ids}
        hit = {pid for k in hit_keys for pid in by_key[k]}
        rows.append((len(hit), len(ids), src, name, url, hit))
    rows.sort(key=lambda r: -r[0])
    print("\nguide                                      channels  your ids found")
    for hits, n, src, name, _, _ in rows[:40]:
        if hits >= min_hits:
            print(f"{src + '/' + name:42s} {n:8d}  {hits:6d}")

    covered, picks, remaining = set(), [], list(rows)
    while True:
        best = max(remaining, key=lambda r: len(r[5] - covered), default=None)
        if best is None or len(best[5] - covered) < min_hits:
            break
        picks.append((best, len(best[5] - covered)))
        covered |= best[5]
        remaining.remove(best)
    print(f"\nGreedy pick: {len(picks)} guides cover {len(covered)} of {len(mine)} ids ({100 * len(covered) / len(mine):.1f}%)")
    for (hits, n, src, name, url, _), gain in picks:
        print(f"  +{gain:5d}  {src}/{name}  {url}")
    missing = sorted(mine - covered)
    print(f"{len(missing)} ids in no guide; sample: {missing[:12]}")
    if out:
        with open(out, "w", encoding="utf-8") as f:
            for (hits, n, src, name, url, _), gain in picks:
                f.write(f"{gain}\t{src}\t{name}\t{url}\n")
            for m in missing:
                f.write(f"MISSING\t{m}\n")


if __name__ == "__main__":
    main()
