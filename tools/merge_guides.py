"""Merges XMLTV guides into one file with duplicates removed.

Usage:
  local files:  python tools/merge_guides.py out.xml.gz in1.xml.gz in2.xml.gz ...
  presets:      python tools/merge_guides.py [out.xml.gz] --presets [--countries CA,US,UK]
                                         [--one-country-per-channel] [--max-cache-age-hours H]
  ci combine:   python tools/merge_guides.py out.xml.gz --codes-json CODES.json --dist-dir dist

Local mode reads the given files in the order given. Presets mode downloads the EpgPresets
guides instead (default output: %TEMP%/opencode/epg_merged[_CA_US_UK].xml.gz). CI mode reads
the ordered guide codes from CODES.json and merges the matching epg_<code>.xml.gz files from
the dist directory, skipping codes whose file is not there.

--countries keeps only those countries' guides, in priority order. A channel that two
countries both carry (CNN, Discovery, Al Jazeera) keeps each country's own guide: the
schedules differ, and a provider's channel id points at its own country's copy. Pass
--one-country-per-channel to keep only the first country's copy instead. One guide per
channel *within* a country is done by tools/clean_epg.py --one-per-channel.

Channels are unique by id (first definition wins; a later one only fills a missing icon).
Programmes are unique by (channel, start); the first one seen wins. Copies of one channel
under different ids (CNN.HD.us2 / CNN.HD.uk) are kept: apps look a guide up by id, so
removing them would blank those channels.
"""
import gzip
import json
import os
import re
import sys
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PRESETS = ROOT / "app/src/main/java/com/carteltv/tv/data/xtream/EpgPresets.kt"


def preset_urls():
    """The country codes of EpgPresets' `all` list, or a loud exit: an empty merge is a silent bug."""
    text = PRESETS.read_text(encoding="utf-8")
    block = re.search(r"val all: List<Preset> = listOf\((.*?)\n\s*\)", text, re.S)
    codes = re.findall(r'p\("[^"]+", "([A-Z0-9_]+)"\)', block.group(1)) if block else []
    if not codes:
        raise SystemExit(f"no preset codes found in {PRESETS}: rename or reformat would publish an empty guide")
    return codes


QUALITY_WORDS = {"hd", "fhd", "uhd", "sd", "4k", "8k", "hevc"}


def country_of(code):
    """CA2 -> CA, US_LOCALS1 -> US."""
    return re.match(r"[A-Z]{2}", code).group()


def fold_channel(el, cid):
    """A channel's identity across country files: display name, lower-cased, quality words dropped."""
    name = el.findtext("display-name") or re.sub(r"\.[a-z0-9_]{2,8}$", "", cid)
    tokens = [t for t in re.split(r"[^a-z0-9]+", name.lower()) if t and t not in QUALITY_WORDS]
    return "".join(tokens) or cid.lower()


def download(code, cache, max_age_hours=6.0):
    """Cached only while fresh: a stale or partial file is never served as today's guide.

    The file lands via a .part rename so an interrupted download cannot leave a truncated
    guide behind, and a cached copy older than [max_age_hours] (the app refreshes guides
    every 6) is re-downloaded instead of quietly backing a dead-source "OK" in check_epgs.
    Pass max_age_hours=0 to reuse whatever is cached, for offline runs.
    """
    dest = cache / f"epg_ripper_{code}.xml.gz"
    if max_age_hours and dest.exists() and dest.stat().st_size > 0:
        if (time.time() - dest.stat().st_mtime) / 3600 <= max_age_hours:
            return dest
    part = dest.with_suffix(".part")
    url = f"https://epgshare01.online/epgshare01/epg_ripper_{code}.xml.gz"
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 CartelTV-epg-merge"})
            with urllib.request.urlopen(req, timeout=120) as r, open(part, "wb") as f:
                while chunk := r.read(1 << 20):
                    f.write(chunk)
            os.replace(part, dest)
            return dest
        except Exception as e:
            print(f"  retry {code}: {e}", flush=True)
            time.sleep(5 * (attempt + 1))
    part.unlink(missing_ok=True)
    raise SystemExit(f"could not download {code}")


