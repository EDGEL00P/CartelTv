import gzip
import json
import sys
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from pipeline import __main__ as cli
from pipeline.exceptions import QualityError
from pipeline.orchestration import Orchestrator, Task
from pipeline.quality import check_future_programmes, check_row_count, check_unique, enforce_suite, run_suite
from pipeline.validation import validate_batch


class QualityChecks(unittest.TestCase):
    def test_check_row_count(self):
        with self.assertRaises(QualityError):
            check_row_count([], min_rows=1)
        check_row_count([1], min_rows=1)

    def test_check_unique(self):
        with self.assertRaises(QualityError):
            check_unique([{"id": 1}, {"id": 1}], "id")
        check_unique([{"id": 1}, {"id": 2}], "id")

    def test_run_suite_counts_passes_and_failures(self):
        r = run_suite(
            [],
            [
                {"type": "row_count", "min": 1, "name": "rc"},
                {"type": "row_count", "min": 0, "name": "ok"},
            ],
        )
        self.assertEqual((r["passed"], r["failed"]), (1, 1))
        self.assertIn("rc", r["errors"][0])

    def test_enforce_suite_raises_on_failure(self):
        with self.assertRaises(QualityError):
            enforce_suite([], [{"type": "row_count", "min": 1, "name": "rc"}])
        self.assertEqual(enforce_suite([{"id": 1}], [{"type": "row_count", "min": 1}])["failed"], 0)

    def test_validate_batch(self):
        out = validate_batch([{"id": 1}, {"id": 2}], ["id"])
        self.assertEqual(len(out), 2)

    def test_check_future_programmes(self):
        with tempfile.TemporaryDirectory() as tmp:
            stale = Path(tmp) / "stale.xml.gz"
            with gzip.open(stale, "wb") as fh:
                fh.write(b'<tv><programme stop="20200101000000"/></tv>')
            with self.assertRaises(QualityError):
                check_future_programmes(stale)
            fresh = Path(tmp) / "fresh.xml.gz"
            with gzip.open(fresh, "wb") as fh:
                fh.write(b'<tv><programme stop="20200101000000"/><programme stop="29991231235959"/></tv>')
            check_future_programmes(fresh)


class OrchestratorRun(unittest.TestCase):
    def test_dependencies_run_first_and_results_are_stored(self):
        seen = []
        orch = Orchestrator()
        orch.add(Task("second", lambda ctx: seen.append("second"), deps=["first"], retries=0))
        orch.add(Task("first", lambda ctx: seen.append("first"), retries=0))
        orch.run()
        self.assertEqual(seen, ["first", "second"])

    def test_a_failing_task_blocks_its_dependents(self):
        ran = []
        orch = Orchestrator()
        orch.add(Task("boom", lambda ctx: (_ for _ in ()).throw(ValueError("no")), retries=0))
        orch.add(Task("after", lambda ctx: ran.append(True), deps=["boom"], retries=0))
        with self.assertRaises(ValueError):
            orch.run()
        self.assertEqual(ran, [])


class PipelineRun(unittest.TestCase):
    """cmd_run end to end with the network and the XML tools stubbed."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.sources = self.root / "sources.json"
        self.sources.write_text(
            json.dumps({"sources": [{"name": "one", "url": "https://example.test/one.xml.gz"}]}),
            encoding="utf-8",
        )
        self.args = Namespace(
            sources=str(self.sources),
            out_dir=str(self.root / "out"),
            raw_dir=str(self.root / "raw"),
            max_days=7,
            log_level="WARNING",
        )

    def tearDown(self):
        self.tmp.cleanup()

    def _stub_tools(self):
        def fake_download(url, dest, **kwargs):
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(b"raw")
            return 3

        def fake_clean(raw, out, **kwargs):
            out.write_text("<tv/>", encoding="utf-8")

        def fake_merge(inputs, out, **kwargs):
            # A guide with a programme that ends far in the future passes the freshness gate.
            with gzip.open(out, "wb") as fh:
                fh.write(b'<tv><programme stop="29991231235959"/></tv>')

        return mock.patch.multiple(
            "pipeline.ingestion", download=fake_download
        ), mock.patch.object(cli, "clean_epg", fake_clean), mock.patch.object(cli, "merge_guides", fake_merge)

    def test_a_successful_run_publishes_the_manifest(self):
        d, c, m = self._stub_tools()
        with d, c, m:
            self.assertEqual(cli.cmd_run(self.args), 0)
        out = self.root / "out"
        manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["quality"]["failed"], 0)
        self.assertEqual(manifest["dead_letter"], 0)
        # Publishing promoted the staged files: final names exist, no .part remains,
        # and the manifest points at the promoted paths.
        self.assertTrue((out / "merged.xml.gz").is_file())
        self.assertTrue((out / "cleaned_raw_one.xml").is_file())
        self.assertEqual(manifest["merged"], str(out / "merged.xml.gz"))
        self.assertEqual(manifest["cleaned"], [str(out / "cleaned_raw_one.xml")])
        self.assertEqual(list(out.glob("*.part")), [])

    def test_every_source_failing_fails_the_run_without_a_manifest(self):
        def refuse(url, dest, **kwargs):
            raise OSError("down")

        with mock.patch("pipeline.ingestion.download", refuse), \
                mock.patch("pipeline.ingestion.time.sleep", lambda *_: None), \
                mock.patch("pipeline.orchestration.time.sleep", lambda *_: None):
            self.assertEqual(cli.cmd_run(self.args), 1)
        self.assertFalse((self.root / "out" / "manifest.json").exists())
        self.assertTrue((self.root / "out" / "dead_letter.jsonl").exists())

    def test_a_quality_failure_fails_the_run(self):
        def empty_clean(raw, out, **kwargs):
            out.write_text("", encoding="utf-8")

        d, _, m = self._stub_tools()
        with d, mock.patch.object(cli, "clean_epg", empty_clean), m:
            self.assertEqual(cli.cmd_run(self.args), 1)
        out = self.root / "out"
        self.assertFalse((out / "manifest.json").exists())
        # A failed run never publishes: the final names stay absent and the staged
        # .part files are left behind for inspection.
        self.assertFalse((out / "cleaned_raw_one.xml").exists())
        self.assertFalse((out / "merged.xml.gz").exists())
        self.assertTrue((out / "cleaned_raw_one.xml.part").is_file())
