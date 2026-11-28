"""Mines ESPN's scoreboards for the broadcasters that really carry each league, over a three-week window.

Usage: python tools/mine_league_networks.py [out.json] [--back 7] [--ahead 10]

Reads every league label and path from EspnScoreboardClient.kt (ALL_FEEDS), fetches each day's scoreboard, and counts the
broadcaster names ESPN lists (`broadcasts[].names` and `geoBroadcasts[].media.shortName`). The result is
data-driven and current, but ESPN only lists US-market broadcasters, and only for leagues shown in the US.
"""
import collections
import datetime
import json
import os
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

UA = {"User-Agent": "CartelTV-league-networks/1.0"}
ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/carteltv/tv/data/espn/EspnScoreboardClient.kt"
FEED = re.compile(r'^\s*"([A-Za-z0-9]+)" to "([a-z\-]+/[A-Za-z0-9_.\-]+)",?\s*$', re.M)


def feeds():
    return FEED.findall(SRC.read_text(encoding="utf-8"))


def get(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=25) as r:
            return json.load(r)
    except Exception:
        return {}


def day(label, path, date):
    j = get(f"https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard?dates={date}")
    names = collections.Counter()
    games = 0
    for e in j.get("events", []):
        games += 1
        for c in e.get("competitions", [])[:1]:
            seen = set()
            for b in c.get("broadcasts", []) or []:
                seen.update(n for n in (b.get("names") or []) if n)
            for g in c.get("geoBroadcasts", []) or []:
                m = (g.get("media") or {}).get("shortName")
                if m:
                    seen.add(m)
            names.update(seen)
    return label, games, names


def main():
    args = sys.argv[1:]
    back = int(args[args.index("--back") + 1]) if "--back" in args else 7
    ahead = int(args[args.index("--ahead") + 1]) if "--ahead" in args else 10
    out = next((a for a in args if a.endswith(".json")), os.path.join(os.environ.get("TEMP", "."), "opencode", "league_networks.json"))
    today = datetime.date.today()
    dates = [(today + datetime.timedelta(days=d)).strftime("%Y%m%d") for d in range(-back, ahead + 1)]
    fl = feeds()
    print(f"{len(fl)} leagues x {len(dates)} days", flush=True)
    jobs = [(l, p, d) for l, p in fl for d in dates]
    with ThreadPoolExecutor(12) as ex:
        results = list(ex.map(lambda a: day(*a), jobs))
    games = collections.Counter()
    nets = collections.defaultdict(collections.Counter)
    for label, n, names in results:
        games[label] += n
        nets[label].update(names)
    report = {}
    for label, path in fl:
        top = [(n, c) for n, c in nets[label].most_common(14) if c >= 2]
        report[label] = {"path": path, "games": games[label], "networks": top}
    json.dump(report, open(out, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    with_nets = sum(1 for v in report.values() if v["networks"])
    print(f"{with_nets} of {len(report)} leagues have broadcaster data; {sum(1 for v in report.values() if not v['games'])} had no games in the window -> {out}")


if __name__ == "__main__":
    main()
