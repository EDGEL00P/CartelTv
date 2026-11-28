#!/usr/bin/env python3
"""Export a static sports-guide JSON for CartelTV.

The app fetches this file over HTTP; generation runs in CI (GitHub Actions), not on device.
Data sources: ESPN public scoreboard API (fixtures, times, venue, ESPN-named broadcasters)
plus curated platform rights from tools/platform_rights.json.

Usage:
  python tools/export_sports_guide_json.py out.json [--days 7] [--pretty]
  python tools/export_sports_guide_json.py public/sports-guide.json --days 3 --pretty

Exit is non-zero only when no league returns any games (API down/changed). Individual league
failures are warned and skipped so one bad upstream does not blank the whole guide.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

API = "https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard?dates={day}"
UA = "Mozilla/5.0 CartelTV-sports-guide-json/1.0"

# (espn path, display name, default game length hours, region tags)
# Paths are the same {sport}/{league} ids the app/ESPN use. A league that returns no games
# for the window is kept in the report but skipped from games; hard failures warn and continue.
LEAGUES = [
    # US / global majors
    ("football/nfl", "NFL", 3.5, ["US", "AU", "UK", "CA", "NZ"]),
    ("football/college-football", "NCAA Football", 3.5, ["US"]),
    ("basketball/nba", "NBA", 2.75, ["US", "AU", "UK", "CA", "NZ"]),
    ("basketball/wnba", "WNBA", 2.5, ["US", "AU"]),
    ("basketball/mens-college-basketball", "NCAA Men's Basketball", 2.25, ["US"]),
    ("basketball/womens-college-basketball", "NCAA Women's Basketball", 2.25, ["US"]),
    ("baseball/mlb", "MLB", 3.0, ["US", "AU", "UK", "CA"]),
    ("hockey/nhl", "NHL", 2.75, ["US", "CA", "AU", "UK"]),
    ("mma/ufc", "UFC", 4.0, ["US", "AU", "UK", "CA", "NZ"]),
    ("racing/f1", "Formula 1", 2.5, ["GLOBAL"]),
    ("racing/nascar-premier", "NASCAR Cup", 3.5, ["US"]),
    ("racing/irl", "IndyCar", 3.0, ["US"]),
    ("golf/pga", "PGA Tour", 6.0, ["US", "AU", "UK", "CA"]),
    ("golf/lpga", "LPGA Tour", 6.0, ["US", "AU", "UK"]),
    ("tennis/atp", "ATP Tour", 3.0, ["GLOBAL"]),
    ("tennis/wta", "WTA Tour", 3.0, ["GLOBAL"]),
    # Soccer - US/CA
    ("soccer/usa.1", "MLS", 2.0, ["US", "CA", "AU", "UK", "NZ"]),
    ("soccer/usa.nwsl", "NWSL", 2.0, ["US", "AU"]),
    ("soccer/usa.usl.1", "USL Championship", 2.0, ["US"]),
    ("soccer/usa.open", "US Open Cup", 2.0, ["US"]),
    ("soccer/can.w.nsl", "NSL Canada Women", 2.0, ["CA"]),
    # Soccer - UK/IE
    ("soccer/eng.1", "Premier League", 2.0, ["UK", "AU", "US", "CA", "NZ"]),
    ("soccer/eng.2", "EFL Championship", 2.0, ["UK", "US"]),
    ("soccer/eng.3", "League One", 2.0, ["UK"]),
    ("soccer/eng.4", "League Two", 2.0, ["UK"]),
    ("soccer/eng.fa", "FA Cup", 2.0, ["UK", "US", "AU"]),
    ("soccer/eng.league_cup", "EFL Cup", 2.0, ["UK", "US"]),
    ("soccer/eng.w.1", "WSL", 2.0, ["UK", "US", "AU"]),
    ("soccer/sco.1", "Scottish Premiership", 2.0, ["UK"]),
    ("soccer/sco.tennents", "Scottish Cup", 2.0, ["UK"]),
    ("soccer/sco.cis", "Scottish League Cup", 2.0, ["UK"]),
    ("soccer/wal.1", "Welsh Premier League", 2.0, ["UK"]),
    ("soccer/irl.1", "League of Ireland Premier Division", 2.0, ["IE", "UK"]),
    ("soccer/nir.1", "NIFL Premiership", 2.0, ["UK", "IE"]),
    # Soccer - Europe/global
    ("soccer/esp.1", "La Liga", 2.0, ["GLOBAL"]),
    ("soccer/ger.1", "Bundesliga", 2.0, ["GLOBAL"]),
    ("soccer/ita.1", "Serie A", 2.0, ["GLOBAL"]),
    ("soccer/fra.1", "Ligue 1", 2.0, ["GLOBAL"]),
    ("soccer/uefa.champions", "UEFA Champions League", 2.0, ["GLOBAL"]),
    ("soccer/uefa.europa", "UEFA Europa League", 2.0, ["GLOBAL"]),
    ("soccer/uefa.europa.conf", "UEFA Conference League", 2.0, ["GLOBAL"]),
    ("soccer/uefa.euro", "UEFA Euro", 2.0, ["GLOBAL"]),
    ("soccer/fifa.world", "FIFA World Cup", 2.0, ["GLOBAL"]),
    ("soccer/fifa.wwc", "FIFA Women's World Cup", 2.0, ["GLOBAL"]),
    ("soccer/conmebol.libertadores", "Copa Libertadores", 2.0, ["GLOBAL"]),
    ("soccer/concacaf.champions", "CONCACAF Champions Cup", 2.0, ["US", "CA"]),
    ("soccer/caf.nations", "Africa Cup of Nations", 2.0, ["GLOBAL"]),
    # Australia / NZ
    ("australian-football/afl", "AFL", 3.0, ["AU", "NZ"]),
    ("australian-football/aflw", "AFLW", 2.5, ["AU"]),
    ("rugby-league/3", "NRL", 2.0, ["AU", "NZ"]),
    ("rugby/242041", "Super Rugby Pacific", 2.0, ["AU", "NZ"]),
    ("basketball/nbl", "NBL", 2.25, ["AU", "NZ"]),
    ("soccer/aus.1", "A-League Men", 2.0, ["AU", "NZ"]),
    ("soccer/aus.w.1", "A-League Women", 2.0, ["AU", "NZ"]),
    ("cricket/8676", "Cricket", 6.0, ["AU", "UK", "NZ", "IN", "ZA"]),
    # Canada
    ("football/cfl", "CFL", 3.0, ["CA"]),
    ("hockey/pwhl", "PWHL", 2.5, ["CA", "US"]),
    # Rugby union / global English-speaking
    ("rugby/267979", "Gallagher Premiership", 2.0, ["UK", "AU", "NZ", "ZA"]),
    ("rugby/270557", "United Rugby Championship", 2.0, ["UK", "IE", "ZA"]),
    ("rugby/180659", "Six Nations", 2.0, ["UK", "IE"]),
    ("rugby/271937", "European Rugby Champions Cup", 2.0, ["UK", "IE", "FR", "ZA"]),
    ("rugby/272073", "European Rugby Challenge Cup", 2.0, ["UK", "IE", "FR"]),
    ("rugby/244293", "The Rugby Championship", 2.0, ["AU", "NZ", "ZA"]),
    ("rugby/164205", "Rugby World Cup", 2.0, ["GLOBAL"]),
    ("rugby/289237", "Women's Rugby World Cup", 2.0, ["GLOBAL"]),
    ("rugby/289262", "Major League Rugby", 2.0, ["US"]),
    ("rugby/270555", "Currie Cup", 2.0, ["ZA"]),
]


def fetch(path: str, day: str) -> dict | None:
    url = API.format(path=path, day=day)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.load(resp)
        except Exception as exc:  # noqa: BLE001 - league failure must not kill run
            if attempt == 2:
                print(f"warn: {path} {day}: {exc}", file=sys.stderr)
            else:
                time.sleep(2 ** (attempt + 1))
    return None


def parse_iso(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def collect_games(path: str, days: int) -> list[dict]:
    seen: dict[str, dict] = {}
    today = datetime.now(timezone.utc).date()
    for delta in range(-1, days):
        day = (today + timedelta(days=delta)).strftime("%Y%m%d")
        data = fetch(path, day)
        for event in (data or {}).get("events", []):
            event_id = event.get("id")
            if event_id:
                seen[str(event_id)] = event
    return list(seen.values())


def broadcasters(event: dict) -> list[str]:
    comp = (event.get("competitions") or [{}])[0]
    names: list[str] = []
    for item in comp.get("broadcasts") or []:
        names.extend(item.get("names") or [])
    for item in comp.get("geoBroadcasts") or []:
        name = ((item.get("media") or {}).get("shortName") or "").strip()
        if name:
            names.append(name)
    deduped: list[str] = []
    for name in names:
        name = " ".join(str(name).split())
        if name and name not in deduped:
            deduped.append(name)
    return deduped


def venue(event: dict) -> str | None:
    comp = (event.get("competitions") or [{}])[0]
    name = ((comp.get("venue") or {}).get("fullName") or "").strip()
    return name or None


def status_name(event: dict) -> str | None:
    comp = (event.get("competitions") or [{}])[0]
    name = (((comp.get("status") or {}).get("type") or {}).get("name") or "").strip()
    return name or None


def load_rights(path: Path) -> dict[str, list[str]]:
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {k: v for k, v in data.items() if isinstance(v, list)}


def rights_for(league_slug: str, rights: dict[str, list[str]]) -> list[str]:
    return list(rights.get(league_slug, []))


def build(days: int, rights_path: Path) -> dict:
    rights = load_rights(rights_path)
    leagues_out = []
    games_out = []
    for path, name, hours, regions in LEAGUES:
        events = collect_games(path, days)
        league_slug = path.split("/", 1)[1]
        leagues_out.append({
            "path": path,
            "slug": league_slug,
            "name": name,
            "regions": regions,
            "game_hours": hours,
            "events": len(events),
            "rights_curated": rights_for(league_slug, rights),
        })
        if not events:
            continue
        channel_id = f"Sports.{league_slug}.cartel"
        for event in events:
            try:
                start = parse_iso(event["date"])
            except Exception:  # noqa: BLE001
                continue
            end = start + timedelta(hours=hours)
            games_out.append({
                "id": f"espn:{event.get('id')}",
                "league_path": path,
                "league_slug": league_slug,
                "league_name": name,
                "channel_id": channel_id,
                "title": event.get("name") or event.get("shortName") or name,
                "short_title": event.get("shortName"),
                "start_utc": start.isoformat().replace("+00:00", "Z"),
                "end_utc": end.isoformat().replace("+00:00", "Z"),
                "status": status_name(event),
                "venue": venue(event),
                "broadcasters_espn": broadcasters(event),
                "rights_curated": rights_for(league_slug, rights),
                "regions": regions,
                "source": "espn",
            })
        print(f"{name}: {len(events)} games", file=sys.stderr)

    if not games_out:
        raise SystemExit("error: no league returned any games")

    games_out.sort(key=lambda g: (g["start_utc"], g["league_name"], g["title"]))
    return {
        "schema": "carteltv.sports-guide.v1",
        "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "days": days,
        "source": {
            "provider": "espn",
            "scoreboard_api": "https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard",
            "rights_file": str(rights_path.as_posix()),
        },
        "league_count": len([l for l in leagues_out if l["events"] > 0]),
        "game_count": len(games_out),
        "leagues": leagues_out,
        "games": games_out,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", help="Output JSON path")
    parser.add_argument("--days", type=int, default=7, help="Days ahead to include (default: 7)")
    parser.add_argument("--pretty", action="store_true", help="Indent JSON for humans")
    parser.add_argument(
        "--rights",
        default=str(Path(__file__).with_name("platform_rights.json")),
        help="Path to platform_rights.json",
    )
    args = parser.parse_args()

    payload = build(args.days, Path(args.rights))
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="\n") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2 if args.pretty else None)
        fh.write("\n")
    print(
        f"wrote {out}: {payload['league_count']} leagues, {payload['game_count']} games",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
