# Wallpaper Bitmap Luminance test fixtures

These two PNGs are the empirical anchors for
`WallpaperBitmapLuminanceImplTest`'s real-fixture tests
(`fixture amoled-png` and `fixture transparent-png`):

- **`amoled.png`** — AMOLED-style panther illustration with a
  solid black background. 100% effectively-opaque pixels.
  Classifies DARK (median luminance ≈ 0.0).
- **`transparent.png`** — same illustration after the AMOLED
  black has been converted to `alpha=0`. 13.8% effectively-opaque
  pixels, 86.2% fully transparent. Falls through the coverage
  gate (50% threshold) and returns `null` so the classifier
  routes to the system-wallpaper signal.

## Provenance

The conversion (`amoled.png` → `transparent.png`) was produced
by [chiaroscuro](https://github.com/reygnn/chiaroscuro), the
maintainer's open-source AMOLED-black → transparent PNG
converter. This is the expected production input to Kolibri's
multi-layer wallpaper at `layers[0]` for users who want a
Kolibri-internal motif over the system wallpaper — exactly the
shape that uncovered the
`fix(classifier): pixel-level coverage gate for transparent-heavy
wallpapers` regression (commit `bbdc613`).

## Reproducibility

If these PNGs are ever re-encoded (lossless optimisation, format
upgrade, etc.), the luminance/coverage values cited in
`WallpaperBitmapLuminanceImpl`'s KDoc may shift by single-digit
percentage points. Re-run the Python analysis from `bbdc613`'s
commit message to confirm the assertion bands still hold; the
test currently asserts `< 0.05f` for `amoled.png` and `null`
for `transparent.png`, both bands chosen with a margin of
roughly 10× over the measured values.
