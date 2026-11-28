# Testing

*Status: current · Owner: repo maintainer · Last verified: 2026-11-27*

What to run locally, and what CI runs for you. Exact commands; expected results included so a
green run is checkable, not assumed.

## Kotlin (the app)

```bash
./gradlew :app:testDebugUnitTest
```

- One command runs everything: plain JUnit4, Robolectric suites (where Android framework types
  like `Uri` are needed) and the Roborazzi screenshot tests — the Roborazzi Gradle plugin is
  deliberately not applied; the library honours its `-Proborazzi.*` system properties on the plain
  test task (`app/build.gradle.kts` has the note).
- As of 2026-11-27 a full run is 1,010 tests, 0 failures/errors/skipped. Any delta from that is a
  finding to explain, not noise to ignore.
- House style: plain JUnit4, camelCase test names, `AndroidJUnit4` only where framework types
  require it. The engine's policies (`engine/stability/`) are pure classes, so their behavior is
  pinned by fast JVM tests, not device runs.
- CI also runs `lintDebug`, `assembleDebug` and an unsigned `assembleRelease` (an R8 shrinking
  check) in the `android` job of [ci.yml](../.github/workflows/ci.yml).

## Python (guide tools)

```bash
python -m compileall -q tools
python -m unittest discover -s tools -p "test_*.py"
```

- The compile check is part of CI's `python-tools` job, followed by the same unittest discovery
  and `python tools/bughunt.py --fail-on error`. As of 2026-11-27 the discovery runs 47 tests.
- The pipeline CLI's dependencies (pydantic, PyYAML — pinned in
  `tools/pipeline/requirements.txt`) are needed by the pipeline package, not by the test
  discovery of the other tools.

## Docs

```bash
python tools/check_docs.py
```

Checks every markdown file under the repo root and `docs/`: internal links resolve, every
`docs/**` file is indexed from [README.md](README.md), fenced code carries a language tag, prose
stays within 120 columns (tables exempt), and external links answer. Add `--skip-external` to
skip the network round-trips. CI runs this in the `python-tools` job.

## Docs of the tests themselves

When a test fails: read the assertion, not just the suite name — the house tests are written to
state the behavior contract, so a failure names the rule that broke.
