#!/usr/bin/env python3
"""Derive the Kolibri golden cases from the anonymized base backup.

Single source of truth for kolibri/backup-legacy/src/test/resources/golden/kolibri-*.{zip,expected.json}:
reads kolibri-voll.zip and (re)writes every derived ZIP and every expected file,
including kolibri-voll.expected.json. Output is deterministic (fixed ZIP
timestamps, sorted JSON keys), so re-running it produces no diff.

Semantics encoded here are the NEW ones from SPEC_NYX_REWRITE (E1, E2, B11,
B13, B14). If one of those decisions changes, change it here and re-run.

Usage: derive_kolibri_cases.py [golden-dir]   (default: kolibri/backup-legacy/src/test/resources/golden)
"""
import copy, hashlib, json, os, sys, zipfile

D = sys.argv[1] if len(sys.argv) > 1 else "kolibri/backup-legacy/src/test/resources/golden"
BASE = os.path.join(D, "kolibri-voll.zip")
ZIP_TIME = (2026, 9, 29, 10, 0, 0)

SETTINGS_KEYS = [
    "textColor", "textShadowEnabled", "layoutScale", "wallpaperScrimAlpha",
    "verticalPaddingScale", "isFontBold", "contentTopMarginScale",
    "favoritesAlignment", "wallpaperSurfaceMode", "wallpaperBackdrop",
    "showCalendarEvent", "showAlarm", "autoShowKeyboard", "autoLaunchApp",
    "sortOrder", "rotationLocked",
]
# AppConstants ranges (B11 clamps to these).
RANGES = {
    "layoutScale": (0.0, 2.0),
    "wallpaperScrimAlpha": (0.0, 0.5),
    "verticalPaddingScale": (0.0, 2.0),
    "contentTopMarginScale": (0.0, 2.0),
}
ENUMS = {
    "favoritesAlignment": {"START", "CENTER", "END"},
}


def read_zip(path):
    with zipfile.ZipFile(path) as z:
        return json.loads(z.read("backup.json")), {
            n: z.read(n) for n in z.namelist() if n.startswith("wallpapers/")
        }


