# Scope register — Adaptive AppDrawer & Homescreen feature train

Out-of-scope items identified while shipping the
`feat/appdrawer-adaptive-surface` branch (Commits `29f8d37` +
`6c1b08f`) and the in-flight `feat/homescreen-adaptive-text`
branch. Each was *deliberately* left out to keep the shipping
units coherent — not forgotten. This file is the deferred
backlog for the next iteration; entries graduate to a real
branch when the trigger fires or when we have appetite to bundle
them.

Distinguish from:

- `ACCEPTED_LIMITATIONS.md` — costs we *won't* fix (priced-in
  trade-offs). An item can move from here to there if a
  re-evaluation trigger pushes it the wrong way.
- `TODO.md` — project-wide backlog.
- `KNOWN_ISSUES.md` — StrictMode-only.

---

## 1. ThemingDelegate ↔ SystemWallpaperColorsSignal unification

**Status:** Deferred — refactor of working code, no functional gap.

**What's there today.** Two parallel paths poll/observe
system-wallpaper colour hints:

- `SystemWallpaperColorsSignal` (`:domain/core/`, added in
  `6c1b08f`) — process-lifetime singleton, listener-driven via
  `WallpaperManager.OnColorsChangedListener` registered in
  `KolibriLauncherApp.onCreate`, plus an initial poll. Reactive
  to system-wallpaper changes during process lifetime.
- `ThemingDelegate.wallpaperColorsFlow` (`:app/ui/main/delegate/`)
  — internal `MutableStateFlow<DomainWallpaperColors?>`, fed by
  `MainActivity.onResume()` polling
  `WallpaperManager.getWallpaperColors(FLAG_SYSTEM)` and pushing
  via `themingDelegate.updateUiColors(...)`. Reactive only on
  resume.

The signal is a strict superset of what the delegate poll
provides. The delegate could consume the signal directly and the
`MainActivity.onResume()` path could be retired.

**Why deferred.** The poll path is on the critical
`adaptive_colors`-mode pipeline (the homescreen text-colour
secondary-colour tinting). Cutting over without breaking parity
needs careful test coverage on the existing `ObserveUiColorsUseCase`
adaptive branch. Bundling it with the homescreen-classifier work
would have widened the diff and the AVD smoke-test surface.
Cleaner as its own change.

**Trigger.** Pick this up when (a) the homescreen-classifier
work has soaked in production for a release cycle without
regressions, OR (b) a third surface needs a system-wallpaper
signal and duplication starts hurting.

**Sketch.** Inject `SystemWallpaperColorsSignal` into
`ThemingDelegate`, replace `wallpaperColorsFlow` with the
signal's `colors` StateFlow, delete `updateUiColors(...)` and
the `MainActivity.onResume()` polling path. Verify
`ObserveUiColorsUseCase` adaptive_colors-mode tests still pass
unchanged.

---

## 2. Palette-based adaptive_colors for Kolibri-internal layers

**Status:** Deferred — niche mode, real complexity.

**What's there today.** `adaptive_colors` mode in
`ObserveUiColorsUseCase` reads `DomainWallpaperColors.secondaryColorArgb`
directly from the *system* wallpaper. Kolibri-internal layer
wallpapers do not influence the adaptive tint — a user with a
Kolibri-only wallpaper in adaptive_colors mode sees a tint
derived from their (now-invisible) system wallpaper.

**Why deferred.** `secondaryColorArgb` is the OS's curated
palette pick. Replicating it for Kolibri-internal images requires
palette extraction (the `androidx.palette` lib, or a custom
clustering pass over the bitmap) AND a tinting heuristic (which
swatch is the "secondary"? muted? dominant minus extremes?).
Significantly more design surface than the binary
`smart_contrast` extension. adaptive_colors is also an opt-in
niche mode — most users stay on smart_contrast — so the
cost/coverage ratio is unfavourable.

**Trigger.** A user reports the adaptive tint mismatching their
Kolibri-internal layer wallpaper *and* the manual
`SetTextColorUseCase` override is unsatisfying.

**Sketch.** Either add `androidx.palette:palette-ktx` (~50KB jar)
and pull `palette.dominantSwatch.rgb` from the same bitmap the
classifier already inspects, OR extend `BitmapLuminance` to
return both luminance and a dominant ARGB. Plumb a new
`Flow<Int?>` ("Kolibri-internal secondary colour") through
`ClassifyWallpaperUseCase` (or a sibling) into
`ObserveUiColorsUseCase`'s adaptive_colors branch.

---

## 3. `ResolvedBackground` adoption for other surfaces

