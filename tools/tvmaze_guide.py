"""Builds an XMLTV guide of English-language episode airings and streaming releases from TVmaze.

Usage: python tools/tvmaze_guide.py out.xml.gz [--days 7]

Linear: GET /schedule?country=XX&date=D for each English-speaking country, one channel per network
and country. Streaming: GET /schedule/web?date=D (every country), one channel per platform. A streaming
platform releases many episodes at the same moment and a guide channel holds one programme at a time,
so releases are grouped per platform into hour slots ("New on Netflix: A, B, C"), the full list in the
description. Only English-language shows are kept (show language English, or unset on an English-country
network). A failed day is skipped with a warning; the run fails only if nothing came back.
"""
import gzip
import json
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone

import sports_guide as sg

BASE = "https://api.tvmaze.com"
COUNTRIES = ["US", "GB", "CA", "AU", "NZ", "IE", "ZA", "IN", "SG", "PH"]
DEFAULT_MINUTES = 60
MIN_GAP = 0.6  # TVmaze allows 20 calls per 10 seconds


def get(path):
    req = urllib.request.Request(BASE + path, headers={"User-Agent": "CartelTV-tvmaze-guide"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                time.sleep(MIN_GAP)
                return json.load(r)
        except Exception as e:
            if attempt == 3:
                print(f"warn: {path}: {e}", file=sys.stderr)
            else:
                time.sleep(2 ** (attempt + 1))
    return None


def slug(s):
    return "".join(c for c in s.lower() if c.isalnum())


def is_english(show, in_english_market):
    lang = show.get("language")
    return lang == "English" or (lang is None and in_english_market)


def title_of(ep, show):
    tag = f" S{ep['season']}E{ep['number']}" if ep.get("season") and ep.get("number") else ""
    return f"{show['name']}{tag}" + (f": {ep['name']}" if ep.get("name") else "")


def programme(cid, start, minutes, title, desc):
    p = ET.Element("programme", start=sg.xmltv_time(start),
                   stop=sg.xmltv_time(start + timedelta(minutes=minutes)), channel=cid)
    ET.SubElement(p, "title", lang="en").text = title
    if desc:
        ET.SubElement(p, "desc", lang="en").text = desc
    return p


def collect(days):
    today = datetime.now(timezone.utc).date()
    dates = [(today + timedelta(days=d)).isoformat() for d in range(-1, days)]
    linear, web = {}, {}
    for date in dates:
        for cc in COUNTRIES:
            for ep in get(f"/schedule?country={cc}&date={date}") or []:
                show = ep.get("show") or {}
                net = show.get("network") or {}
                if not net.get("name") or not ep.get("airstamp") or not is_english(show, True):
                    continue
                linear.setdefault((net["name"], cc), {})[ep["id"]] = (ep, show)
        for ep in get(f"/schedule/web?date={date}") or []:
            show = (ep.get("_embedded") or {}).get("show") or {}
            plat = show.get("webChannel") or {}
            if not plat.get("name") or not ep.get("airstamp") or not is_english(show, False):
                continue
            web.setdefault(plat["name"], {})[ep["id"]] = (ep, show)
    return linear, web


def build(days):
    linear, web = collect(days)
    channels, programmes = [], []
    for (net, cc), eps in sorted(linear.items()):
        cid = f"Tvmaze.{slug(net)}.{cc.lower()}.cartel"
        ch = ET.Element("channel", id=cid)
        ET.SubElement(ch, "display-name").text = f"{net} ({cc})"
        channels.append(ch)
        for ep, show in eps.values():
            minutes = ep.get("runtime") or show.get("runtime") or DEFAULT_MINUTES
            programmes.append(programme(cid, sg.parse_iso(ep["airstamp"]), minutes, title_of(ep, show),
                                        re.sub(r"<[^>]+>", "", ep.get("summary") or "").strip()))
    for plat, eps in sorted(web.items()):
        cid = f"Tvmaze.web.{slug(plat)}.cartel"
        ch = ET.Element("channel", id=cid)
        ET.SubElement(ch, "display-name").text = f"{plat} New Releases"
        channels.append(ch)
        slots = {}
        for ep, show in eps.values():
            start = sg.parse_iso(ep["airstamp"]).replace(minute=0, second=0, microsecond=0)
            slots.setdefault(start, []).append((ep, show))
        for start, items in sorted(slots.items()):
            names = list(dict.fromkeys(s["name"] for _, s in items))
            if len(names) == 1 and len(items) == 1:
                title = title_of(*items[0])
            else:
                title = f"New on {plat}: " + ", ".join(names[:3]) + (f" +{len(names) - 3} more" if len(names) > 3 else "")
            desc = "; ".join(title_of(e, s) for e, s in items)
            programmes.append(programme(cid, start, DEFAULT_MINUTES, title, desc))
    if not programmes:
        sys.exit("error: TVmaze returned no English programmes")
    return channels, programmes


def main():
    args = sys.argv[1:]
    days = 7
    if "--days" in args:
        i = args.index("--days")
        days = int(args[i + 1])
        del args[i:i + 2]
    if not args:
        sys.exit(__doc__)
    channels, programmes = build(days)
    root = ET.Element("tv", {"generator-info-name": "CartelTV tvmaze_guide"})
    root.extend(channels)
    root.extend(programmes)
    with gzip.open(args[0], "wb") as f:
        ET.ElementTree(root).write(f, encoding="utf-8", xml_declaration=True)
    print(f"wrote {args[0]}: {len(channels)} channels, {len(programmes)} programmes", file=sys.stderr)


if __name__ == "__main__":
    main()
