"""Deep clean and country-sort an XMLTV file.

Usage: python tools/clean_epg.py in.xml.gz out.xml.gz [--countries CA,US,UK] [--keep-hours 6]
       [--max-days 3] [--desc-chars 160] [--slim] [--one-per-channel] [--english-only] [--only-ids ids.txt]

--only-ids keeps only the channels whose id is in the file (one epg_channel_id per line, case-insensitive,
"#" lines ignored): the ones your provider actually carries. Guides for any other channel are never
looked up, so they are dropped. Ids in the file that no guide has are written to <out>.missing-ids.txt.

--english-only drops channels whose programme text is not English: non-Latin scripts, or text where
French/Spanish/German/Italian/Portuguese/Dutch function words clearly outnumber English ones. A
channel with too little text to judge is kept. Removed ids are listed in <out>.nonenglish.tsv.

--one-per-channel keeps a single guide for each channel within a country (the id with the most
programmes wins; "CNN" and "CNN HD" in the US count as one channel). The same channel in another
country keeps its own guide. Dropped ids are listed in <out>.aliases.tsv (dropped id, kept id) so a
lookup can follow them; a provider that uses a dropped id has no guide until it is mapped.

Size options: --max-days drops programmes starting later than N days from now; --desc-chars cuts
descriptions to N characters (0 removes them); --slim keeps only title, sub-title and desc on each
programme (drops credits, categories, icons, episode numbers, ratings).

Per channel, programmes are:
  - dropped when already over by more than --keep-hours (a guide only needs now onwards);
  - dropped when empty or reversed (stop <= start);
  - collapsed when the same title starts within 10 minutes of an earlier copy (a repeat listing,
    not a repeat airing: back-to-back repeats of one title are 30+ minutes apart);
  - clipped so none overlaps the next one (the earlier programme ends when the later one starts);
  - given a tidied title (guide annotations such as "NEW:" and "(R)" removed).
Channels with no programmes left are dropped, display names lose quality tags and country prefixes,
and everything is written in country priority order (then alphabetically) so the file reads like a
real channel line-up. Channel ids are never merged or renamed: apps look a guide up by id.
"""
import gzip
import re
import sqlite3
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

REPEAT_WINDOW = 10 * 60
QUALITY = re.compile(r"(?i)\b(?:8k|4k|uhd|fhd|hd|sd|hq|lq|hevc|h26[45]|x26[45]|raw|hdr(?:10)?|\d{2}fps|1080[pi]?|720p?|576p?|480p?)\b")
PREFIX = re.compile(r"^[\s\W_]*(?:\[[A-Za-z]{2,4}\]|\|[A-Za-z]{2,4}\||\([A-Za-z]{2,4}\)|[A-Za-z]{2,4}\s*(?:[:|➔→»>]+|-+(?=\s)))\s*")
BRACKET = re.compile(r"(?i)[\[(]\s*(?:hd|fhd|uhd|sd|4k|hevc|raw|vip|backup|multi)\s*[\])]")
TITLE_LEAD = re.compile(r"(?i)^\s*(?:[\[(]\s*(?:new|live|repeat|rerun|premiere|encore)\s*[\])]|(?:new|live|repeat|rerun|premiere|encore)(?:\s+episode)?\s*[:|\-–]+)\s*")
TITLE_TRAIL = re.compile(r"(?i)\s*[\[(]\s*(?:new|live|r|repeat|rerun|cc|hd|sap|stereo|ad|subtitled)\s*[\])]\s*$")
SPACES = re.compile(r"\s+")


