"""Packs the downloaded logos into app/src/main/assets/channel_logos.pack and points the index at them.

Usage: python tools/build_logo_pack.py [cache_dir] [--side 72] [--quality 35] [--alpha-quality 50]

Each cached logo is re-encoded to at most --side px (a 46 dp logo is about 60-90 px on a TV, so 72 is not
upscaled). The index must still hold URLs: packing rewrites it, and a packed index cannot be packed again.

Pack layout (big endian): "CLP1", u32 count, u32 offsets[count + 1], then the WebP blobs. Offsets
are relative to the start of the blob area. The index value becomes "#<n>" for a packed logo
(identical images share one blob) and stays the original URL when the download failed.
"""
import hashlib
import io
import os
import struct
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"
INDEX = ASSETS / "channel_logos.tsv"
PACK = ASSETS / "channel_logos.pack"


def reencode(data, side, quality, alpha_quality):
    """Shrinks a cached WebP to at most `side` px; keeps the original if the result is not smaller."""
    im = Image.open(io.BytesIO(data)).convert("RGBA")
    if max(im.size) > side:
        im.thumbnail((side, side), Image.LANCZOS)
    out = io.BytesIO()
    im.save(out, "WEBP", quality=quality, alpha_quality=alpha_quality, method=6)
    return out.getvalue() if len(out.getvalue()) < len(data) else data


def main():
    args = sys.argv[1:]
    opts = {"--side": 72, "--quality": 35, "--alpha-quality": 50}
    for name in opts:
        if name in args:
            i = args.index(name)
            opts[name] = int(args[i + 1])
            del args[i:i + 2]
    cache = Path(args[0]) if args else Path(os.environ.get("TEMP", ".")) / "opencode" / "logo_cache"
    rows = [l.rstrip("\n").split("\t") for l in open(INDEX, encoding="utf-8")]
    if any(r[2].startswith("#") for r in rows):
        raise SystemExit("the index is already packed (values start with '#'); regenerate it with "
                         "build_logo_index.py, or restore a URL index, before packing again")
    blobs, by_hash, slot_for_url = [], {}, {}
    for _, _, value in rows:
        if value.startswith("#") or value in slot_for_url:
            continue
        f = cache / (hashlib.sha1(value.encode()).hexdigest() + ".webp")
        if not f.exists():
            slot_for_url[value] = None
            continue
        data = reencode(f.read_bytes(), opts["--side"], opts["--quality"], opts["--alpha-quality"])
        h = hashlib.sha1(data).digest()
        if h not in by_hash:
            by_hash[h] = len(blobs)
            blobs.append(data)
        slot_for_url[value] = by_hash[h]
    with open(PACK, "wb") as out:
        out.write(b"CLP1" + struct.pack(">I", len(blobs)))
        pos = 0
        for b in blobs:
            out.write(struct.pack(">I", pos))
            pos += len(b)
        out.write(struct.pack(">I", pos))
        for b in blobs:
            out.write(b)
    with open(INDEX, "w", encoding="utf-8", newline="\n") as f:
        for key, country, value in rows:
            slot = slot_for_url.get(value)
            f.write(f"{key}\t{country}\t{'#%d' % slot if slot is not None else value}\n")
    packed = sum(1 for v in slot_for_url.values() if v is not None)
    print(f"{len(blobs)} unique blobs, {packed} urls packed, {len(slot_for_url) - packed} left as urls, "
          f"pack={PACK.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