def merge(sources, out_path, one_country=False):
    """Writes one XMLTV file from (country, path) pairs, in the order given.

    Channels are unique by id, the first definition winning, a later one only filling a
    missing icon. Programmes are unique by (channel, start), the first one seen winning.
    In one-country mode a channel that a higher-priority country already has is dropped
    whole: its programmes go with it.
    """
    channels, seen = {}, set()
    progs_in = progs_out = 0
    name_country = {}  # folded channel name -> country that owns it
    dropped = set()    # channel ids removed because a higher-priority country already has that channel
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryFile() as body:
        for country, src in sources:
            n_prog = n_new = 0
            with gzip.open(src, "rb") as f:
                for _, el in ET.iterparse(f, events=("end",)):
                    if el.tag == "channel":
                        cid = el.get("id", "")
                        if one_country:
                            owner = name_country.setdefault(fold_channel(el, cid), country)
                            if owner != country:
                                dropped.add(cid)
                                el.clear()
                                continue
                        cur = channels.get(cid)
                        if cur is None:
                            channels[cid] = el
                            continue
                        if cur.find("icon") is None and el.find("icon") is not None:
                            cur.append(el.find("icon"))
                        el.clear()
                    elif el.tag == "programme":
                        n_prog += 1
                        if el.get("channel") in dropped:
                            el.clear()
                            continue
                        # One channel cannot start two programmes at the same moment; a copy that only
                        # differs by stop time is the same airing.
                        key = (el.get("channel"), el.get("start"))
                        if key not in seen:
                            seen.add(key)
                            body.write(ET.tostring(el, encoding="utf-8") + b"\n")
                            n_new += 1
                        el.clear()
                    else:
                        continue
            progs_in += n_prog
            progs_out += n_new
            if country is not None:
                print(f"{src.name}: {n_prog} programmes, {n_new} new", flush=True)
        body.seek(0)
        with gzip.open(out_path, "wb", compresslevel=9) as out:
            out.write(b'<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="CartelTV merged guide">\n')
            for el in channels.values():
                out.write(ET.tostring(el, encoding="utf-8"))
                out.write(b"\n")
            while chunk := body.read(1 << 20):
                out.write(chunk)
            out.write(b"</tv>\n")
    print(f"{out_path}: {len(channels)} channels, programmes {progs_in} -> {progs_out} "
          f"({progs_in - progs_out} duplicates dropped)")


def main():
    temp = Path(os.environ.get("TEMP", "."))
    args = sys.argv[1:]
    countries = []
    if "--countries" in args:
        i = args.index("--countries")
        countries = [c.strip().upper() for c in args[i + 1].split(",") if c.strip()]
        del args[i:i + 2]
    one_country = "--one-country-per-channel" in args
    if one_country:
        args.remove("--one-country-per-channel")
    if "--codes-json" in args:
        # CI mode: the merge list lives in one JSON file, not in this script or the workflow.
        i = args.index("--codes-json")
        codes_json = Path(args[i + 1])
        del args[i:i + 2]
        dist = Path("dist")
        if "--dist-dir" in args:
            j = args.index("--dist-dir")
            dist = Path(args[j + 1])
            del args[j:j + 2]
        if not args:
            sys.exit(__doc__)
        codes = json.loads(codes_json.read_text(encoding="utf-8"))["codes"]
        sources = []
        for code in codes:
            p = dist / f"epg_{code}.xml.gz"
            if p.is_file() and p.stat().st_size > 0:
                sources.append((country_of(code), p))
        if not sources:
            raise SystemExit(f"no guides found in {dist} for the codes in {codes_json}")
        merge(sources, Path(args[0]), one_country=one_country)
    elif "--presets" in args:
        args.remove("--presets")
        max_cache_age = 6.0
        if "--max-cache-age-hours" in args:
            i = args.index("--max-cache-age-hours")
            max_cache_age = float(args[i + 1])
            del args[i:i + 2]
        default_name = "epg_merged_" + "_".join(countries) + ".xml.gz" if countries else "epg_merged.xml.gz"
        out_path = Path(args[0]) if args else temp / "opencode" / default_name
        cache = temp / "opencode" / "epg_cache"
        cache.mkdir(parents=True, exist_ok=True)
        codes = preset_urls()
        if countries:
            codes = sorted((c for c in codes if country_of(c) in countries), key=lambda c: countries.index(country_of(c)))
        sources = ((country_of(code), download(code, cache, max_cache_age)) for code in codes)
        merge(sources, out_path, one_country=one_country)
    else:
        if len(args) < 2:
            sys.exit(__doc__)
        out_path, inputs = Path(args[0]), [Path(p) for p in args[1:]]
        merge(((None, p) for p in inputs), out_path)


if __name__ == "__main__":
    main()
