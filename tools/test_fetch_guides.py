import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fetch_guides as fg  # noqa: E402


class FetchGuides(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    def run_fetch(self, sources, fail=()):
        def fetch(url, dest):
            if any(f in url for f in fail):
                raise OSError("HTTP 503")
            with open(dest, "wb") as fh:
                fh.write(b"raw")

        def cleaner(raw, out, max_days):
            with open(out, "wb") as fh:
                fh.write(b"clean")

        return fg.fetch_all(sources, os.path.join(self.root, "out"), os.path.join(self.root, "raw"),
                            fetch=fetch, cleaner=cleaner)

    def test_a_failing_source_is_skipped_and_the_rest_are_built(self):
        sources = [{"name": "A", "url": "http://x/a", "kind": "linear"}, {"name": "B", "url": "http://x/b", "kind": "fast"}]  # bughunt:ignore kt-cleartext-http
        built, failed = self.run_fetch(sources, fail=("/a",))
        self.assertEqual(built, ["B"])
        self.assertEqual([name for name, _ in failed], ["A"])
        self.assertFalse(os.path.exists(os.path.join(self.root, "out", "epg_A.xml.gz")))
        self.assertTrue(os.path.exists(os.path.join(self.root, "out", "epg_B.xml.gz")))

    def test_an_unknown_kind_is_rejected_when_the_config_loads(self):
        path = os.path.join(self.root, "s.json")
        with open(path, "w") as fh:
            json.dump({"sources": [{"name": "A", "url": "u", "kind": "weird"}]}, fh)
        with self.assertRaises(ValueError):
            fg.load_sources(path)

    def test_the_shipped_config_is_valid_and_replay_guides_are_never_linear(self):
        sources = fg.load_sources(os.path.join(os.path.dirname(os.path.abspath(__file__)), "guide_sources.json"))
        by_name = {s["name"]: s["kind"] for s in sources}
        self.assertEqual(len(by_name), len(sources), "duplicate source names")
        for replay in ("PLEX_US", "ROKU_ALL", "PLUTO_US", "SAMSUNG_US"):
            self.assertEqual(by_name[replay], "fast")
        self.assertEqual(by_name["EPGPW_US"], "linear")


if __name__ == "__main__":
    unittest.main()
