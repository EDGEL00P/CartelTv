#!/usr/bin/env python3
"""bughunt: repo-specific bug finder for CartelTV.

Scans Kotlin, Android manifests and Python tools with rules tuned to the bugs
this app can actually ship (swallowed coroutine cancellation, cleartext HTTP,
trust-all TLS, leaked secrets, unsafe WebView, exported components).

  python tools/bughunt.py                       # text report, exit 1 on new findings
  python tools/bughunt.py --sarif out.sarif     # for GitHub code scanning
  python tools/bughunt.py --json out.json
  python tools/bughunt.py --update-baseline     # accept current findings
  python tools/bughunt.py --issues              # file GitHub issues (GITHUB_TOKEN, GITHUB_REPOSITORY)

Findings are fingerprinted by rule + file + normalized line text, so they
survive line-number drift. Findings listed in tools/bughunt-baseline.json are
not reported as new. Suppress a line with `// bughunt:ignore <rule>`.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = Path(__file__).with_name("bughunt-baseline.json")
SKIP_DIRS = {"build", ".gradle", ".kotlin", ".git", ".serena", ".android-sdk", "__pycache__", "node_modules"}
SEVERITY_ORDER = {"error": 0, "warning": 1, "note": 2}
ISSUE_MARKER = "<!-- bughunt:{rule} -->"


@dataclass(frozen=True)
class Rule:
    id: str
    severity: str
    title: str
    why: str
    pattern: re.Pattern[str]
    langs: tuple[str, ...]
    skip_tests: bool = True


@dataclass
class Finding:
    rule: str
    severity: str
    title: str
    why: str
    path: str
    line: int
    text: str

    @property
    def fingerprint(self) -> str:
        norm = re.sub(r"\s+", " ", self.text.strip())
        return hashlib.sha256(f"{self.rule}|{self.path}|{norm}".encode()).hexdigest()[:16]


def r(pattern: str) -> re.Pattern[str]:
    return re.compile(pattern)


RULES = [
    Rule("kt-global-scope", "warning", "GlobalScope launch",
         "GlobalScope coroutines outlive their owner and are never cancelled; use a lifecycle or app scope.",
         r(r"\bGlobalScope\s*\.\s*(launch|async)\b"), ("kt",)),
    Rule("kt-run-blocking", "warning", "runBlocking in app code",
         "runBlocking can ANR the main thread and deadlock single-threaded dispatchers.",
         r(r"\brunBlocking\b"), ("kt",)),
    Rule("kt-thread-sleep", "warning", "Thread.sleep in app code",
         "Blocks the thread and ignores coroutine cancellation; use delay().",
         r(r"\bThread\s*\.\s*sleep\s*\("), ("kt",)),
    Rule("kt-cleartext-http", "warning", "Cleartext http:// URL",
         "Traffic can be read or modified in transit; use https unless the target is a LAN device.",
         r(r"""["']http://(?!localhost|127\.0\.0\.1|10\.0\.2\.2|192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.|schemas\.|www\.w3\.org|ns\.adobe|purl\.org|xmlpull)[^"'\s$]+"""),
         ("kt", "py")),
    Rule("kt-trust-all-tls", "error", "TLS validation disabled",
         "A trust-all TrustManager or HostnameVerifier lets anyone on the network impersonate the server.",
         r(r"""(checkServerTrusted\s*\([^)]*\)\s*(\{\s*\}|=\s*Unit)|HostnameVerifier\s*\{\s*_\s*,\s*_\s*->\s*true|ALLOW_ALL_HOSTNAME_VERIFIER|hostnameVerifier\s*\{[^}]*true)"""),
         ("kt",)),
    Rule("kt-webview-unsafe", "warning", "Unsafe WebView setting",
         "File access or universal access from file URLs lets loaded content read local data.",
         r(r"(allowFileAccessFromFileURLs|allowUniversalAccessFromFileURLs)\s*=\s*true|setAllowUniversalAccessFromFileURLs\s*\(\s*true"),
         ("kt",)),
    Rule("kt-weak-crypto", "note", "Weak crypto primitive",
         "MD5, SHA-1, DES and ECB are broken for security use.",
         r(r"""(MessageDigest|Cipher)\s*\.\s*getInstance\s*\(\s*"(MD5|SHA-?1|DES|[A-Za-z]+/ECB[^"]*|AES)"\s*\)"""),
         ("kt",)),
    Rule("kt-hardcoded-secret", "error", "Possible hardcoded secret",
         "Credentials committed to source are exposed to everyone with repo or APK access.",
         r(r"""(?i)\b(api[_-]?key|secret|token|passw(or)?d|client[_-]?secret)\b\s*[:=]\s*"[A-Za-z0-9_\-+/=]{16,}\""""),
         ("kt", "py")),
    Rule("kt-log-secret", "warning", "Sensitive value written to log",
         "Logcat is readable by other tooling and bug reports; never log tokens or passwords.",
         r(r"""(?i)\bLog\.[divwe]\s*\([^)]*\b(token|passw(or)?d|secret|apikey|api_key)\b[^)]*\$"""),
         ("kt",)),
    Rule("kt-print-stack-trace", "note", "printStackTrace",
         "Bypasses Log and crash reporting; log with context or propagate.",
         r(r"\.printStackTrace\s*\(\s*\)"), ("kt",)),
    Rule("kt-simpledateformat-locale", "note", "SimpleDateFormat without Locale",
         "Output varies with device locale (digits, calendar), breaking parsing and API date strings.",
         r(r"""SimpleDateFormat\s*\(\s*"[^"]*"\s*\)"""), ("kt",)),
    Rule("kt-compose-collect-as-state", "note", "collectAsState instead of lifecycle-aware collection",
         "collectAsState keeps collecting while the app is stopped; prefer collectAsStateWithLifecycle.",
         r(r"\.collectAsState\s*\("), ("kt",)),
    Rule("kt-force-unwrap", "note", "Non-null assertion (!!)",
         "Throws NPE at runtime; handle null explicitly.",
         r(r"[A-Za-z0-9_\)\]]!!(?!=)"), ("kt",)),
    Rule("py-shell-true", "error", "subprocess with shell=True",
         "Shell interpolation enables command injection.",
         r(r"subprocess\.\w+\([^)]*shell\s*=\s*True"), ("py",)),
    Rule("py-verify-false", "error", "TLS verification disabled",
         "verify=False disables certificate validation.",
         r(r"verify\s*=\s*False"), ("py",)),
    Rule("py-bare-except", "warning", "Bare except",
         "Catches KeyboardInterrupt/SystemExit and hides real failures.",
         r(r"^\s*except\s*:\s*(#.*)?$"), ("py",)),
    Rule("py-request-no-timeout", "warning", "HTTP request without timeout",
         "A stalled server hangs the tool (and CI) forever.",
         r(r"requests\.(get|post|put|delete|head)\((?!.*timeout)"), ("py",)),
    Rule("py-urlopen-no-timeout", "warning", "urlopen without timeout",
         "A stalled server hangs the tool (and CI) forever.",
         r(r"urlopen\((?!.*timeout)"), ("py",)),
]


