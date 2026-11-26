import gzip
import os
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import big_events as be  # noqa: E402

NOW = 1_800_000_000  # a fixed "now" so the tests do not depend on the clock


def xmltv_time(epoch):
    return time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(epoch))


def write_guide(directory, region, channel_name, programmes):
    """programmes: list of (title, sub_title_or_None, start_offset_hours)."""
    rows = [f'<channel id="c1"><display-name>{channel_name}</display-name></channel>']
    for title, sub, hours in programmes:
        start = NOW + int(hours * 3600)
        sub_xml = f"<sub-title>{sub}</sub-title>" if sub else ""
        rows.append(
            f'<programme start="{xmltv_time(start)}" stop="{xmltv_time(start + 7200)}" channel="c1">'
            f"<title>{title}</title>{sub_xml}</programme>"
        )
    path = os.path.join(directory, f"epg_{region}.xml.gz")
    with gzip.open(path, "wt", encoding="utf-8") as fh:
        fh.write("<tv>" + "".join(rows) + "</tv>")


class EventMatch(unittest.TestCase):
    def test_a_live_marked_grand_prix_counts(self):
        self.assertEqual(be.event_match("Live: Formula 1: Las Vegas Grand Prix", None), ("Motorsport", 7))

    def test_the_same_title_without_a_live_mark_is_not_enough(self):
        self.assertIsNone(be.event_match("Formula 1: Las Vegas Grand Prix", None))

    def test_a_confirmed_numbered_card_counts_without_a_live_mark(self):
        confirmed = be.espn_confirmed(["UFC 335: Oliveira vs. Lopes"])
        self.assertEqual(be.event_match("UFC PPV: UFC 335", None, confirmed=confirmed), ("UFC", 10))

    def test_an_unconfirmed_old_numbered_card_is_a_replay_not_an_event(self):
        confirmed = be.espn_confirmed(["UFC 335: Oliveira vs. Lopes"])
        self.assertIsNone(be.event_match("Anderson Silva vs. Forrest Griffin UFC 101", None, confirmed=confirmed))

    def test_a_fight_night_is_confirmed_by_both_surnames(self):
        confirmed = be.espn_confirmed(["UFC Fight Night: Adesanya vs Magomedov"])
        self.assertEqual(be.event_match("UFC Fight Night: Adesanya v Magomedov", None, confirmed=confirmed), ("UFC", 7))

    def test_highlights_documentaries_and_tickers_are_not_the_event(self):
        for title in (
            "Live: UFC 334 HL: Gane v Hokit",
            "Live: Top Super Bowl Moments",
            "Live: America's Game: The Super Bowl Champions: 1983 Raiders",
            "Live: Next match: UFC 335 at 12/12/2026 08:00 PM EST",
        ):
            self.assertIsNone(be.event_match(title, None), title)

    def test_ambiguous_phrases_need_their_sport(self):
        self.assertIsNone(be.event_match("LIVE: MiniGP - MotoMini World Series Final", None))
        self.assertIsNone(be.event_match("LIVE: MotorRacing 360 - Adelaide Grand Final Street Party", None))
        self.assertEqual(be.event_match("Live: AFL Grand Final", None), ("Australian", 9))

    def test_a_concert_at_a_venue_is_not_the_olympics(self):
        self.assertIsNone(be.event_match("Tom Petty Live at The Olympic", None))

    def test_equestrian_grand_prix_is_not_motorsport(self):
        self.assertIsNone(be.event_match("Live: Global Champions Tour: Verona: Super Grand Prix", None))

    def test_skiing_world_cup_is_not_soccer(self):
        self.assertIsNone(be.event_match("Live: Alpine Skiing World Cup", None))
        self.assertEqual(be.event_match("Live: 2026 FIFA U-17 World Cup", None), ("Soccer", 9))

    def test_a_date_in_the_title_that_is_not_the_airing_date_is_a_rerun(self):
        december_13 = 1_797_000_000  # mid December
        self.assertTrue(be.announced_date_mismatch("UFC PPV: December 13th: UFC 335", december_13 - 20 * 86400))
        self.assertFalse(be.announced_date_mismatch("Fight night", NOW))