**Status:** Deferred — original Commit 1 spec already noted this.

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

**Why deferred.** Each surface has its own theming history and
text-density profile (a settings list is denser than the
homescreen and may want a stronger contrast surface). Adopting
the abstraction is mechanical; choosing the right
`ResolvedBackground` shape per surface is a per-screen design
question.

**Trigger.** A user-visible inconsistency between the wallpaper-
following surfaces (homescreen, AppDrawer) and the system-Day/
Night-following surfaces (settings, onboarding) gets reported,
OR a new surface is being designed and the question of "which
backdrop should drive this" comes up naturally.

---

## 4. Translucent / blur-based AppDrawer styles

**Status:** Deferred — original spec (Commit 1) already declared
this out of scope.

**What's there today.** AppDrawer uses one of two pre-defined
*solid* surfaces (`#DD000000` dark, `#F0FFFFFF` light), picked by
classifier output. No blur, no live-wallpaper showthrough beyond
the alpha-channel transparency baked into the two surface colours.

**Why deferred.** Per the original spec's "What this commit does
NOT solve" section: "considered and deferred to a separate
feature evaluation." Blur on Android is API 31+ via
`RenderEffect.createBlurEffect` plus `Window.setBackgroundBlurRadius`
(or the older RenderScript path); it interacts with battery and
accessibility (motion-reduce settings) in ways that need their
own design pass.

**Trigger.** Explicit user request, OR a Material Design
guideline shift that makes blurred container surfaces clearly
correct for launcher app lists.

---

## 5. Composite-rendering of multi-layer wallpapers

**Status:** Already a documented limitation — see
`ACCEPTED_LIMITATIONS.md` §2. Listed here too because the
re-evaluation trigger could push it back into scope.

**What's there today.** `ClassifyWallpaperUseCase` inspects
`layers[0]` only, with an alpha-gate (≥ 0.8) and Normal-blend
gate. Multi-layer compositions where `layers[0]` is transparent
or non-Normal-blended fall through to the system signal, even
when a higher layer is opaque and visually dominant.

**Why deferred / accepted.** Real composite-rendering needs an
ARGB buffer + blend pipeline. The cost/coverage trade is
unfavourable for the common Kolibri-multi-layer shape (opaque
bottom + detail overlays).

**Trigger.** See `ACCEPTED_LIMITATIONS.md` §2 for the three
specific re-evaluation conditions.

---

## 6. Hysteresis on the luminance pathway

**Status:** Deferred — currently theatrical given the input
shape; documented in `ClassifyWallpaperUseCase` KDoc.

**What's there today.** No deadband, no `lastClassification`
persistence. Both upstreams (Kolibri-internal wallpaper state +
system colour hints) emit only on user action or system event;
no animation, no sensor, no time-of-day. Neither can flap on its
own.

**Why deferred.** Hysteresis would be cosmetic for static
inputs. Adding it speculatively means a deadband-tuning argument
without a real signal.

**Trigger.** Dynamic Kolibri-internal layer types are added
(sensor parallax, time-of-day swap, animated layers) — at that
point the bitmap-luminance pathway is no longer flap-proof and
the deadband + persisted-last-classification become motivated.

**Sketch.** Add `LIGHT_TO_DARK_THRESHOLD = 0.45f` /
`DARK_TO_LIGHT_THRESHOLD = 0.55f` in the use case. Persist last
classification under a new DataStore key. On classification
re-compute, return the persisted value when the new luminance
falls in the deadband.

---

## 7. Rename `AppDrawerSurfaceClassification` → generic name

**Status:** Deferred — minor naming correctness.

**What's there today.** The enum is named for its first consumer
(AppDrawer surface), but as of Commit 3 the homescreen
text-colour pipeline also consumes it via the same
`ClassifyWallpaperUseCase`. The name is now a half-truth.

**Why deferred.** Touches every consumer (use case return type,
delegate, ViewModel facade, fragment observer, all tests).
Bundling it with the homescreen wiring would have inflated the
diff for a name-only change.

**Trigger.** A third surface adopts the classifier (per §3 of
this file), at which point the name is unambiguously wrong and
worth a sweep. Suggested new name: `WallpaperLuminanceClassification`
or simply `LuminanceClassification`.

**Sketch.** `git grep -l AppDrawerSurfaceClassification | xargs
sed -i 's/AppDrawerSurfaceClassification/LuminanceClassification/g'`,
rename the file, run `./gradlew test checkConventions checkRule13`.

---

## 8. Empirical AVD verification — gaps in the smoke-test set

**Status:** Partial — the user's own setup was verified, the
formal reviewer test set was not exhausted.

