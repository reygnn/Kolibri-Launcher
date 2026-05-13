# Scope register — Adaptive AppDrawer & Homescreen feature train

Out-of-scope items identified while shipping the
`feat/appdrawer-adaptive-surface` branch (Commits `29f8d37` +
`6c1b08f`) and the `feat/homescreen-adaptive-text` follow-up. Each
was *deliberately* left out to keep the shipping units coherent.
This file is the deferred backlog; entries graduate to a real
branch when the maintainer feels the UX symptom or when a
tech-trigger fires.

Distinguish from:

- `ACCEPTED_LIMITATIONS.md` — costs we *won't* fix (priced-in
  trade-offs). An item can move from here to there if a
  re-evaluation pushes it the wrong way.
- `TODO.md` — project-wide backlog.
- `KNOWN_ISSUES.md` — StrictMode-only.

**Triage history (2026-05-13).** Solo-maintainer review: rephrased
"a user reports X" triggers as "I feel X myself". Six of the
original ten entries closed: §1 (ThemingDelegate signal-unification)
+ §7 (rename to `LuminanceClassification`) shipped in `f245bfc`,
§9 (version bump pattern) shipped in `40b0945`, and §4 (blur
AppDrawer) + §6 (luminance-pathway hysteresis) + §8 (full AVD
smoke-test set before every distributable) were dropped — no
appetite, no plans for dynamic layers, on-demand-release workflow.
§10 (coverage-threshold tuning at `huggie.png`'s 48.7% fence)
moved to `TODO.md` for non-feature-train tracking. What's left is
the two items below, plus the still-accepted composite-rendering
limitation cross-linked at the end.

---

## 1. `ResolvedBackground` adoption for other surfaces

**Status:** Open — UX inconsistency between wallpaper-following and
system-theme-following surfaces is mildly bothersome to the
maintainer in daily use (2026-05-13 self-check).

**What's there today.** `ResolvedBackground` (sealed interface,
`:domain/model/`) was designed to be the central abstraction for
text-on-surface decisions across the whole launcher. Today it
drives only the AppDrawer surface (`SolidColor` variant) and is
indirectly aligned with the homescreen via `ClassifyWallpaperUseCase`.

Surfaces that should eventually adopt it:

- **Search overlay** (in-app search, currently inside the
  AppDrawer — already covered transitively).
- **Long-press menu** (`AppContextMenuDialogFragment`) — uses its
  own theme today.
- **Settings activities** (`SettingsActivity`,
  `SwipeActionsActivity`, `HiddenAppsActivity`,
  `CustomNamesActivity`, etc.) — currently follow the system
  Day/Night theme, not the wallpaper signal.
- **Onboarding** (`OnboardingActivity`) — same.

**Why deferred originally.** Each surface has its own theming
history and text-density profile (a settings list is denser than
the homescreen and may want a stronger contrast surface).
Adopting the abstraction is mechanical; choosing the right
`ResolvedBackground` shape per surface is a per-screen design
question.

**Trigger fired:** maintainer self-check 2026-05-13 — yes, the
Day/Night-following Settings screens feel disconnected from the
wallpaper-following Home + AppDrawer.

---

## 2. Palette-based `adaptive_colors` for Kolibri-internal layers

**Status:** Open — maintainer notices the adaptive tint mismatching
when only Kolibri-internal layers are visible (2026-05-13
self-check).

**What's there today.** `adaptive_colors` mode in
`ObserveUiColorsUseCase` reads `DomainWallpaperColors.secondaryColorArgb`
directly from the *system* wallpaper. Kolibri-internal layer
wallpapers do not influence the adaptive tint — a user with a
Kolibri-only wallpaper in adaptive_colors mode sees a tint
derived from their (now-invisible) system wallpaper.

**Why deferred originally.** `secondaryColorArgb` is the OS's
curated palette pick. Replicating it for Kolibri-internal images
requires palette extraction (the `androidx.palette` lib, or a
custom clustering pass over the bitmap) AND a tinting heuristic
(which swatch is the "secondary"? muted? dominant minus extremes?).
Significantly more design surface than the binary
`smart_contrast` extension. adaptive_colors is also an opt-in
niche mode — so the cost/coverage ratio looked unfavourable at
first.

**Sketch.** Either add `androidx.palette:palette-ktx` (~50KB jar)
and pull `palette.dominantSwatch.rgb` from the same bitmap the
classifier already inspects, OR extend `BitmapLuminance` to
return both luminance and a dominant ARGB. Plumb a new
`Flow<Int?>` ("Kolibri-internal secondary colour") through
`ClassifyWallpaperUseCase` (or a sibling) into
`ObserveUiColorsUseCase`'s adaptive_colors branch.

**Trigger fired:** maintainer self-check 2026-05-13 — yes, the
adaptive tint reading from the *invisible* system wallpaper is
the wrong source when Kolibri-internal layers cover everything.

---

## 3. Composite-rendering of multi-layer wallpapers (cross-link)

**Status:** Already a documented limitation — see
`ACCEPTED_LIMITATIONS.md` §2. Kept here for the empirical signal
the post-train AVD work produced.

**What's there today.** `ClassifyWallpaperUseCase` inspects
`layers[0]` only, with an alpha-gate (≥ 0.8) and Normal-blend
gate. Multi-layer compositions where `layers[0]` is transparent
or non-Normal-blended fall through to the system signal, even
when a higher layer is opaque and visually dominant.

**Empirical signal against the trigger (commit `2423ddb`+).**
Two maintainer-curated multi-layer Doré-style collages were
tested on AVD with AUTO mode and produced the correct
DARK classification (white homescreen text + dark AppDrawer
surface) — neither a tone-anchor at `layers[0]` nor a composite
renderer was needed. Reading: in the maintainer's actual collage
workflow, all routing paths through the heuristic converge on
the same perceived outcome because the Doré aesthetic is
consistently dark-dominant across whatever layer the classifier
ends up inspecting. The trigger condition ("AUTO consistently
picks the wrong surface for a multi-layer wallpaper actually
used") is not firing as of this verification. Re-test if the
maintainer's collage style diversifies (e.g., adds light
pop-art layers over dark Doré bases) where routing paths might
genuinely diverge.
