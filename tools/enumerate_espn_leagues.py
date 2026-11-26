"""Lists every ESPN league whose scoreboard answers, so the app's league list is verified, not guessed.

Usage: python tools/enumerate_espn_leagues.py [out.json]    (default: %TEMP%/opencode/espn_leagues.json)

For each sport ESPN's core API names its leagues; each league's site scoreboard is then requested. A league is
kept when it returns its name. The result records sport, slug (the path used by the app), name and how many
events the scoreboard held at the time.
"""
import json
import os
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = {"User-Agent": "CartelTV-league-catalogue/1.0"}
CORE = "https://sports.core.api.espn.com/v2/sports"
SITE = "https://site.api.espn.com/apis/site/v2/sports"


def get(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=25) as r:
            return json.load(r)
    except (urllib.error.URLError, TimeoutError, ValueError, OSError):
        return {}


def leagues_of(sport):
    out, page = [], 1
    while True:
        j = get(f"{CORE}/{sport}/leagues?limit=300&page={page}")
        for it in j.get("items", []):
            ref = it.get("$ref", "")
            slug = ref.split("/leagues/")[-1].split("?")[0]
            if slug:
                out.append(slug)
        if page >= j.get("pageCount", 1):
            return out
        page += 1


def probe(args):
    sport, slug = args
    j = get(f"{SITE}/{sport}/{slug}/scoreboard")
    lg = (j.get("leagues") or [{}])[0] if j else {}
    name = lg.get("name")
    if not name:
        return None
    return {"sport": sport, "slug": slug, "path": f"{sport}/{slug}", "name": name,
            "abbr": lg.get("abbreviation"), "events": len(j.get("events", []))}


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.environ.get("TEMP", "."), "opencode", "espn_leagues.json")
    sports = [it["$ref"].rstrip("/").split("/sports/")[-1].split("?")[0] for it in get(f"{CORE}?limit=100").get("items", [])]
    print("sports:", sports, flush=True)
    pairs = []
    for sport in sports:
        slugs = leagues_of(sport)
        print(f"  {sport}: {len(slugs)} leagues listed", flush=True)
        pairs += [(sport, s) for s in slugs]
    with ThreadPoolExecutor(16) as ex:
        found = [r for r in ex.map(probe, pairs) if r]
    json.dump(found, open(out, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    print(f"{len(found)} of {len(pairs)} leagues answered -> {out}", flush=True)


if __name__ == "__main__":
    main()
