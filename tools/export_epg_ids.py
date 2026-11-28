"""Writes the epg_channel_id of every live channel your Xtream provider carries, one per line.

Usage: python tools/export_epg_ids.py HOST USER PASSWORD [out.txt]
       (or set XTREAM_HOST / XTREAM_USER / XTREAM_PASS instead of the arguments)

HOST is the server address as you type it into the app, e.g. http://example.com:8080. The credentials are
only used for the request to your own provider and are never written anywhere. Feed the output to
`clean_epg.py --only-ids`.
"""
import json
import os
import sys
import urllib.parse
import urllib.request


def main():
    args = sys.argv[1:]
    host = args[0] if len(args) > 0 else os.environ.get("XTREAM_HOST", "")
    user = args[1] if len(args) > 1 else os.environ.get("XTREAM_USER", "")
    password = args[2] if len(args) > 2 else os.environ.get("XTREAM_PASS", "")
    out = args[3] if len(args) > 3 else "provider_epg_ids.txt"
    if not (host and user and password):
        raise SystemExit(__doc__)
    if not host.startswith("http"):
        host = "http://" + host
    q = urllib.parse.urlencode({"username": user, "password": password, "action": "get_live_streams"})
    with urllib.request.urlopen(f"{host.rstrip('/')}/player_api.php?{q}", timeout=120) as r:
        streams = json.load(r)
    ids = sorted({(s.get("epg_channel_id") or "").strip() for s in streams} - {""}, key=str.lower)
    with open(out, "w", encoding="utf-8") as f:
        f.write("".join(i + "\n" for i in ids))
    print(f"{len(streams)} channels, {len(ids)} with a guide id -> {out}")


if __name__ == "__main__":
    main()