def write_zip(path, backup, blobs):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        def put(name, data):
            info = zipfile.ZipInfo(name, ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
        put("backup.json", json.dumps(backup, indent=2, ensure_ascii=False))
        for name in sorted(blobs):
            put(name, blobs[name])


def short(component):
    """pkg/pkg.Cls -> pkg/.Cls (the manifest short form B14 must normalize)."""
    p, c = component.split("/", 1)
    return f"{p}/{c[len(p):]}" if c.startswith(p + ".") else component


def state_of(backup, blobs, before=None):
    """Expected post-import state under the new semantics."""
    s = backup["settings"]
    before = before or {}
    settings = {}
    for k in SETTINGS_KEYS:
        v = s.get(k)
        if k in RANGES and v is not None:
            lo, hi = RANGES[k]
            v = min(max(v, lo), hi)                       # B11
        if k in ENUMS and v is not None and v not in ENUMS[k]:
            v = before.get(k, "<unchanged>")              # unknown enum: keep current
        settings[k] = v
    norm = lambda c: None if c is None else c.replace("/.", "/" + c.split("/")[0] + ".", 1)  # B14
    layers = [{
        "id": L["id"],
        "blobSha256": hashlib.sha256(blobs[L["imageFileName"]]).hexdigest(),
        "scale": L["scale"], "translateX": L["translateX"], "translateY": L["translateY"],
        "captureSampleSize": L.get("captureSampleSize"),
    } for L in s.get("wallpaperLayers", [])]
    wallpaper = {"layers": layers} if layers else "<unchanged>"       # E2
    return {
        "favorites": sorted({norm(c) for c in s["favoriteComponents"]}),
        "favoritesOrder": [norm(c) for c in s["favoritesOrder"]],
        "hiddenComponents": sorted({norm(c) for c in s["hiddenComponents"]}),  # B13
        "customAppNames": s["customAppNames"],
        "swipe": {"leftToRight": norm(s.get("swipeLeftApp")),
                  "rightToLeft": norm(s.get("swipeRightApp"))},
        "settings": settings,
        "wallpaper": wallpaper,
    }


def missing(state, not_installed):
    """E1: kept, but reported. Report = favorites + swipe slots (today's scope)."""
    refs = set(state["favorites"]) | {v for v in state["swipe"].values() if v}
    return sorted(refs & set(not_installed))


def expected(case, zip_name, backup, blobs, pins, notes, installed="every component referenced by the backup",
             before=None, importer="kolibri", result=None, not_installed=()):
    st = state_of(backup, blobs, before)
    doc = {
        "case": case,
        "backup": zip_name,
        "importer": importer,
        "pins": pins,
        "writtenBy": {"app": "kolibri", "appVersion": backup["appVersion"],
                      "format": "legacy-zip (backup.json + wallpapers/)"},
        "importOptions": "all",
        "installed": installed,
        "targetBefore": before or "<any>",
        "expected": result or {
            **st,
            "importResult": {"type": "Success",
                             "missingApps": missing(st, not_installed),
                             "droppedWallpaperLayers": 0},
        },
        "notes": notes,
    }
    with open(os.path.join(D, case + ".expected.json"), "w", encoding="utf-8") as f:
        f.write(json.dumps(doc, indent=2, ensure_ascii=False, sort_keys=False) + "\n")


base, blobs = read_zip(BASE)
s = base["settings"]

# 1 voll -----------------------------------------------------------------------
expected("kolibri-voll", "kolibri-voll.zip", base, blobs, ["round trip", "blob binding"], [
    "Flat single-layer fields (wallpaperUri, wallpaperScale, ...) are ignored.",
    "hiddenComponents is the backup's set exactly (B13).",
    "All values are in range, so B11 clamping changes nothing.",
])

# 2 fehlende Apps (E1) — same ZIP, smaller installed set ------------------------
fav, hidden = s["favoriteComponents"], s["hiddenComponents"]
fav_and_hidden = next(c for c in fav if c in hidden)
fav_only = next(c for c in fav if c not in hidden)
hidden_only = next(c for c in hidden if c not in fav)
not_installed = sorted({fav_and_hidden, fav_only, hidden_only, s["swipeLeftApp"]})
expected("kolibri-fehlende-apps", "kolibri-voll.zip", base, blobs, ["E1"], [
    "Nothing is filtered: every reference survives, including hidden and swipe.",
    "missingApps reports favorites and swipe slots only (hidden apps are not reported).",
    "Import must not wait for / fail on the installed-apps prime.",
], installed={"allReferencedExcept": not_installed}, not_installed=not_installed)

# 3 Hidden ersetzt (B13) — same ZIP, target already hides other apps ------------
before_hidden = sorted(hidden[:3] + ["com.example.app90/com.example.app90.MainActivity",
                                     "com.example.app91/com.example.app91.MainActivity"])
expected("kolibri-hidden-ersetzt", "kolibri-voll.zip", base, blobs, ["B13"], [
    "app90/app91 were hidden on the target and are NOT in the backup: they end up visible.",
    "Result equals the backup's hidden set exactly (replace, not merge).",
], before={"hiddenComponents": before_hidden})

# 4 Kurzform (B14) -------------------------------------------------------------
b = copy.deepcopy(base); t = b["settings"]
t["favoriteComponents"][0] = short(t["favoriteComponents"][0])          # short in set
i = t["favoritesOrder"].index(fav[0]); t["favoritesOrder"][i] = short(fav[0])  # and in order
t["hiddenComponents"][1] = short(t["hiddenComponents"][1])
t["hiddenComponents"][2] = short(t["hiddenComponents"][2])
t["swipeRightApp"] = short(t["swipeRightApp"])
write_zip(os.path.join(D, "kolibri-kurzform.zip"), b, blobs)
expected("kolibri-kurzform", "kolibri-kurzform.zip", b, blobs, ["B14"], [
    "Short forms (pkg/.Cls) appear in favorites, order, hidden and swipe.",
    "Every one is normalized to pkg/pkg.Cls; with E1 they must NOT be reported missing.",
    "Installed set is the full-form enumeration; expected state equals kolibri-voll.",
])

# 5 ohne Wallpaper (E2) --------------------------------------------------------
b = copy.deepcopy(base); t = b["settings"]
t["wallpaperLayers"] = []
for k in ("wallpaperUri", "wallpaperScale", "wallpaperTranslateX",
          "wallpaperTranslateY", "wallpaperImageFileName"):
    t.pop(k, None)
write_zip(os.path.join(D, "kolibri-ohne-wallpaper.zip"), b, {})
expected("kolibri-ohne-wallpaper", "kolibri-ohne-wallpaper.zip", b, {}, ["E2"], [
    "The backup has no layers and no blobs; the target keeps its current wallpaper.",
    "Nyx today would reset to NONE; the new semantics keep it (E2).",
], before={"wallpaper": "<existing non-empty state>"})

# 6 Werte außerhalb (B11) ------------------------------------------------------
b = copy.deepcopy(base); t = b["settings"]
t.update({"layoutScale": 3.5, "wallpaperScrimAlpha": 0.9,
          "verticalPaddingScale": -0.4, "contentTopMarginScale": 7.0,
          "favoritesAlignment": "DIAGONAL"})
write_zip(os.path.join(D, "kolibri-werte-ausserhalb.zip"), b, blobs)
expected("kolibri-werte-ausserhalb", "kolibri-werte-ausserhalb.zip", b, blobs, ["B11"], [
    "Floats are clamped to the AppConstants ranges.",
    "Unknown enum name keeps the target's current value (today's skip-on-unknown).",
], before={"favoritesAlignment": "CENTER"})

# The former case "kolibri-in-nyx" (an old Kolibri archive imported by Nyx) is no golden case
# any more (2b-3c): the engine refuses it by structure alone, and BackupFormatContract
# ("an archive from before the container format is refused as outdated") pins that for both
# apps without the real archive, which goes away with :kolibri:backup-legacy.

print("ok")