**What's covered.** The original-bug-report scenario (white
system wallpaper + transparent grey Kolibri layer 0) verified for
the AppDrawer (Commit 2). The homescreen-adaptive-text inverse
case (white system + opaque dark Kolibri layer → expect white
homescreen text) is in the in-flight commit's test protocol.

**What's not covered.** From the reviewer's recommended test set:

- Wallpaper with high local contrast (snow + forest, building +
  sky). The median-luminance classifier *should* produce sane
  results, but the eye may judge the local contrast under text
  more harshly than the median predicts.
- System-wallpaper change at runtime (the
  `OnColorsChangedListener` should fire and re-classify within
  ~hundreds of ms; not explicitly observed).
- Multi-layer setups beyond the two trivial cases (transparent
  detail-only, fully-opaque bottom).

**Why deferred.** Empirical visual testing requires a human eye.
It is the right gate before each release AAB but is also the
right thing to do incrementally as users adopt the build.

**Trigger.** Before any push to a release tag (vs. internal test
AAB). At that point, the four-wallpaper smoke-test set should be
walked once.

---

## 9. Version bump before public release

**Status:** Pending — every test AAB so far has been built at
0.99.72 / code 92, overwriting the ACRA mapping each time.

**What's there today.** `app/build.gradle.kts` pins
`versionName = "0.99.72"` / `versionCode = 92`. Multiple test
AABs have been built and uploaded mappings to the self-hosted
ACRA server for that exact version.

**Why deferred.** During iteration, version-stable AABs let us
discard intermediate builds without polluting release tags. The
ACRA-mapping overwrites are tolerable while no downstream user
has the older obfuscated APK.

**Trigger.** Before the first AAB intended for actual
distribution (Play Store internal track, GitHub release, sideload
for a user other than the maintainer's test devices).

**Sketch.** Bump `versionCode` to 93 and `versionName` to
`0.99.73` (or the next suitable point release per the project's
versioning convention). Tag accordingly per
`CLAUDE.md` § Versioning.

**Update (commit `40b0945`):** done — bumped to 0.99.73 / 93
after the adaptive-surface train landed. Entry kept here as a
pattern reference for the next iteration cycle: re-use the
in-flight versionCode for test AABs, bump once before the next
distributable.

---

## 10. Coverage-threshold borderline — empirical anchor `huggie.png`

**Status:** Deferred — current heuristic is correct for all
verified examples; tuning is about robustness, not correctness.

**What's there today.** `WallpaperBitmapLuminanceImpl` uses
`MIN_OPAQUE_COVERAGE = 0.5f` to decide whether a Kolibri-internal
layer is visually dominant enough to drive the classification.
Below 50% effectively-opaque pixels, the classifier falls
through to the system signal.

**Empirical anchor.** During Doré-style verification on real
maintainer wallpapers, `huggie.png` (a chiaroscuro-converted
illustration) measured **48.7% coverage** — 1.3 percentage
points below the gate, sitting on the routing-strategy fence.
The two paths happened to converge for that image:

- Fall-through (current behaviour at 48.7%): system-signal
  driven — DARK if user's system wallpaper is dark/neutral.
- Layer-classification (would happen at 51%): median over the
  opaque pixels = 0.258, also below the 0.5 luminance threshold
  → DARK.

So the Doré chiaroscuro pipeline + maintainer's typical system
wallpaper produce the same perceived outcome regardless of
which route the classifier takes. The fragility is currently
invisible.

**Why deferred.** No UX symptom in real usage. A re-encoding
pass that shifts pixel coverage by a percent point would change
the routing strategy without changing the visible outcome.

**Trigger.**

- A user reports AUTO picking the wrong surface for an image
  whose coverage sits in the 40..60% borderline (where the two
  routes might disagree if the layer-classification's median is
  on the opposite side of 0.5 from the system signal).
- A wallpaper-asset pipeline change (lossless re-encoding,
  alpha-edge processing) starts moving coverage values
  systematically and reveals the routing fragility.

**Sketch.** Tune `MIN_OPAQUE_COVERAGE` upward — 0.6 or 0.7
would push `huggie`-shape images firmly into fall-through
territory. Risk: images with 50..70% coverage that today
classify correctly might lose that path. Empirical decision —
collect more borderline samples before tuning.
Alternative: add hysteresis at the coverage gate (a deadband
between e.g. 0.45 and 0.55 where the routing decision sticks
to its previous value), but that's the same hysteresis-pattern
deferred for the luminance pathway in §6 of this file — wait
until dynamic Kolibri-internal layers actually motivate it.
