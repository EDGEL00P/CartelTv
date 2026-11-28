#!/usr/bin/env python3
"""Convert the generated holiday_* and surface_* PNGs in res/drawable-nodpi to WebP and remove the PNGs.

Usage: python tools/to_webp.py [res/drawable-nodpi] [--quality 80] [--max-side 1024]

Two resources with one name (x.png and x.webp) clash, so each PNG is deleted once its WebP is written.
"""
import os
import sys

from PIL import Image

PREFIXES = ("holiday_", "surface_")


def convert(directory, quality=80, max_side=1024):
    done = []
    for name in sorted(os.listdir(directory)):
        stem, ext = os.path.splitext(name)
        if ext.lower() != ".png" or not stem.startswith(PREFIXES):
            continue
        src = os.path.join(directory, name)
        with Image.open(src) as im:
            im = im.convert("RGB")
            im.thumbnail((max_side, max_side))
            im.save(os.path.join(directory, stem + ".webp"), "WEBP", quality=quality, method=6)
        os.remove(src)
        done.append(stem)
    return done


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    directory = args[0] if args else os.path.join("app", "src", "main", "res", "drawable-nodpi")
    quality = int(sys.argv[sys.argv.index("--quality") + 1]) if "--quality" in sys.argv else 80
    side = int(sys.argv[sys.argv.index("--max-side") + 1]) if "--max-side" in sys.argv else 1024
    converted = convert(directory, quality, side)
    for stem in converted:
        print(f"{stem}.webp  {os.path.getsize(os.path.join(directory, stem + '.webp')) // 1024} KB")
    print(f"{len(converted)} converted")
