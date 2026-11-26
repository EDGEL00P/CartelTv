"""Downloads every logo URL in channel_logos.tsv and stores it as a small WebP.

Run it on a freshly generated index (build_logo_index.py): once build_logo_pack.py has packed an entry
its URL is replaced by "#<id>" and is no longer listed.

Usage: python tools/fetch_logos.py [cache_dir] [--index urls.tsv] [--only-host HOST] [--retry-failed]
(default cache: %TEMP%/opencode/logo_cache). --only-host with --retry-failed re-tries just that host's
earlier failures, e.g. `--only-host upload.wikimedia.org --retry-failed`.

Resumable: finished files and permanent failures are skipped on the next run. Hosts are rate
limited separately (imgur tolerates many requests, Wikimedia very few).
"""
import gzip
import hashlib
import io
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
INDEX = ROOT / "app/src/main/assets/channel_logos.tsv"
SIDE = 96
QUALITY = 40
HOST_LIMITS = {"upload.wikimedia.org": 1}
DEFAULT_LIMIT = 6
HOST_DELAY = {"upload.wikimedia.org": 1.0}
MAX_ROUNDS = 6       # throttled logos are retried this many rounds before they count as failed
ROUND_PAUSE = 130    # seconds between rounds: Wikimedia asks for up to 120 s
# Wikimedia's policy asks for a descriptive agent; a browser-like one gets throttled harder.
USER_AGENT = "CartelTV-logo-builder/1.0 (personal Android TV launcher; one-off logo cache)"


def name_for(url: str) -> str:
    return hashlib.sha1(url.encode()).hexdigest() + ".webp"


def convert(raw: bytes) -> bytes:
    im = Image.open(io.BytesIO(raw))
    im = im.convert("RGBA")
    im.thumbnail((SIDE, SIDE), Image.LANCZOS)
    out = io.BytesIO()
    im.save(out, "WEBP", quality=QUALITY, method=6)
    return out.getvalue()


def main():
    args = sys.argv[1:]
    index, only_host, retry = INDEX, None, False
    if "--index" in args:
        i = args.index("--index")
        index = Path(args[i + 1])
        del args[i:i + 2]
    if "--only-host" in args:
        i = args.index("--only-host")
        only_host = args[i + 1]
        del args[i:i + 2]
    if "--retry-failed" in args:
        retry = True
        args.remove("--retry-failed")
    cache = Path(args[0]) if args else Path(os.environ.get("TEMP", ".")) / "opencode" / "logo_cache"
    cache.mkdir(parents=True, exist_ok=True)
    failed_path = cache / "failed.json"
    failed = json.loads(failed_path.read_text()) if failed_path.exists() else {}
    urls = sorted({l.rstrip("\n").split("\t")[2] for l in open(index, encoding="utf-8")})
    if only_host:
        urls = [u for u in urls if u.split("/")[2] == only_host]
    if retry:
        failed = {u: v for u, v in failed.items() if not (only_host and u.split("/")[2] == only_host)}
    todo = [u for u in urls if not (cache / name_for(u)).exists() and u not in failed]
    print(f"{len(urls)} logos, {len(urls) - len(todo) - len(failed)} done, {len(failed)} failed before, {len(todo)} to fetch", flush=True)

    sems = {}
    lock = threading.Lock()
    counter = {"ok": 0, "bad": 0}

    def sem_for(host):
        with lock:
            return sems.setdefault(host, threading.Semaphore(HOST_LIMITS.get(host, DEFAULT_LIMIT)))

    def one(u):
        """Fetches one logo. Returns 'ok', 'failed' (permanent) or 'later' (throttled: try again next round).

        A 429 is never waited out here: sleeping would hold this host's slot and stall every other logo."""
        host = u.split("/")[2]
        for attempt in range(3):
            with sem_for(host):
                try:
                    req = urllib.request.Request(u, headers={"User-Agent": USER_AGENT})
                    raw = urllib.request.urlopen(req, timeout=30).read()
                    (cache / name_for(u)).write_bytes(convert(raw))
                    counter["ok"] += 1
                    time.sleep(HOST_DELAY.get(host, 0))
                    return "ok"
                except urllib.error.HTTPError as e:
                    if e.code == 429:
                        time.sleep(HOST_DELAY.get(host, 0))
                        return "later"
                    if e.code in (403, 404, 410):
                        break
                    time.sleep(2 * (attempt + 1))
                except Exception as e:  # undecodable image, timeout, DNS
                    if isinstance(e, (OSError,)) and "cannot identify" in str(e):
                        break
                    time.sleep(2 * (attempt + 1))
        failed[u] = 1
        counter["bad"] += 1
        return "failed"

    started = time.time()
    pending = todo
    for round_no in range(1, MAX_ROUNDS + 1):
        later = []
        with ThreadPoolExecutor(24) as ex:
            for i, (u, status) in enumerate(zip(pending, ex.map(one, pending)), 1):
                if status == "later":
                    later.append(u)
                if i % 500 == 0:
                    failed_path.write_text(json.dumps(failed))
                    print(f"round {round_no}: {i}/{len(pending)} ok={counter['ok']} bad={counter['bad']} "
                          f"throttled={len(later)} {int(time.time() - started)}s", flush=True)
        failed_path.write_text(json.dumps(failed))
        print(f"round {round_no} done: ok={counter['ok']} bad={counter['bad']} throttled={len(later)}", flush=True)
        if not later:
            break
        pending = later
        time.sleep(ROUND_PAUSE)
    for u in pending if later else []:
        failed[u] = 1
        counter["bad"] += 1
    failed_path.write_text(json.dumps(failed))
    print(f"done ok={counter['ok']} bad={counter['bad']}", flush=True)


if __name__ == "__main__":
    main()
