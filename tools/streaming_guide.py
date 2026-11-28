"""Builds an XMLTV guide of live events per streaming platform, one channel per platform and league.

Usage: python tools/streaming_guide.py out.xml.gz [--days 7]

Events come from ESPN's public scoreboard API (the leagues in sports_guide.py plus the ones below).
An event is listed on a platform when ESPN names that platform as a broadcaster, or when
tools/platform_rights.json says the platform carries the league (Kayo, Stan Sport, NFL+ and similar
services that ESPN does not name). Channels are split by league because games overlap and a guide
channel can only hold one programme at a time. A league that fails is skipped with a warning; the run
fails only if nothing at all came back.
"""
import gzip
import json
import re
import sys
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from pathlib import Path

import sports_guide as sg

EXTRA_LEAGUES = [
    ("australian-football", "afl", "AFL", 3.0),
    ("rugby-league", "3", "NRL", 2.0),
    ("soccer", "aus.1", "A-League", 2.0),
    ("soccer", "eng.2", "EFL Championship", 2.0),
    ("soccer", "eng.fa", "FA Cup", 2.0),
    ("soccer", "eng.league_cup", "EFL Cup", 2.0),
    ("soccer", "uefa.europa.conf", "UEFA Conference League", 2.0),
    ("soccer", "concacaf.champions", "CONCACAF Champions Cup", 2.0),
    ("soccer", "conmebol.libertadores", "Copa Libertadores", 2.0),
    ("soccer", "sco.1", "Scottish Premiership", 2.0),
    ("golf", "lpga", "LPGA Tour", 6.0),
    ("football", "ufl", "UFL", 3.0),
]

PLATFORMS = [
    ("ESPN+", r"ESPN\+|ESPN Unlimited"),
    ("Peacock", r"Peacock"),
    ("Paramount+", r"Paramount\+"),
    ("Prime Video", r"Prime Video|Amazon"),
    ("Apple TV", r"Apple TV"),
    ("Netflix", r"Netflix"),
    ("Max", r"\bMax\b|HBO Max"),
    ("Disney+", r"Disney\+"),
    ("Hulu", r"Hulu"),
    ("YouTube", r"YouTube"),
    ("DAZN", r"DAZN"),
    ("Fox One", r"Fox One"),
    ("Victory+", r"Victory\+"),
    ("Kayo Sports", r"Kayo"),
    ("Stan Sport", r"Stan Sport"),
    ("NFL+", r"NFL\+"),
]
# ESPN's API reports US broadcasters; global services keep one international channel.
GLOBAL = {"Apple TV", "Netflix", "Disney+", "Max", "DAZN"}
PLATFORM_RES = [(n, re.compile(p, re.I)) for n, p in PLATFORMS]


def slug(s):
    return re.sub(r"[^a-z0-9]+", "", s.lower().replace("+", "plus"))


def broadcast_names(ev):
    comp = (ev.get("competitions") or [{}])[0]
    names = []
    for b in comp.get("broadcasts") or []:
        names += b.get("names") or []
    for b in comp.get("geoBroadcasts") or []:
        m = b.get("media") or {}
        names += [m.get("shortName") or ""]
    return [n for n in names if n]


def platforms_for(ev, league, rights):
    found = {name + (" (INT)" if name in GLOBAL else " (US)")
             for n in broadcast_names(ev) for name, rx in PLATFORM_RES if rx.search(n)}
    found.update(rights.get(league, []))
    return found


def sg_programme(cid, start, title, desc):
    p = ET.Element("programme", start=sg.xmltv_time(start), stop=sg.xmltv_time(start + timedelta(hours=1)), channel=cid)
    ET.SubElement(p, "title", lang="en").text = title
    ET.SubElement(p, "desc", lang="en").text = desc
    ET.SubElement(p, "category", lang="en").text = "Sports"
    return p


def build(days, rights):
    leagues = list({l[1]: l for l in sg.LEAGUES + EXTRA_LEAGUES}.values())
    with ThreadPoolExecutor(max_workers=8) as pool:
        fetched = list(pool.map(lambda l: sg.games(l[0], l[1], days), leagues))
    channels, programmes = [], []
    upcoming = {}  # platform -> {hour slot -> [(league name, event name)]}
    for (_, league, name, hours), evs in zip(leagues, fetched):
        per_platform = {}
        for ev in evs:
            for plat in platforms_for(ev, league, rights):
                per_platform.setdefault(plat, []).append(ev)
                try:
                    hour = sg.parse_iso(ev["date"]).replace(minute=0, second=0, microsecond=0)
                except (KeyError, ValueError):
                    continue
                upcoming.setdefault(plat, {}).setdefault(hour, []).append(
                    f"{ev.get('shortName') or ev.get('name') or name} ({name})")
        for plat, pevs in sorted(per_platform.items()):
            cid = f"Stream.{slug(plat)}.{slug(league)}.cartel"
            ch = ET.Element("channel", id=cid)
            ET.SubElement(ch, "display-name").text = f"{plat} {name}"
            channels.append(ch)
            for ev in pevs:
                try:
                    start = sg.parse_iso(ev["date"])
                except (KeyError, ValueError):
                    continue
                p = ET.Element("programme", start=sg.xmltv_time(start),
                               stop=sg.xmltv_time(start + timedelta(hours=hours)), channel=cid)
                ET.SubElement(p, "title", lang="en").text = ev.get("name") or ev.get("shortName") or name
                comp = (ev.get("competitions") or [{}])[0]
                venue = (comp.get("venue") or {}).get("fullName")
                ET.SubElement(p, "desc", lang="en").text = f"{name} live on {plat}" + (f". {venue}" if venue else "")
                ET.SubElement(p, "category", lang="en").text = "Sports"
                programmes.append(p)
            print(f"{plat} / {name}: {len(pevs)} events", file=sys.stderr)
    # One schedule per platform across every league, an hour per slot, for the provider's numbered event
    # channels ("ESPN+ 504", "PRIME US 061") whose own event is unknown until the provider assigns one.
    for plat, slots in sorted(upcoming.items()):
        cid = f"Stream.{slug(plat)}.upcoming.cartel"
        ch = ET.Element("channel", id=cid)
        ET.SubElement(ch, "display-name").text = f"{plat} Upcoming Events"
        channels.append(ch)
        for hour, items in sorted(slots.items()):
            title = f"On {plat}: " + "; ".join(items[:2]) + (f" +{len(items) - 2} more" if len(items) > 2 else "")
            programmes.append(sg_programme(cid, hour, title, "; ".join(items)))
    if not programmes:
        sys.exit("error: no platform event found")
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
    rights = {k: v for k, v in json.loads(
        (Path(__file__).with_name("platform_rights.json")).read_text(encoding="utf-8")).items() if not k.startswith("_")}
    channels, programmes = build(days, rights)
    root = ET.Element("tv", {"generator-info-name": "CartelTV streaming_guide"})
    root.extend(channels)
    root.extend(programmes)
    with gzip.open(args[0], "wb") as f:
        ET.ElementTree(root).write(f, encoding="utf-8", xml_declaration=True)
    print(f"wrote {args[0]}: {len(channels)} channels, {len(programmes)} programmes", file=sys.stderr)


if __name__ == "__main__":
    main()