def is_test(path: Path) -> bool:
    parts = {p.lower() for p in path.parts}
    return bool(parts & {"test", "androidtest", "tests"}) or path.name.endswith(("Test.kt", "Tests.kt"))


def iter_files():
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            p = Path(dirpath) / name
            if name.endswith(".kt"):
                yield p, "kt"
            elif name.endswith(".py") and p != Path(__file__).resolve():
                yield p, "py"
            elif name == "AndroidManifest.xml":
                yield p, "manifest"


def strip_comment(line: str, lang: str) -> str:
    marker = "//" if lang == "kt" else "#"
    in_str = False
    i = 0
    while i < len(line):
        c = line[i]
        if c == '"' and (i == 0 or line[i - 1] != "\\"):
            in_str = not in_str
        elif not in_str and line.startswith(marker, i):
            if lang == "kt" and i > 0 and line[i - 1] == ":":
                i += 1
                continue
            return line[:i]
        i += 1
    return line


def scan_lines(path: Path, lang: str, rel: str) -> list[Finding]:
    out: list[Finding] = []
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return out
    testfile = is_test(path.relative_to(ROOT))
    active = [x for x in RULES if lang in x.langs and not (testfile and x.skip_tests)]
    in_block = False
    for n, raw in enumerate(lines, 1):
        stripped = raw.strip()
        if lang == "kt":
            if in_block:
                if "*/" in raw:
                    in_block = False
                continue
            if stripped.startswith("/*"):
                in_block = "*/" not in raw
                continue
            if stripped.startswith(("import ", "package ")):
                continue
        code = strip_comment(raw, lang)
        if not code.strip():
            continue
        ignore = re.search(r"bughunt:ignore\s*([\w\-, ]*)", raw)
        for rule in active:
            if ignore and (not ignore.group(1).strip() or rule.id in ignore.group(1)):
                continue
            if rule.pattern.search(code):
                out.append(Finding(rule.id, rule.severity, rule.title, rule.why, rel, n, stripped))
    if lang == "kt":
        out += scan_swallowed_cancellation(lines, rel, testfile)
        out += scan_empty_catch(lines, rel, testfile)
    return out


