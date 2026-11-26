"""Builds a sports XMLTV guide from ESPN's public scoreboard API, one channel per league.

Usage: python tools/sports_guide.py out.xml.gz [--days 7] [--merge guide.xml.gz]

Without --merge the output holds only the sports channels. With --merge the sports channels and
programmes are appended to a copy of that guide (an existing channel id is never overwritten).
Each game is a programme from its kickoff for a typical duration; the broadcaster names go in the
description. A league ESPN fails to answer for is skipped with a warning, never fatal, but the run
fails if no league returned anything (the API is down or changed).
"""
import gzip
import sys
import time
import urllib.request
import json
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone

API = "https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard?dates={day}"

# (espn sport, espn league, display name, game length in hours)
LEAGUES = [
    ("football", "nfl", "NFL", 3.5),
    ("football", "college-football", "NCAA Football", 3.5),
    ("basketball", "nba", "NBA", 2.75),
    ("basketball", "wnba", "WNBA", 2.5),
    ("basketball", "mens-college-basketball", "NCAA Men's Basketball", 2.25),
    ("basketball", "womens-college-basketball", "NCAA Women's Basketball", 2.25),
    ("baseball", "mlb", "MLB", 3.0),
    ("hockey", "nhl", "NHL", 2.75),
    ("soccer", "eng.1", "Premier League", 2.0),
    ("soccer", "esp.1", "La Liga", 2.0),
    ("soccer", "ger.1", "Bundesliga", 2.0),
    ("soccer", "ita.1", "Serie A", 2.0),
    ("soccer", "fra.1", "Ligue 1", 2.0),
    ("soccer", "usa.1", "MLS", 2.0),
    ("soccer", "uefa.champions", "UEFA Champions League", 2.0),
    ("soccer", "uefa.europa", "UEFA Europa League", 2.0),
    ("soccer", "fifa.world", "FIFA World Cup", 2.0),
    ("mma", "ufc", "UFC", 4.0),
    ("racing", "f1", "Formula 1", 2.5),
    ("racing", "nascar-premier", "NASCAR Cup", 3.5),
    ("racing", "irl", "IndyCar", 3.0),
    ("golf", "pga", "PGA Tour", 6.0),
    ("tennis", "atp", "ATP Tour", 3.0),
    ("tennis", "wta", "WTA Tour", 3.0),
    ("rugby", "267979", "Rugby", 2.0),
    ("cricket", "8676", "Cricket", 6.0),
]


def fetch(sport, league, day):
    url = API.format(sport=sport, league=league, day=day)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 CartelTV-sports-guide"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except Exception as e:
            if attempt == 2:
                print(f"warn: {league} {day}: {e}", file=sys.stderr)
            else:
                time.sleep(2 ** (attempt + 1))
    return None


def parse_iso(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone(timezone.utc)


def xmltv_time(dt):
    return dt.strftime("%Y%m%d%H%M%S +0000")


def games(sport, league, days):
    seen = {}
    today = datetime.now(timezone.utc).date()
    for d in range(-1, days):
        day = (today + timedelta(days=d)).strftime("%Y%m%d")
        data = fetch(sport, league, day)
        for ev in (data or {}).get("events", []):
            seen[ev["id"]] = ev
    return list(seen.values())


def describe(ev):
    comp = (ev.get("competitions") or [{}])[0]
    names = []
    for b in comp.get("broadcasts") or []:
        names += b.get("names") or []
    for b in comp.get("geoBroadcasts") or []:
        n = (b.get("media") or {}).get("shortName")
        if n:
            names.append(n)
    names = list(dict.fromkeys(names))
    venue = (comp.get("venue") or {}).get("fullName")
    parts = []
    if names:
        parts.append("Watch on " + ", ".join(names))
    if venue:
        parts.append(venue)
    return ". ".join(parts)


def build(days):
    channels, programmes = [], []
    for sport, league, name, hours in LEAGUES:
        evs = games(sport, league, days)
        if not evs:
            continue
        cid = f"Sports.{league}.cartel"
        ch = ET.Element("channel", id=cid)
        ET.SubElement(ch, "display-name").text = name
        channels.append(ch)
        for ev in evs:
            try:
                start = parse_iso(ev["date"])
            except (KeyError, ValueError):
                continue
            p = ET.Element("programme", start=xmltv_time(start),
                           stop=xmltv_time(start + timedelta(hours=hours)), channel=cid)
            ET.SubElement(p, "title", lang="en").text = ev.get("name") or ev.get("shortName") or name
            desc = describe(ev)
            if desc:
                ET.SubElement(p, "desc", lang="en").text = desc
            ET.SubElement(p, "category", lang="en").text = "Sports"
            programmes.append(p)
        print(f"{name}: {len(evs)} games", file=sys.stderr)
    if not programmes:
        sys.exit("error: no league returned any games")
    return channels, programmes


def main():
    args = sys.argv[1:]
    days = 7
    merge = None
    if "--days" in args:
        i = args.index("--days")
        days = int(args[i + 1])
        del args[i:i + 2]
    if "--merge" in args:
        i = args.index("--merge")
        merge = args[i + 1]
        del args[i:i + 2]
    if not args:
        sys.exit(__doc__)
    channels, programmes = build(days)
    if merge:
        with gzip.open(merge, "rb") as f:
            root = ET.parse(f).getroot()
        existing = {c.get("id") for c in root.findall("channel")}
        channels = [c for c in channels if c.get("id") not in existing]
        first_prog = root.find("programme")
        idx = list(root).index(first_prog) if first_prog is not None else len(root)
        for n, c in enumerate(channels):
            root.insert(idx + n, c)
    else:
        root = ET.Element("tv", {"generator-info-name": "CartelTV sports_guide"})
        for c in channels:
            root.append(c)
    kept = {c.get("id") for c in channels} | {c.get("id") for c in root.findall("channel")}
    for p in programmes:
        if p.get("channel") in kept:
            root.append(p)
    with gzip.open(args[0], "wb") as f:
        ET.ElementTree(root).write(f, encoding="utf-8", xml_declaration=True)
    print(f"wrote {args[0]}: {len(channels)} channels, {len(programmes)} programmes", file=sys.stderr)


if __name__ == "__main__":
    main()