class Build(unittest.TestCase):
    def build(self, **kw):
        return be.build(self.dir, kw.pop("regions", ("US2", "UK1")), 14, 50, now=NOW, **kw)

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    def test_the_same_event_on_two_channels_and_regions_is_one_entry_naming_both(self):
        write_guide(self.dir, "US2", "Fox Sports", [("Live: Formula 1: Las Vegas Grand Prix", None, 5)])
        write_guide(self.dir, "UK1", "Sky F1", [("Live: Formula 1: Las Vegas Grand Prix", None, 5)])
        events = self.build()["events"]
        self.assertEqual(len(events), 1)
        self.assertEqual({c["name"] for c in events[0]["channels"]}, {"Fox Sports", "Sky F1"})
        self.assertEqual(set(events[0]["regions"]), {"US2", "UK1"})

    def test_guides_outside_the_chosen_regions_are_ignored(self):
        write_guide(self.dir, "FR1", "Canal", [("Live: Formula 1: Las Vegas Grand Prix", None, 5)])
        self.assertEqual(self.build()["events"], [])

    def test_radio_channels_are_skipped(self):
        write_guide(self.dir, "UK1", "BBC R5L", [("Live: Boxing night", None, 5)])
        self.assertEqual(self.build()["events"], [])

    def test_programmes_beyond_the_window_or_long_past_are_dropped(self):
        write_guide(self.dir, "US2", "Fox Sports", [
            ("Live: Formula 1: Far Grand Prix", None, 24 * 30),
            ("Live: Formula 1: Old Grand Prix", None, -48),
        ])
        self.assertEqual(self.build()["events"], [])

    def test_one_event_per_sport_per_day_keeps_the_biggest(self):
        write_guide(self.dir, "US2", "Sports", [
            ("Live: Formula 1: Qualifying Grand Prix", None, 3),
            ("Live: UFC 335", None, 4),
            ("Live: Formula 1: Race Grand Prix", None, 5),
        ])
        events = self.build()["events"]
        self.assertEqual([e["sport"] for e in events].count("Motorsport"), 1)
        self.assertEqual(events[0]["sport"], "UFC")

    def test_the_title_gains_its_sub_title(self):
        write_guide(self.dir, "US2", "Sports", [("Live: UFC Fight Night", "Adesanya vs Magomedov", 3)])
        self.assertEqual(self.build()["events"][0]["title"], "Live: UFC Fight Night: Adesanya vs Magomedov")


KAYO_HTML = """
<section date="2026-11-22"><p>Sun</p><h2>22</h2></section>
<article data-target-id="Epg:a" data-target-title="F1 Las Vegas Race"><header><span id="tile-labels-Epg:a">2:30 am</span></header>
<footer><div><h3>F1 Las Vegas Race</h3></div><div><p>F1 World Championship 2026</p></div></footer></article>
<article data-target-id="Epg:b" data-target-title="Las Vegas Practice"><header><span id="tile-labels-Epg:b">11:00 pm</span></header>
<footer><div><p>F1 Academy</p></div></footer></article>
<article data-target-id="Epg:c" data-target-title="KAYO ONLY - Duke v Clemson"><header><span id="tile-labels-Epg:c">LIVE</span></header>
<footer><div><p>NCAA Division I</p></div></footer></article>
<section date="2026-11-23"><p>Mon</p></section>
<article data-target-id="Epg:d" data-target-title="Teremoana v Ahio"><header><span id="tile-labels-Epg:d">12:00 am</span></header>
<footer><div><p>Matchroom Boxing</p></div></footer></article>
"""

