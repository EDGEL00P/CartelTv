#!/usr/bin/env python3
"""Pull the big upcoming events (fights, finals, grands prix, PPV nights) out of the built TV guides.

Usage: python tools/big_events.py GUIDE_DIR OUT.json [--days 14] [--limit 60] [--regions US2,UK1,...]
           [--sources tools/guide_sources.json] [--espn mma/ufc,mma/pfl] [--espn-daily football/nfl,...]
           [--kayo kayo.html]

GUIDE_DIR holds the epg_<CODE>.xml.gz files that .github/workflows/epg.yml builds. Only guides from
English-speaking regions are read. The same event listed on several channels or in several regions
becomes one entry that names every channel it is on. A listing counts when the guide marks it live,
or when ESPN's schedule (--espn) confirms it. ESPN also supplies the streaming services carrying
each game (ESPN+, Disney+, Prime Video, Peacock, Netflix, Apple TV...), and national streaming-only
games become events of their own. --kayo adds Kayo Sports' public fixtures page.
"""
import gzip
import hashlib
import json
import os
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clean_epg import parse_time  # noqa: E402
from kayo_schedule import parse as parse_kayo  # noqa: E402

ENGLISH_REGIONS = (
    "US2", "US_SPORTS1", "PEACOCK1", "RALLY_TV1", "UK1", "IE1", "CA2", "AU1", "NZ1", "ZA1",
    "IN1", "IN2", "IN4", "SG1", "PH1", "PH2", "JM1", "BB1", "NG1", "KE1",
)

# (pattern, sport, weight, must also match, must not match). A higher weight is a bigger night; the
# best match wins per programme. "Must also match" keeps ambiguous phrases honest: a "Grand Final" is
# only an AFL or NRL night when the title says so.
RULES = [
    (r"\bUFC\s*\d{3}\b", "UFC", 10, None, None),
    (r"\bUFC\b", "UFC", 7, None, None),
    (r"\bPFL\b|\bBellator\b|\bONE Championship\b", "MMA", 6, None, None),
    (r"\bWrestleMania\b|\bRoyal Rumble\b|\bSummerSlam\b|\bSurvivor Series\b", "Wrestling", 9, None, None),
    (r"\bboxing\b|\bheavyweight (?:title|championship)\b|\bworld title fight\b", "Boxing", 8, None, None),
    (r"\bpay[- ]?per[- ]?view\b|\bPPV\b", "PPV", 8, None, None),
    (r"\b(?:F1|Formula 1|MotoGP)\b.*\bRace\b", "Motorsport", 7, None,
     r"\b(?:Academy|Practice|Qualifying|Sprint Shootout|Warm|Driver Tracker|Pit Lane|Co-Pilot|Live Timing|Commentary)\b"),
    (r"\bGrand Prix\b|\bDaytona 500\b|\bIndianapolis 500\b", "Motorsport", 7, None,
     r"\b(?:Global Champions|Show ?jumping|Equestrian|Dressage|Skating|Gymnastics|Diving|Swimming|SailGP|"
     r"Warm[- ]?up|Practice|Qualifying|Preview|Press)\b"),
    (r"\bSuper Bowl\b", "NFL", 10, r"\bNFL\b|\bSuper Bowl [IVXLC]+\b", None),
    (r"\bWorld Series\b", "MLB", 9, r"\bMLB\b|\bbaseball\b", None),
    (r"\bStanley Cup\b", "NHL", 9, r"\bNHL\b|\bhockey\b", None),
    (r"\bNBA Finals\b", "NBA", 9, None, None),
    (r"\bGrand Final\b|\bState of Origin\b", "Australian", 9,
     r"\bAFL\b|\bNRL\b|\bA-League\b|\bSuper Rugby\b|\bNetball\b|\bBBL\b|\bBig Bash\b|\bState of Origin\b", None),
    (r"\bChampions League Final\b|\bFA Cup Final\b|\bEuropean Championship\b|\bFIFA\b.*\bWorld Cup\b|"
     r"\bWorld Cup\b.*\b(?:football|soccer)\b", "Soccer", 9, None, None),
    (r"\bThe Ashes\b|\bIPL Final\b|\bT20 World Cup\b|\bCricket World Cup\b", "Cricket", 8, None, None),
    (r"\bRugby World Cup\b|\bSix Nations\b|\bBledisloe\b|\bBritish (?:and|&) Irish Lions\b", "Rugby", 8, None, None),
    (r"\bWimbledon\b|\bAustralian Open\b|\bUS Open\b|\bFrench Open\b|\bRoland[- ]Garros\b", "Tennis", 7, None, None),
    (r"\bThe Masters\b|\bPGA Championship\b|\bOpen Championship\b|\bRyder Cup\b", "Golf", 7, None, None),
    (r"\bOlympics\b|\bOlympic Games\b", "Olympics", 8, None, None),
]
LIVE_MARK = re.compile(r"^\s*(?:new:\s*)?live\b|\blive\s*:|[\(\[]\s*live\s*[\)\]]", re.I)
RADIO = re.compile(r"\b(?:radio|BBC R\d|R5L)\b", re.I)
COMPILED = [
    (re.compile(p, re.I), sport, weight, re.compile(need, re.I) if need else None, re.compile(avoid, re.I) if avoid else None)
    for p, sport, weight, need, avoid in RULES
]

