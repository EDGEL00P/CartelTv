import tempfile
import unittest
from pathlib import Path
from unittest import mock

from check_docs import (
    external_links,
    fence_problems,
    index_problems,
    internal_link_problems,
    iter_prose,
    line_problems,
    links_in,
    url_ok,
)


class ProseScan(unittest.TestCase):
    def test_fenced_code_is_skipped_and_closing_fence_needs_no_tag(self):
        lines = ["```python", "x = 1", "```"]
        self.assertEqual(list(iter_prose(lines)), [])
        self.assertEqual(fence_problems(lines), [])

    def test_untagged_fence_is_a_problem(self):
        lines = ["```", "plain", "```"]
        self.assertEqual(fence_problems(lines), ["line 1: fenced code block without a language tag"])

    def test_links_inside_code_fences_are_ignored(self):
        lines = ["```text", "[dead](nope.md)", "```", "[live](here.md)"]
        self.assertEqual([t for _n, t in links_in(lines)], ["here.md"])

    def test_long_prose_line_is_flagged_but_tables_and_urls_are_exempt(self):
        lines = ["x" * 130, "| " + "y" * 130 + " |", "[docs](https://" + "z" * 200 + ")"]
        self.assertEqual(len(line_problems(lines)), 1)


class InternalLinks(unittest.TestCase):
    def test_relative_and_absolute_targets_resolve(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "target.md").write_text("", encoding="utf-8")
            (root / "sub").mkdir()
            (root / "sub" / "linker.md").write_text(
                "[ok](../target.md) [gone](../missing.md) [anchor](../target.md#top)", encoding="utf-8"
            )
            problems = internal_link_problems(root / "sub" / "linker.md", (root / "sub" / "linker.md").read_text().splitlines())
            self.assertEqual(problems, ["line 1: broken internal link ../missing.md"])

    def test_external_and_mailto_targets_are_left_alone(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "d.md"
            path.write_text("[site](https://example.test) [mail](mailto:a@b.c)", encoding="utf-8")
            self.assertEqual(internal_link_problems(path, path.read_text().splitlines()), [])


class IndexCoverage(unittest.TestCase):
    def test_unindexed_docs_are_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            docs = Path(tmp)
            (docs / "README.md").write_text("[one](one.md)\n", encoding="utf-8")
            (docs / "one.md").write_text("", encoding="utf-8")
            (docs / "adr").mkdir()
            (docs / "adr" / "0001-x.md").write_text("", encoding="utf-8")
            with mock.patch("check_docs.DOCS", docs), mock.patch("check_docs.ROOT", docs):
                expected = str(Path("adr") / "0001-x.md")
                self.assertEqual(
                    index_problems([(docs / "README.md", ["[one](one.md)"])]),
                    [f"{expected}: not indexed in docs/README.md"],
                )


class External(unittest.TestCase):
    def test_link_collection_dedupes_across_files(self):
        files = [
            (Path("a.md"), ["[x](https://example.test/1)"]),
            (Path("b.md"), ["[y](https://example.test/1)", "[z](https://example.test/2)"]),
        ]
        self.assertEqual(external_links(files), ["https://example.test/1", "https://example.test/2"])

    def test_soft_status_codes_pass_and_hard_errors_fail(self):
        import urllib.error
        with mock.patch("urllib.request.urlopen", side_effect=urllib.error.HTTPError(None, 403, None, None, None)):
            self.assertIsNone(url_ok("https://example.test/1"))
        with mock.patch("urllib.request.urlopen", side_effect=urllib.error.HTTPError(None, 410, None, None, None)):
            self.assertEqual(url_ok("https://example.test/1"), "HTTP 410")


if __name__ == "__main__":
    unittest.main()