CATCH_RE = re.compile(r"\bcatch\s*\(\s*\w+\s*:\s*(Throwable|Exception)\s*\)\s*\{")


def catch_body(lines: list[str], start: int) -> tuple[str, int]:
    depth, body = 0, []
    for i in range(start, min(len(lines), start + 40)):
        seg = lines[i]
        if i == start:
            seg = seg[CATCH_RE.search(seg).end() - 1:]
        depth += seg.count("{") - seg.count("}")
        body.append(seg)
        if depth <= 0:
            return "\n".join(body), i
    return "\n".join(body), start


def scan_swallowed_cancellation(lines: list[str], rel: str, testfile: bool) -> list[Finding]:
    if testfile or not any("suspend" in l or "launch" in l or "async" in l or "withContext" in l for l in lines):
        return []
    out = []
    for i, line in enumerate(lines):
        if not CATCH_RE.search(strip_comment(line, "kt")) or "bughunt:ignore" in line:
            continue
        body, _ = catch_body(lines, i)
        window = "\n".join(lines[max(0, i - 12):i + 1])
        if "CancellationException" in body or "CancellationException" in window:
            continue
        if re.search(r"\bthrow\b", body):
            continue
        scope = "\n".join(lines[max(0, i - 30):i])
        if not re.search(r"\b(suspend|launch|async|withContext|delay|await|collect|emit)\b", scope + body):
            continue
        out.append(Finding("kt-swallowed-cancellation", "warning",
                           "catch (Exception) may swallow CancellationException",
                           "Catching Exception/Throwable around suspending code eats cancellation, so cancelled work keeps running; rethrow CancellationException first.",
                           rel, i + 1, lines[i].strip()))
    return out


def scan_empty_catch(lines: list[str], rel: str, testfile: bool) -> list[Finding]:
    if testfile:
        return []
    out = []
    pat = re.compile(r"\bcatch\s*\([^)]*\)\s*\{\s*(\}|//[^\n]*\}?)?\s*$")
    for i, line in enumerate(lines):
        code = strip_comment(line, "kt")
        if "bughunt:ignore" in line:
            continue
        if re.search(r"\bcatch\s*\([^)]*\)\s*\{\s*\}", code):
            out.append(Finding("kt-empty-catch", "warning", "Empty catch block",
                               "Failures vanish silently; log with context or handle them.",
                               rel, i + 1, line.strip()))
        elif pat.search(code) and i + 1 < len(lines) and lines[i + 1].strip() == "}":
            out.append(Finding("kt-empty-catch", "warning", "Empty catch block",
                               "Failures vanish silently; log with context or handle them.",
                               rel, i + 1, line.strip()))
    return out


ANDROID = "{http://schemas.android.com/apk/res/android}"