# Programmes that talk about an event without being it.
NOT_THE_EVENT = re.compile(
    r"\b(replay|classic|highlights?|hl|magazine|preview|review|encore|repeat|unleashed|archive|"
    r"best of|rewind|throwback|re-air|film|movie|documentary|countdown|press conference|"
    r"weigh[- ]?ins?|embedded|prelims? show|pregame|post-?game|studio|moments?|greatest|"
    r"weekly|vault|storied|story|champions:|anniversary|road to|inside|top \d+|sitcoms?|"
    r"next match|up next|coming up|possible overtime)\b",
    re.I,
)

MONTHS = ("january", "february", "march", "april", "may", "june", "july", "august",
          "september", "october", "november", "december")
ANNOUNCED = re.compile(r"\b(" + "|".join(MONTHS) + r")\s+(\d{1,2})(?:st|nd|rd|th)?\b", re.I)


def announced_date_mismatch(headline, start, tolerance_days=1.5):
    """True when the title names a calendar date that is not when this airing starts (a re-air or promo)."""
    m = ANNOUNCED.search(headline)
    if not m:
        return False
    month, day = MONTHS.index(m.group(1).lower()) + 1, int(m.group(2))
    year = time.gmtime(start).tm_year
    for y in (year - 1, year, year + 1):
        try:
            when = time.mktime((y, month, day, 12, 0, 0, 0, 0, 0)) - time.timezone
        except (ValueError, OverflowError):
            continue
        if abs(when - start) <= tolerance_days * 86400:
            return False
    return True


def confirmed_by(headline, confirmed):
    """True when every token of any confirmed event ("ufc 335", or two fighters' surnames) is in the headline."""
    words = set(_norm(headline).split())
    return any(tokens and set(tokens) <= words for tokens in confirmed)


def event_match(title, sub, start=None, confirmed=()):
    """Return (sport, weight) when this programme is a big event itself, else None.

    Only the title and sub-title count: descriptions mention events constantly without being them, and
    24/7 replay channels list old nights as if they were new, so the guide must say live or an
    independent schedule (confirmed) must vouch for the event.
    """
    headline = " ".join(filter(None, (title, sub)))
    if not headline or NOT_THE_EVENT.search(headline):
        return None
    if start is not None and announced_date_mismatch(headline, start):
        return None
    if not LIVE_MARK.search(headline) and not confirmed_by(headline, confirmed):
        return None
    best = None
    for rx, sport, weight, need, avoid in COMPILED:
        if not rx.search(headline) or (need and not need.search(headline)) or (avoid and avoid.search(headline)):
            continue
        if best is None or weight > best[1]:
            best = (sport, weight)
    return best

