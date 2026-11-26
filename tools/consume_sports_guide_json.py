#!/usr/bin/env python3
"""Consume a CartelTV sports-guide JSON feed (local file or HTTP URL).

The feed is produced by tools/export_sports_guide_json.py and published by
.github/workflows/sports-guide.yml.

Usage:
  python tools/consume_sports_guide_json.py public/sports-guide.json --summary
  python tools/consume_sports_guide_json.py https://edgel00p.github.io/CartelTv/sports-guide.json --summary
  python tools/consume_sports_guide_json.py sports-guide.json --xmltv out.xml.gz
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_URL = "https://edgel00p.github.io/CartelTv/sports-guide.json"
UA = "Mozilla/5.0 CartelTV-sports-guide-consumer/1.0"


def load(source: str) -> dict:
    if source.startswith("http://") or source.startswith("https://"):
        req = urllib.request.Request(source, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.load(resp)
    return json.loads(Path(source).read_text(encoding="utf-8"))


def parse_iso(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def xmltv_time(dt: datetime) -> str:
    return dt.strftime("%Y%m%d%H%M%S +0000")


def summary(feed: dict) -> str:
    lines = [
        f"schema: {feed.get('schema')}",
        f"generated_at: {feed.get('generated_at')}",
        f"leagues: {feed.get('league_count')}  games: {feed.get('game_count')}  days: {feed.get('days')}",
    ]
    leagues = feed.get("leagues", [])
    active = [l for l in leagues if l.get("events")]
    for league in active[:30]:
        rights = ", ".join(league.get("rights_curated", [])[:3])
        suffix = f" | rights: {rights}" if rights else ""
        lines.append(f"  - {league.get('name')}: {league.get('events')} events{suffix}")
    if len(active) > 30:
        lines.append(f"  ... {len(active) - 30} more active leagues")
    return "\n".join(lines)


def write_xmltv(feed: dict, out_path: str) -> tuple[int, int]:
    root = ET.Element("tv", {"generator-info-name": "CartelTV sports-guide-json consumer"})
    seen_channels: set[str] = set()
    for league in feed.get("leagues", []):
        if not league.get("events"):
            continue
        slug = league.get("slug") or league.get("path", "unknown").split("/")[-1]
        cid = f"Sports.{slug}.cartel"
        if cid in seen_channels:
            continue
        seen_channels.add(cid)
        ch = ET.SubElement(root, "channel", id=cid)
        ET.SubElement(ch, "display-name").text = league.get("name") or slug

    prog_count = 0
    for game in feed.get("games", []):
        try:
            start = parse_iso(game["start_utc"])
            end = parse_iso(game["end_utc"])
        except Exception:  # noqa: BLE001
            continue
        cid = game.get("channel_id") or f"Sports.{game.get('league_slug','unknown')}.cartel"
        if cid not in seen_channels:
            ch = ET.SubElement(root, "channel", id=cid)
            ET.SubElement(ch, "display-name").text = game.get("league_name") or game.get("league_slug") or cid
            seen_channels.add(cid)
        prog = ET.SubElement(root, "programme", start=xmltv_time(start), stop=xmltv_time(end), channel=cid)
        ET.SubElement(prog, "title", lang="en").text = game.get("title") or game.get("league_name") or "Sports"
        desc_parts = []
        broadcasters = [b for b in (game.get("broadcasters_espn") or []) if b]
        rights = [r for r in (game.get("rights_curated") or []) if r]
        if broadcasters:
            desc_parts.append("Watch on " + ", ".join(broadcasters))
        if rights:
            desc_parts.append("Rights: " + ", ".join(rights))
        if game.get("venue"):
            desc_parts.append(str(game["venue"]))
        if desc_parts:
            ET.SubElement(prog, "desc", lang="en").text = ". ".join(desc_parts)
        ET.SubElement(prog, "category", lang="en").text = "Sports"
        prog_count += 1

    with gzip.open(out_path, "wb") as fh:
        ET.ElementTree(root).write(fh, encoding="utf-8", xml_declaration=True)
    return len(seen_channels), prog_count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", default=DEFAULT_URL, help="JSON file path or HTTP(S) URL")
    parser.add_argument("--summary", action="store_true", help="Print feed summary (default if no output option)")
    parser.add_argument("--xmltv", help="Write an XMLTV .xml.gz converted from the feed")
    args = parser.parse_args()

    feed = load(args.source)
    if args.xmltv:
        ch, prog = write_xmltv(feed, args.xmltv)
        print(f"wrote {args.xmltv}: {ch} channels, {prog} programmes", file=sys.stderr)
    if args.summary or not args.xmltv:
        print(summary(feed))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