def scan_manifest(path: Path, rel: str) -> list[Finding]:
    out = []
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return out
    app = root.find("application")
    if app is None:
        return out

    def add(rule, sev, title, why, el):
        out.append(Finding(rule, sev, title, why, rel, 1, f"<{el.tag} {el.get(ANDROID + 'name', '')}>"))

    if app.get(ANDROID + "debuggable") == "true":
        add("manifest-debuggable", "error", "debuggable=true in manifest",
            "Debuggable builds expose app data and allow code injection.", app)
    if app.get(ANDROID + "allowBackup") == "true":
        add("manifest-allow-backup", "note", "allowBackup=true",
            "Settings and stored credentials can be extracted with adb backup; restrict or exclude them.", app)
    if app.get(ANDROID + "usesCleartextTraffic") == "true":
        add("manifest-cleartext", "warning", "usesCleartextTraffic=true",
            "Allows plaintext HTTP app-wide; scope exceptions with a network security config.", app)
    for el in app:
        if el.tag not in {"activity", "service", "receiver", "provider", "activity-alias"}:
            continue
        exported = el.get(ANDROID + "exported")
        has_filter = el.find("intent-filter") is not None
        perm = el.get(ANDROID + "permission")
        if el.tag in {"service", "receiver", "provider"} and exported == "true" and not perm:
            add("manifest-exported-no-permission", "warning",
                f"Exported {el.tag} without permission",
                "Any app on the device can invoke it; require a permission or set exported=false.", el)
        if el.tag == "provider" and exported == "true" and el.get(ANDROID + "grantUriPermissions") == "true":
            add("manifest-provider-grant", "warning", "Exported provider grants URI permissions",
                "Broad URI grants can leak private files.", el)
        if exported is None and has_filter and el.tag != "activity":
            add("manifest-missing-exported", "error", f"{el.tag} with intent-filter lacks android:exported",
                "Required on Android 12+; the build fails or the default is unsafe.", el)
    return out


def scan_all() -> list[Finding]:
    findings: list[Finding] = []
    for path, lang in iter_files():
        rel = path.relative_to(ROOT).as_posix()
        if lang == "manifest":
            findings += scan_manifest(path, rel)
        else:
            findings += scan_lines(path, lang, rel)
    findings.sort(key=lambda f: (SEVERITY_ORDER[f.severity], f.rule, f.path, f.line))
    return findings


def load_baseline() -> set[str]:
    if not BASELINE.exists():
        return set()
    return set(json.loads(BASELINE.read_text(encoding="utf-8")).get("fingerprints", []))


def write_sarif(findings: list[Finding], dest: Path) -> None:
    rules = {x.id: x for x in RULES}
    meta = {f.rule: f for f in findings}
    rule_defs = [{
        "id": rid,
        "name": rid,
        "shortDescription": {"text": (rules[rid].title if rid in rules else meta[rid].title)},
        "fullDescription": {"text": (rules[rid].why if rid in rules else meta[rid].why)},
        "defaultConfiguration": {"level": (rules[rid].severity if rid in rules else meta[rid].severity)},
    } for rid in sorted({f.rule for f in findings} | set(rules))]
    sarif = {
        "version": "2.1.0",
        "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
        "runs": [{
            "tool": {"driver": {"name": "bughunt", "rules": rule_defs}},
            "results": [{
                "ruleId": f.rule,
                "level": f.severity,
                "message": {"text": f"{f.title}. {f.why}"},
                "partialFingerprints": {"bughunt/v1": f.fingerprint},
                "locations": [{"physicalLocation": {
                    "artifactLocation": {"uri": f.path},
                    "region": {"startLine": f.line}}}],
            } for f in findings],
        }],
    }
    dest.write_text(json.dumps(sarif, indent=2), encoding="utf-8")


def gh_request(method: str, url: str, token: str, body: dict | None = None):
    req = urllib.request.Request(
        url, method=method, data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read() or b"null")