def display_title(title, sub):
    title = (title or "").strip()
    sub = (sub or "").strip()
    if sub and sub.lower() not in title.lower() and len(sub) <= 80:
        return f"{title}: {sub}"
    return title


def _norm(text):
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def event_key(sport, title):
    """Same sport and the same words is the same event, however many channels or re-airs list it."""
    return f"{sport}|{_norm(title)}"


def read_guide(path, region, now, horizon, confirmed=()):
    """Yield one dict per qualifying programme in an XMLTV file."""
    opener = gzip.open if path.endswith(".gz") else open
    names = {}
    with opener(path, "rb") as fh:
        for _, el in ET.iterparse(fh):
            if el.tag == "channel":
                names[el.get("id")] = (el.findtext("display-name") or el.get("id") or "").strip()
                el.clear()
            elif el.tag == "programme":
                start, stop = parse_time(el.get("start")), parse_time(el.get("stop"))
                if start is not None and stop is not None and now - 3600 <= start <= horizon:
                    title, sub = el.findtext("title"), el.findtext("sub-title")
                    channel = names.get(el.get("channel"), el.get("channel") or "")
                    hit = None if RADIO.search(channel) else event_match(title, sub, start, confirmed)
                    if hit:
                        yield {
                            "title": display_title(title, sub), "sport": hit[0], "weight": hit[1],
                            "start": start, "end": stop,
                            "channel": channel, "region": region,
                        }
                el.clear()


def region_of(filename):
    m = re.match(r"epg_(.+)\.xml(?:\.gz)?$", filename)
    return m.group(1) if m else None


def espn_confirmed(events):
    """Token sets that vouch for upcoming cards, from ESPN scoreboard event names.

    "UFC 335: Oliveira vs. Lopes" confirms both "ufc 335" and the two surnames; a fight night confirms its surnames.
    """
    out = []
    for name in events:
        number = re.search(r"\bUFC\s*(\d{3})\b", name, re.I)
        if number:
            out.append(("ufc", number.group(1)))
        bout = re.search(r":\s*(.+?)\s+vs?\.?\s+(.+)$", name, re.I)
        if bout:
            a, b = _norm(bout.group(1)).split(), _norm(bout.group(2)).split()
            if a and b:
                out.append((a[-1], b[-1]))
    return out


# ESPN's scoreboard names the broadcaster of every game. Those names are the streaming listings:
# ESPN+, Disney+, Hulu, Prime Video, Peacock, Paramount+, Netflix, Apple TV and so on.
STREAMING = re.compile(
    r"ESPN\+|Disney\+|Hulu|Prime Video|Amazon|Peacock|Paramount\+|Netflix|Apple TV|YouTube|DAZN|\bMax\b", re.I,
)
EXCLUSIVE = re.compile(r"Prime Video|Amazon|Netflix|Peacock|Apple TV|YouTube", re.I)  # national streaming-only games
LEAGUE_LABEL = {
    "football/nfl": "NFL", "basketball/nba": "NBA", "hockey/nhl": "NHL", "baseball/mlb": "MLB",
    "mma/ufc": "UFC", "mma/pfl": "MMA", "soccer/usa.1": "Soccer", "soccer/eng.1": "Soccer",
}
ESPN_URL = "https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard?dates={dates}"
UA = "Mozilla/5.0 CartelTV-guide"


def espn_events_from_json(doc, league):
    """One dict per ESPN event: name, start (UTC epoch), league label and the streaming services carrying it."""
    out = []
    for ev in doc.get("events", []):
        comps = ev.get("competitions") or [{}]
        names = []
        for b in comps[0].get("broadcasts", []):
            names += b.get("names", [])
        for g in comps[0].get("geoBroadcasts", []):
            names.append(((g.get("media") or {}).get("shortName")) or "")
        streams = []
        for n in names:
            if n and STREAMING.search(n) and "(local)" not in n.lower() and n not in streams:
                streams.append(n)
        try:
            start = datetime.fromisoformat(ev["date"].replace("Z", "+00:00"))
            start = int((start if start.tzinfo else start.replace(tzinfo=timezone.utc)).timestamp())
        except (KeyError, ValueError):
            continue
        out.append({
            "name": ev.get("name", ""), "start": start, "league": league, "streams": streams,
            "state": ((ev.get("status") or {}).get("type") or {}).get("state", ""),
        })
    return out