def parse_time(s):
    """XMLTV 'YYYYMMDDHHMMSS +ZZZZ' (zone optional) to epoch seconds, or None."""
    m = re.match(r"(\d{14})(?:\s*([+-])(\d{2})(\d{2}))?", s or "")
    if not m:
        return None
    try:
        t = datetime.strptime(m.group(1), "%Y%m%d%H%M%S").replace(tzinfo=timezone.utc).timestamp()
    except ValueError:
        # Upstream feeds carry junk like month 13; dropping the one entry beats
        # aborting the whole guide in the 6-hour publish loop.
        return None
    if m.group(2):
        off = int(m.group(3)) * 3600 + int(m.group(4)) * 60
        t -= off if m.group(2) == "+" else -off
    return int(t)


def format_time(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y%m%d%H%M%S +0000")


def clean_title(t):
    s = t
    for _ in range(2):
        s = TITLE_TRAIL.sub("", TITLE_LEAD.sub("", s))
    return SPACES.sub(" ", s).strip() or t.strip()


def clean_display(name):
    s = name
    for _ in range(2):
        s = PREFIX.sub("", s)
    s = QUALITY.sub(" ", BRACKET.sub(" ", s))
    return SPACES.sub(" ", s).strip(" -|:*/") or name.strip()


# Function words that are not also common English words, so a hit is evidence for that language.
FOREIGN_STOP = {
    "fr": "le les des une dans avec est sur qui sont du aux ses leur cette mais ils elle nous vous ont très".split(),
    "es": "el los las una para del por que sus su al es se pero como más sobre entre cuando sin esta este".split(),
    "de": "der das und mit ein eine von zu dem ist nicht sich auf für wird nach auch bei aus über wie oder".split(),
    "it": "il gli della delle per che sono nel alla dei una non più anche sul tra come quando".split(),
    "pt": "os uma com para que dos das não seu sua pelo mais como sobre entre quando mas são".split(),
    "nl": "het een met voor zijn naar ook aan niet dat deze bij maar wordt".split(),
}
FOREIGN_WORDS = {w for ws in FOREIGN_STOP.values() for w in ws}
ENGLISH_STOP = set("the and of to in with for on his her their after when who from that this while they about into "
                   "over he she it is are was an at by up out its has have will new one".split())
WORD = re.compile(r"[^\W\d_]+", re.UNICODE)


def note_language(stats, el):
    """Adds one programme's title and description to its channel's language counters."""
    text = " ".join(filter(None, (el.findtext("title"), el.findtext("desc"))))
    if not text:
        return
    s = stats.setdefault(el.get("channel"), [0, 0, 0, 0])  # english, foreign, non-latin letters, letters
    for w in WORD.findall(text.lower()):
        s[3] += len(w)
        if not w.isascii() and any(ord(c) > 0x24F for c in w):
            s[2] += len(w)
        elif w in ENGLISH_STOP:
            s[0] += 1
        elif w in FOREIGN_WORDS:
            s[1] += 1


def non_english_reason(s):
    """Reason a channel is not English-speaking, or None. Too little text to judge counts as English."""
    if not s:
        return None
    en, foreign, nonlatin, letters = s
    if letters >= 30 and nonlatin / letters > 0.3:
        return "non-Latin script"
    if foreign >= 8 and foreign > 1.5 * en:
        return "foreign function words"
    return None


PLACEHOLDER = re.compile(
    r"(?i)^\s*(?:no\s+(?:information|info|data|programm?e?s?(?:\s+information)?)(?:\s+available)?|"
    r"programm?e?s?\s+(?:information\s+)?(?:not\s+available|unavailable)|to\s+be\s+announced|tba|"
    r"off\s+air|sign\s*off|station\s+(?:off\s+air|closed)|no\s+epg|dummy|test(?:\s+pattern)?|"
    r"local\s+programming|programming|paid\s+programming|no\s+events?(?:\s+scheduled)?|no\s+schedule|"
    r"schedule\s+(?:not\s+available|unavailable)|information\s+(?:not\s+available|unavailable)|"
    r"coming\s+soon|off[\s-]?line|closed|filler|reserved|unknown|n/?a|none|"
    r"to\s+be\s+(?:confirmed|determined)|tbc|tbd|scheduled\s+programm?e?s?|"
    r"no\s+(?:title|name|signal)|[-_.\s*#=]+)\s*\.?\s*$"
)
# Channels that exist only to fill a slot: "Dummy 3", "Slot-04", "Placeholder", "####", empty names. Names like
# "Channel 4", "Channel 5" (UK), "Channel 9" (Australia) and "PPV 1" / "Event 12" are real, so they are not listed.
CHANNEL_PLACEHOLDER = re.compile(
    r"(?i)^\s*(?:(?:dummy|test|placeholder|reserved|unused|spare|empty|blank|slot|no\s+signal|offline)"
    r"\s*[-_#:.]?\s*\d*\s*(?:hd|fhd|sd)?|[\W\d_]*)\s*$"
)
PLACEHOLDER_SHARE = 0.6
MIN_COVERAGE = 3 * 3600


def dead_reason(keep, now):
    """Why a cleaned channel's guide is useless, or None. keep = [[start, stop, element], ...] sorted."""
    if keep[-1][1] < now:
        return "ended"
    if keep[-1][1] - keep[0][0] < MIN_COVERAGE:
        return "short coverage"
    titles = {(el.findtext("title") or "").strip().lower() for _, _, el in keep}
    if all(PLACEHOLDER.match(t) or not t for t in titles):
        return "placeholder titles"
    return None


def country_of(channel_id):
    """Country from the id suffix: CNN.HD.us2 -> US, RAIITALIA.au -> AU, anything else -> ZZ."""
    m = re.search(r"\.([a-z]{2})[a-z0-9_]*$", channel_id.lower())
    return m.group(1).upper() if m else "ZZ"


def main():
    args = sys.argv[1:]
    countries, keep_hours = [], 6.0
    if "--countries" in args:
        i = args.index("--countries")
        countries = [c.strip().upper() for c in args[i + 1].split(",") if c.strip()]
        del args[i:i + 2]
    if "--keep-hours" in args:
        i = args.index("--keep-hours")
        keep_hours = float(args[i + 1])
        del args[i:i + 2]
    max_days, desc_chars, slim = None, None, False
    if "--max-days" in args:
        i = args.index("--max-days")
        max_days = float(args[i + 1])
        del args[i:i + 2]
    if "--desc-chars" in args:
        i = args.index("--desc-chars")
        desc_chars = int(args[i + 1])
        del args[i:i + 2]
    if "--slim" in args:
        slim = True
        args.remove("--slim")
    only_ids = None
    excluded = set()
    if "--only-ids" in args:
        i = args.index("--only-ids")
        only_ids = {
            line.strip().lower() for line in Path(args[i + 1]).read_text(encoding="utf-8-sig").splitlines()
            if line.strip() and not line.startswith("#")
        }
        del args[i:i + 2]
        if not only_ids:
            raise SystemExit("--only-ids file has no ids")
    english_only = "--english-only" in args
    if english_only:
        args.remove("--english-only")
    one_per_channel = "--one-per-channel" in args
    if one_per_channel:
        args.remove("--one-per-channel")
    src, dst = Path(args[0]), Path(args[1])
    cutoff = time.time() - keep_hours * 3600
    horizon = time.time() + max_days * 86400 if max_days is not None else None
    db_path = dst.with_suffix(".work.sqlite")
    db_path.unlink(missing_ok=True)
    db = sqlite3.connect(db_path)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute("CREATE TABLE prog(channel TEXT, start INT, stop INT, xml BLOB)")

    channels = {}
    lang_stats = {}
    stats = dict(read=0, stale=0, empty=0, repeat=0, clipped=0, unknown_channel=0)
    with open(src, "rb") as probe:
        is_gzip = probe.read(2) == b"\x1f\x8b"
    with (gzip.open(src, "rb") if is_gzip else open(src, "rb")) as f:
        for _, el in ET.iterparse(f, events=("end",)):
            if el.tag == "channel":
                if only_ids is not None and (el.get("id") or "").lower() not in only_ids:
                    excluded.add(el.get("id"))
                    el.clear()
                else:
                    channels[el.get("id")] = el
            elif el.tag == "programme":
                stats["read"] += 1
                if el.get("channel") in excluded:
                    stats["not_in_list"] = stats.get("not_in_list", 0) + 1
                    el.clear()
                    continue
                if english_only:
                    note_language(lang_stats, el)
                start, stop = parse_time(el.get("start")), parse_time(el.get("stop"))
                if start is None or stop is None or stop <= start:
                    stats["empty"] += 1
                elif stop < cutoff:
                    stats["stale"] += 1
                elif horizon is not None and start > horizon:
                    stats["future"] = stats.get("future", 0) + 1
                elif el.get("channel") not in channels:
                    stats["unknown_channel"] += 1
                else:
                    title = el.find("title")
                    if title is not None and title.text:
                        title.text = clean_title(title.text)
                    desc = el.find("desc")
                    if desc is not None and desc_chars is not None:
                        if desc_chars == 0 or not desc.text:
                            el.remove(desc)
                        elif len(desc.text) > desc_chars:
                            desc.text = desc.text[:desc_chars].rsplit(" ", 1)[0].rstrip(" ,;:-") + "…"
                    if slim:
                        for child in list(el):
                            if child.tag not in ("title", "sub-title", "desc"):
                                el.remove(child)
                        for child in el:
                            child.attrib.pop("lang", None)
                    db.execute("INSERT INTO prog VALUES(?,?,?,?)",
                               (el.get("channel"), start, stop, ET.tostring(el, encoding="utf-8")))
                el.clear()
    db.execute("CREATE INDEX by_channel ON prog(channel, start)")

    def rank(cid):
        c = country_of(cid)
        return (countries.index(c) if c in countries else len(countries), c)

    ordered = sorted(
        channels,
        key=lambda cid: (rank(cid), clean_display(channels[cid].findtext("display-name") or cid).lower(), cid),
    )
    alias_of = {}
    non_english = {}
    if english_only:
        for cid in channels:
            why = non_english_reason(lang_stats.get(cid))
            if why:
                non_english[cid] = why
        eng_path = dst.with_name(dst.name.split(".")[0] + ".nonenglish.tsv")
        eng_path.write_text("".join(f"{c}\t{w}\n" for c, w in sorted(non_english.items())), encoding="utf-8")
        stats["non_english"] = len(non_english)
    if one_per_channel:
        counts = dict(db.execute("SELECT channel, COUNT(*) FROM prog GROUP BY channel"))
        best = {}
        for cid in ordered:
            key = (country_of(cid), re.sub(r"[^a-z0-9]+", "", clean_display(channels[cid].findtext("display-name") or cid).lower()))
            if not key[1]:
                continue
            cur = best.get(key)
            if cur is None or counts.get(cid, 0) > counts.get(cur, 0):
                if cur is not None:
                    alias_of[cur] = cid
                best[key] = cid
            else:
                alias_of[cid] = cur
        aliases_path = dst.with_name(dst.name.split(".")[0] + ".aliases.tsv")
        aliases_path.write_text("".join(f"{d}\t{k}\n" for d, k in sorted(alias_of.items())), encoding="utf-8")
        stats["merged_channels"] = len(alias_of)
    kept_channels = kept_programmes = 0
    dead = []
    now = time.time()
    body = dst.with_suffix(".body.tmp")
    with open(body, "wb") as out_body, gzip.open(dst, "wb", compresslevel=9) as out:
        out.write(b'<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="CartelTV cleaned guide">\n')
        chan_xml = []
        for cid in ordered:
            if cid in alias_of or cid in non_english:
                continue
            if CHANNEL_PLACEHOLDER.match(channels[cid].findtext("display-name") or ""):
                dead.append((cid, "placeholder channel"))
                continue
            rows = db.execute("SELECT start, stop, xml FROM prog WHERE channel=? ORDER BY start, stop DESC", (cid,)).fetchall()
            last_by_title = {}
            keep = []
            for start, stop, xml in rows:
                el = ET.fromstring(xml)
                title = (el.findtext("title") or "").strip().lower()
                prev = last_by_title.get(title)
                if prev is not None and start - prev < REPEAT_WINDOW:
                    stats["repeat"] += 1
                    continue
                last_by_title[title] = start
                if keep and keep[-1][1] > start:
                    stats["clipped"] += 1
                    keep[-1][1] = start
                    if keep[-1][1] <= keep[-1][0]:
                        keep.pop()
                keep.append([start, stop, el])
            keep = [k for k in keep if k[1] > k[0]]
            if not keep:
                dead.append((cid, "no programmes"))
                continue
            filler = [k for k in keep if PLACEHOLDER.match((k[2].findtext("title") or "").strip())]
            if len(filler) >= PLACEHOLDER_SHARE * len(keep):
                dead.append((cid, "mostly placeholder titles"))
                continue
            if filler:
                stats["placeholder_programmes"] = stats.get("placeholder_programmes", 0) + len(filler)
                keep = [k for k in keep if k not in filler]
            reason = dead_reason(keep, now)
            if reason:
                dead.append((cid, reason))
                continue
            kept_channels += 1
            ch = channels[cid]
            names = ch.findall("display-name")
            if names:
                names[0].text = clean_display(names[0].text or cid)
            chan_xml.append(ET.tostring(ch, encoding="utf-8"))
            for start, stop, el in keep:
                el.set("start", format_time(start))
                el.set("stop", format_time(stop))
                out_body.write(ET.tostring(el, encoding="utf-8"))
                out_body.write(b"\n")
                kept_programmes += 1
        for c in chan_xml:
            out.write(c)
            out.write(b"\n")
        out_body.flush()
        with open(body, "rb") as rb:
            while chunk := rb.read(1 << 20):
                out.write(chunk)
        out.write(b"</tv>\n")
    body.unlink()
    db.close()
    db_path.unlink(missing_ok=True)
    print(f"read {stats['read']} programmes -> kept {kept_programmes} on {kept_channels} of {len(channels)} channels")
    dead_path = dst.with_name(dst.name.split(".")[0] + ".dead.tsv")
    dead_path.write_text("".join(f"{cid}\t{why}\n" for cid, why in dead), encoding="utf-8")
    by_reason = {}
    for _, why in dead:
        by_reason[why] = by_reason.get(why, 0) + 1
    print(f"dead guides removed: {len(dead)} {by_reason} (listed in {dead_path.name})")
    if only_ids is not None:
        present = {c.lower() for c in channels} | {c.lower() for c in excluded}
        missing = sorted(only_ids - present)
        miss_path = dst.with_name(dst.name.split(".")[0] + ".missing-ids.txt")
        miss_path.write_text("".join(m + "\n" for m in missing), encoding="utf-8")
        print(f"--only-ids: {len(only_ids)} ids listed, {len(only_ids) - len(missing)} found in the guide, "
              f"{len(missing)} not in any guide (see {miss_path.name}); {len(excluded)} guide channels "
              f"({stats.get('not_in_list', 0)} programmes) not in your list were dropped")
    print(f"placeholder programmes removed from real channels: {stats.get('placeholder_programmes', 0)}")
    print(f"non-English channels removed: {stats.get('non_english', 0)}")
    print(f"removed beyond horizon: {stats.get('future', 0)}; duplicate channels folded into one per country: {stats.get('merged_channels', 0)}")
    print(f"removed: stale {stats['stale']}, empty/reversed {stats['empty']}, repeat listings {stats['repeat']}, "
          f"unknown channel {stats['unknown_channel']}; clipped overlaps {stats['clipped']}")
    print(f"{dst} {dst.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