ESPN_JSON = {"events": [
    {"name": "UFC 335: Oliveira vs. Lopes", "date": "2026-12-12T22:00Z", "status": {"type": {"state": "pre"}},
     "competitions": [{"broadcasts": [{"names": ["Paramount+"]}], "geoBroadcasts": []}]},
    {"name": "Green Bay Packers at Los Angeles Rams", "date": "2026-11-26T01:00Z", "status": {"type": {"state": "pre"}},
     "competitions": [{"broadcasts": [], "geoBroadcasts": [{"media": {"shortName": "Netflix"}}]}]},
    {"name": "TBD at TBD", "date": "2026-12-04T05:00Z", "status": {"type": {"state": "pre"}},
     "competitions": [{"broadcasts": [{"names": ["Prime Video"]}]}]},
    {"name": "Pittsburgh Penguins at Dallas Stars", "date": "2026-11-23T01:00Z", "status": {"type": {"state": "pre"}},
     "competitions": [{"broadcasts": [{"names": ["Prime Video (Local)", "ESPN+"]}]}]},
]}


class Kayo(unittest.TestCase):
    def test_times_are_utc_and_follow_the_day_section(self):
        import kayo_schedule
        fixtures = {f["title"]: f for f in kayo_schedule.parse(KAYO_HTML, now=NOW)}
        self.assertEqual(fixtures["F1 Las Vegas Race"]["start"], 1_795_314_600)  # 2026-11-22 02:30 UTC
        self.assertEqual(fixtures["Teremoana v Ahio"]["start"], 1_795_392_000)  # 2026-11-23 00:00 UTC
        self.assertEqual(fixtures["Las Vegas Practice"]["competition"], "F1 Academy")

    def test_live_tiles_start_now_and_kayo_only_is_flagged(self):
        import kayo_schedule
        live = next(f for f in kayo_schedule.parse(KAYO_HTML, now=NOW) if f["live"])
        self.assertEqual(live["start"], NOW)
        self.assertTrue(live["kayo_only"])

    def test_kayo_turns_races_and_boxing_into_events_but_not_practice(self):
        now = 1_795_300_000
        events = be.kayo_events(KAYO_HTML, now, now + 5 * 86400)
        self.assertEqual({e["title"] for e in events}, {"F1 Las Vegas Race", "Teremoana v Ahio"})
        self.assertTrue(all(e["streams"] == ["Kayo"] for e in events))


class Espn(unittest.TestCase):
    def test_streaming_services_come_from_broadcasts_and_geo_broadcasts_but_not_local_feeds(self):
        parsed = {e["name"]: e["streams"] for e in be.espn_events_from_json(ESPN_JSON, "NFL")}
        self.assertEqual(parsed["UFC 335: Oliveira vs. Lopes"], ["Paramount+"])
        self.assertEqual(parsed["Green Bay Packers at Los Angeles Rams"], ["Netflix"])
        self.assertEqual(parsed["Pittsburgh Penguins at Dallas Stars"], ["ESPN+"])

    def test_national_streaming_only_games_become_events_but_tbd_slots_and_local_feeds_do_not(self):
        espn = be.espn_events_from_json(ESPN_JSON, "NFL")
        now = 1_795_000_000
        titles = {e["title"] for e in be.exclusive_events(espn, now, now + 40 * 86400)}
        self.assertEqual(titles, {"Green Bay Packers at Los Angeles Rams"})

    def test_a_confirmed_card_lists_the_services_espn_gives_it(self):
        espn = be.espn_events_from_json(ESPN_JSON, "UFC")
        self.assertEqual(be.streams_for("UFC PPV: UFC 335", espn), ["Paramount+"])
        self.assertEqual(be.streams_for("UFC 101", espn), [])


class Dedupe(unittest.TestCase):
    def test_different_series_on_the_same_day_stay_separate_but_one_race_weekend_merges(self):
        tmp = tempfile.TemporaryDirectory()
        write_guide(tmp.name, "UK1", "Sky", [
            ("Live: Formula 1: Las Vegas Grand Prix", None, 5),
            ("Live: Formula 1: Las Vegas Grand Prix: Race", None, 6),
            ("Live: MotoGP: Portuguese Grand Prix", None, 7),
        ])
        events = be.build(tmp.name, ("UK1",), 14, 50, now=NOW)["events"]
        tmp.cleanup()
        titles = sorted(e["title"] for e in events)
        self.assertEqual(len(titles), 2, titles)
        self.assertTrue(any("Las Vegas" in t for t in titles) and any("MotoGP" in t for t in titles))


if __name__ == "__main__":
    unittest.main()
