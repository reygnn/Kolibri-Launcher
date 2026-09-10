# Wallpaper Bitmap Luminance test fixtures

These PNGs are the empirical anchors for
`WallpaperBitmapLuminanceImplTest`'s real-fixture tests
(`fixture amoled-png`, `fixture transparent-png`, and
`fixture checkerboard-diagonal-png`):

- **`amoled.png`** — AMOLED-style panther illustration with a
  solid black background. 100% effectively-opaque pixels.
  Classifies DARK (median luminance ≈ 0.0).
- **`transparent.png`** — same illustration after the AMOLED
  black has been converted to `alpha=0`. 13.8% effectively-opaque
  pixels, 86.2% fully transparent. Falls through the coverage
  gate (50% threshold) and returns `null` so the classifier
  routes to the system-wallpaper signal.
- **`checkerboard_diagonal.png`** — device-sized (1080×2424)
  diagonal black/white checkerboard, the on-device readability
  stress wallpaper. 100% opaque, so the coverage gate passes and
  a classification is always produced. The high-frequency 50/50
  pattern is the adversarial near-`0.5`-threshold case: the 32×32
  bilinear downscale averages each cell to mid-gray, so the WCAG
  median collapses to ≈ 0.21 — below the LIGHT threshold, far
  above the AMOLED floor. The test pins that magnitude band, NOT
  the LIGHT/DARK side (a 50/50 median sits on the knife-edge and
  the side is not a stable contract). This fixture documents the
  AUTO limitation that the glyph-level text-outline (0.99.193)
  exists to cover — neither a single text colour nor a scrim wins
  on this wallpaper.

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

`checkerboard_diagonal.png` is a generated diagonal black/white
checkerboard sized to the Pixel 9a panel (1080×2424), used as the
on-device readability stress wallpaper while validating the
text-outline change (0.99.193) and pulled straight from the device
into this corpus.

## Reproducibility

If these PNGs are ever re-encoded (lossless optimisation, format
upgrade, etc.), the luminance/coverage values cited in
`WallpaperBitmapLuminanceImpl`'s KDoc may shift by single-digit
percentage points. Re-run the Python analysis from `bbdc613`'s
commit message to confirm the assertion bands still hold; the
test currently asserts `< 0.05f` for `amoled.png` and `null`
for `transparent.png`, both bands chosen with a margin of
roughly 10× over the measured values.