def file_issues(findings: list[Finding], dry_run: bool) -> int:
    token = os.environ.get("GITHUB_TOKEN", "")
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not dry_run and (not token or not repo):
        print("GITHUB_TOKEN and GITHUB_REPOSITORY are required (or pass --dry-run).", file=sys.stderr)
        return 2
    by_rule: dict[str, list[Finding]] = {}
    for f in findings:
        by_rule.setdefault(f.rule, []).append(f)
    existing: dict[str, dict] = {}
    if not dry_run:
        page = 1
        while True:
            batch = gh_request("GET", f"https://api.github.com/repos/{repo}/issues?state=all&labels=bughunt&per_page=100&page={page}", token)
            existing.update({m.group(1): i for i in batch if (m := re.search(r"<!-- bughunt:([\w\-]+) -->", i.get("body") or ""))})
            if len(batch) < 100:
                break
            page += 1
    for rule, items in by_rule.items():
        head = items[0]
        shown = items[:50]
        lines = [ISSUE_MARKER.format(rule=rule), f"**{head.title}** (`{rule}`, {head.severity})", "", head.why, "",
                 f"{len(items)} occurrence(s):", ""]
        lines += [f"- `{f.path}:{f.line}`: `{f.text[:120]}`" for f in shown]
        if len(items) > len(shown):
            lines.append(f"- ... and {len(items) - len(shown)} more")
        body = "\n".join(lines)
        title = f"[bughunt] {head.title} ({len(items)})"
        if dry_run:
            print(f"--- would file: {title}\n{body}\n")
            continue
        prior = existing.get(rule)
        if prior is None:
            gh_request("POST", f"https://api.github.com/repos/{repo}/issues", token,
                       {"title": title, "body": body, "labels": ["bughunt", head.severity]})
            print(f"filed: {title}")
        elif prior["state"] == "open":
            gh_request("PATCH", f"https://api.github.com/repos/{repo}/issues/{prior['number']}", token,
                       {"title": title, "body": body})
            print(f"updated #{prior['number']}: {title}")
    for rule, prior in existing.items():
        if rule not in by_rule and prior["state"] == "open" and not dry_run:
            gh_request("PATCH", f"https://api.github.com/repos/{repo}/issues/{prior['number']}", token,
                       {"state": "closed", "state_reason": "completed"})
            print(f"closed #{prior['number']}: no longer reproduces")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sarif", type=Path)
    ap.add_argument("--json", type=Path)
    ap.add_argument("--update-baseline", action="store_true")
    ap.add_argument("--no-baseline", action="store_true", help="report everything, ignore baseline")
    ap.add_argument("--issues", action="store_true", help="file/update/close GitHub issues, one per rule")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--min-severity", choices=list(SEVERITY_ORDER), default="note")
    ap.add_argument("--fail-on", choices=list(SEVERITY_ORDER), default="warning",
                    help="exit 1 when a new finding is at least this severe")
    args = ap.parse_args()

    all_findings = scan_all()
    if args.update_baseline:
        BASELINE.write_text(json.dumps({"fingerprints": sorted({f.fingerprint for f in all_findings})}, indent=1) + "\n",
                            encoding="utf-8")
        print(f"baseline updated: {len(all_findings)} findings accepted")
        return 0

    baseline = set() if args.no_baseline else load_baseline()
    limit = SEVERITY_ORDER[args.min_severity]
    findings = [f for f in all_findings if f.fingerprint not in baseline and SEVERITY_ORDER[f.severity] <= limit]

    if args.sarif:
        write_sarif(findings, args.sarif)
    if args.json:
        args.json.write_text(json.dumps([{**f.__dict__, "fingerprint": f.fingerprint} for f in findings], indent=2),
                             encoding="utf-8")
    for f in findings:
        print(f"{f.path}:{f.line}: {f.severity}: [{f.rule}] {f.title}\n    {f.text[:140]}")
    counts = {s: sum(1 for f in findings if f.severity == s) for s in SEVERITY_ORDER}
    print(f"\n{len(findings)} finding(s): {counts['error']} error, {counts['warning']} warning, {counts['note']} note"
          f" ({len(all_findings) - len(findings)} suppressed or below threshold)")

    if args.issues:
        rc = file_issues(findings, args.dry_run)
        if rc:
            return rc
    gate = SEVERITY_ORDER[args.fail_on]
    return 1 if any(SEVERITY_ORDER[f.severity] <= gate for f in findings) else 0


if __name__ == "__main__":
    sys.exit(main())