def _get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=25) as resp:
        return json.load(resp)


def fetch_espn(ranged, daily, days, now):
    """ESPN events for the window: `ranged` paths in one request, `daily` paths one request per day."""
    def day(t):
        return time.strftime("%Y%m%d", time.gmtime(t))

    events = []
    for path in ranged:
        url = ESPN_URL.format(path=path, dates=f"{day(now)}-{day(now + days * 86400)}")
        try:
            events += espn_events_from_json(_get_json(url), LEAGUE_LABEL.get(path, path))
        except (OSError, ValueError) as exc:
            print(f"espn {path} skipped: {exc}", file=sys.stderr)
    for path in daily:
        for i in range(days):
            try:
                url = ESPN_URL.format(path=path, dates=day(now + i * 86400))
                events += espn_events_from_json(_get_json(url), LEAGUE_LABEL.get(path, path))
            except (OSError, ValueError) as exc:
                print(f"espn {path} +{i}d skipped: {exc}", file=sys.stderr)
                break
    return events


def exclusive_events(espn_events, now, horizon):
    """Streaming-only national games (Prime Video, Netflix, Peacock...) as events of their own."""
    out = []
    for ev in espn_events:
        service = next((s for s in ev["streams"] if EXCLUSIVE.search(s)), None)
        if "TBD" in ev["name"].upper().split():
            continue  # a bracket slot with no teams yet is not something to announce
        if service and ev["state"] == "pre" and now - 3600 <= ev["start"] <= horizon:
            out.append({
                "title": ev["name"], "sport": ev["league"], "weight": 8, "start": ev["start"],
                "end": ev["start"] + 3 * 3600, "channel": service, "region": "US", "streams": [service],
            })
    return out


def kayo_events(html, now, horizon, confirmed=()):
    """Big events on Kayo's schedule; Kayo lists live fixtures, so each one counts as marked live."""
    out = []
    for fx in parse_kayo(html, now):
        if not (now - 3600 <= fx["start"] <= horizon):
            continue
        hit = event_match("Live: " + fx["title"], fx["competition"], fx["start"], confirmed)
        if hit:
            out.append({
                "title": fx["title"], "sport": hit[0], "weight": hit[1], "start": fx["start"],
                "end": fx["start"] + 3 * 3600, "channel": "Kayo Sports", "region": "AU", "streams": ["Kayo"],
            })
    return out


def streams_for(title, espn_events):
    """Streaming services ESPN lists for the card this title names (matched on its confirmed tokens)."""
    found = []
    for ev in espn_events:
        if ev["streams"] and confirmed_by(title, espn_confirmed([ev["name"]])):
            found += [s for s in ev["streams"] if s not in found]
    return found


GENERIC_WORDS = frozenset(
    "live new the and with from for pre post race grand prix formula sports sport coverage day round "
    "main event final night presented sky tnt fox bt tsn bein kayo".split(),
)


def _sig_words(title):
    return {w for w in _norm(title).split() if len(w) >= 3 and w not in GENERIC_WORDS}


