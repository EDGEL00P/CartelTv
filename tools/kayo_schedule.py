#!/usr/bin/env python3
"""Read Kayo Sports' public fixtures page (https://kayosports.com.au/en-AU/schedule).

The page is server-rendered: one <section date="YYYY-MM-DD"> per day, one <article
data-target-title="..."> per live fixture, a time label ("2:30 am", or "LIVE" once it has started)
and a competition line. Times on the page are UTC; that was checked against Sky and TNT listings of
the same Formula 1 and MotoGP sessions.

Usage: python tools/kayo_schedule.py kayo.html [--now EPOCH]   (prints the parsed fixtures as JSON)
"""
import calendar
import json
import re
import sys
import time
from html.parser import HTMLParser

TIME_LABEL = re.compile(r"^\s*(\d{1,2}):(\d{2})\s*(am|pm)\s*$", re.I)


class _Schedule(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.day = None
        self.fixtures = []
        self._fx = None
        self._capture = None  # which text we are collecting: "label", "title" or "comp"
        self._in_footer = False

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "section" and a.get("date"):
            self.day = a["date"]
        elif tag == "article" and a.get("data-target-title") is not None:
            self._fx = {"day": self.day, "title": a["data-target-title"].strip(), "label": "", "competition": ""}
        elif self._fx is not None:
            if tag == "span" and (a.get("id") or "").startswith("tile-labels-"):
                self._capture = "label"
            elif tag == "footer":
                self._in_footer = True
            elif tag == "p" and self._in_footer:
                self._capture = "comp"

    def handle_endtag(self, tag):
        if tag in ("span", "p"):
            self._capture = None
        elif tag == "footer":
            self._in_footer = False
        elif tag == "article" and self._fx is not None:
            self.fixtures.append(self._fx)
            self._fx = None

    def handle_data(self, data):
        if self._fx is not None and self._capture in ("label", "comp"):
            key = "label" if self._capture == "label" else "competition"
            self._fx[key] += data


def _epoch(day, label, now):
    """UTC epoch for a fixture, or `now` when the tile says LIVE; None when the time is unreadable."""
    if label.strip().upper() == "LIVE":
        return int(now)
    m = TIME_LABEL.match(label)
    if not (day and m):
        return None
    hour, minute = int(m.group(1)) % 12, int(m.group(2))
    if m.group(3).lower() == "pm":
        hour += 12
    y, mo, d = (int(x) for x in day.split("-"))
    return calendar.timegm((y, mo, d, hour, minute, 0))


def parse(html, now=None):
    """Return the fixtures on the page: title, competition, start (UTC epoch), live, kayo_only."""
    now = now if now is not None else time.time()
    page = _Schedule()
    page.feed(html)
    out = []
    for fx in page.fixtures:
        start = _epoch(fx["day"], fx["label"], now)
        if start is None:
            continue
        competition = " ".join(fx["competition"].split())
        out.append({
            "title": fx["title"],
            "competition": competition,
            "start": start,
            "live": fx["label"].strip().upper() == "LIVE",
            "kayo_only": fx["title"].upper().startswith("KAYO ONLY") or competition.upper().startswith("KAYO ONLY"),
        })
    return out


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    when = float(sys.argv[sys.argv.index("--now") + 1]) if "--now" in sys.argv else None
    with open(sys.argv[1], encoding="utf-8") as fh:
        print(json.dumps(parse(fh.read(), when), indent=1, ensure_ascii=False))
