#!/usr/bin/env python3
"""Anonymize a real Kolibri backup ZIP into a public golden fixture.

The repo is public (GPL, GitHub), a real backup is not: it lists the owner's
apps, custom names and wallpaper images. This keeps everything the frozen
reader has to prove (structure, field set, overlaps between favorites /
order / hidden / swipe, per-layer transforms, blob <-> imageFileName binding,
distinct blobs) and replaces only the personal content:

  * every package name  -> com.example.appNN   (deterministic, first-seen order)
  * every activity class -> <new package>.MainActivity
  * custom-name values   -> "Name NN"
  * wallpaper blobs      -> tiny distinct grey PNGs, same entry names

Usage: anonymize_kolibri_backup.py <in.zip> <out.zip>
"""
import io, json, sys, zipfile
from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as z:
    backup = json.loads(z.read("backup.json"))
    blob_names = [n for n in z.namelist() if n.startswith("wallpapers/")]

s = backup["settings"]
pkg_map = {}

def pkg(p):
    if p not in pkg_map:
        pkg_map[p] = f"com.example.app{len(pkg_map) + 1:02d}"
    return pkg_map[p]

def comp(c):
    if c is None:
        return None
    p, _cls = c.split("/", 1)
    np = pkg(p)
    return f"{np}/{np}.MainActivity"

for key in ("favoriteComponents", "favoritesOrder", "hiddenComponents"):
    s[key] = [comp(c) for c in s.get(key, [])]
for key in ("swipeLeftApp", "swipeRightApp"):
    s[key] = comp(s.get(key))
s["customAppNames"] = {
    pkg(p): f"Name {i + 1:02d}" for i, p in enumerate(s.get("customAppNames", {}))
}

out = io.BytesIO()
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("backup.json", json.dumps(backup, indent=2, ensure_ascii=False))
    for i, name in enumerate(sorted(blob_names)):
        im = Image.new("L", (8, 16), 40 + 50 * i)  # distinct content per layer
        buf = io.BytesIO()
        im.save(buf, "PNG")
        z.writestr(name, buf.getvalue())
open(dst, "wb").write(out.getvalue())
print(f"{len(pkg_map)} packages anonymized, {len(blob_names)} blobs replaced -> {dst}")