def _same_event(a, b):
    """Same sport, same day, and the titles share two distinctive words (or one is just a generic slot)."""
    if a["sport"] != b["sport"] or abs(a["start"] - b["start"]) > 36 * 3600:
        return False
    wa, wb = _sig_words(a["title"]), _sig_words(b["title"])
    return len(wa & wb) >= 2 or (min(len(wa), len(wb)) < 2 and a["start"] // 86400 == b["start"] // 86400)


def _merge(merged, ev):
    """Fold one programme into the entry for its event: earliest start, biggest weight, every channel."""
    key = event_key(ev["sport"], ev["title"])
    cur = merged.get(key)
    if cur is None:
        cur = merged[key] = {**ev, "channels": [], "regions": [], "streams": []}
    cur["start"] = min(cur["start"], ev["start"])
    cur["end"] = max(cur["end"], ev["end"])
    cur["weight"] = max(cur["weight"], ev["weight"])
    chan = {"name": ev["channel"], "region": ev["region"]}
    if chan not in cur["channels"]:
        cur["channels"].append(chan)
    if ev["region"] not in cur["regions"]:
        cur["regions"].append(ev["region"])
    cur["streams"] += [s for s in ev.get("streams", []) if s not in cur["streams"]]


def build(guide_dir, regions, days, limit, now=None, confirmed=(), extra=(), espn_events=()):
    now = now if now is not None else time.time()
    horizon = now + days * 86400
    merged = {}
    for name in sorted(os.listdir(guide_dir)):
        region = region_of(name)
        if region not in regions:
            continue
        try:
            for ev in read_guide(os.path.join(guide_dir, name), region, now, horizon, confirmed):
                _merge(merged, ev)
        except (ET.ParseError, OSError, EOFError) as exc:
            print(f"skipped {name}: {exc}", file=sys.stderr)
    for ev in extra:
        _merge(merged, ev)
    events = []
    for key, ev in merged.items():
        streams = ev["streams"] + [s for s in streams_for(ev["title"], espn_events) if s not in ev["streams"]]
        events.append({
            "id": hashlib.sha1(key.encode()).hexdigest()[:12],
            "title": ev["title"], "sport": ev["sport"], "weight": ev["weight"],
            "start": int(ev["start"]), "end": int(ev["end"]),
            "channels": ev["channels"][:6], "regions": ev["regions"], "streams": streams,
        })
    events.sort(key=lambda e: (-e["weight"], e["start"]))
    kept = []
    for ev in events:
        twin = next((k for k in kept if _same_event(k, ev)), None)
        if twin is None:
            kept.append(ev)
        else:  # a grand prix weekend is one card, listed on every channel and service that carries it
            twin["channels"] += [c for c in ev["channels"] if c not in twin["channels"]]
            twin["channels"] = twin["channels"][:6]
            twin["regions"] += [r for r in ev["regions"] if r not in twin["regions"]]
            twin["streams"] += [s for s in ev["streams"] if s not in twin["streams"]]
            twin["start"], twin["end"] = min(twin["start"], ev["start"]), max(twin["end"], ev["end"])
    return {"generated": int(now), "days": days, "events": kept[:limit]}


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    def option(name, default=None):
        return argv[argv.index(name) + 1] if name in argv else default

    def listed(name):
        return [p for p in (option(name) or "").split(",") if p]

    guide_dir, out = argv[1], argv[2]
    days, limit = int(option("--days", 14)), int(option("--limit", 60))
    regions = list(option("--regions", "").split(",") if option("--regions") else ENGLISH_REGIONS)
    sources = option("--sources")
    if sources and os.path.exists(sources):
        with open(sources, encoding="utf-8") as fh:
            regions += [s["name"] for s in json.load(fh)["sources"] if s.get("kind") == "linear"]
    now = time.time()
    espn = fetch_espn(listed("--espn"), listed("--espn-daily"), days, now) if (option("--espn") or option("--espn-daily")) else []
    confirmed = espn_confirmed([e["name"] for e in espn if e["league"] in ("UFC", "MMA")])
    extra = exclusive_events(espn, now, now + days * 86400)
    kayo_file = option("--kayo")
    if kayo_file and os.path.exists(kayo_file):
        with open(kayo_file, encoding="utf-8", errors="replace") as fh:
            extra += kayo_events(fh.read(), now, now + days * 86400, confirmed)
    result = build(guide_dir, tuple(regions), days, limit, now=now, confirmed=confirmed, extra=extra, espn_events=espn)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=1)
    print(f"{len(result['events'])} big events from {len(regions)} guides, {len(espn)} ESPN events -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
