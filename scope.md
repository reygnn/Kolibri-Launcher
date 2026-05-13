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

**Status:** Done with narrowed scope — see Update at the bottom of
this entry.

**What's there today (historical).** `ResolvedBackground` (sealed
interface, `:domain/model/`) was designed to be the central
abstraction for text-on-surface decisions across the whole
launcher. Before this entry shipped it drove only the AppDrawer
surface (`SolidColor` variant) and was indirectly aligned with the
homescreen via `ClassifyWallpaperUseCase`.

Surfaces that were on the original adoption list:

- **Search overlay** (in-app search, currently inside the
  AppDrawer — already covered transitively).
- **Long-press menu** (`AppContextMenuDialogFragment`) — used its
  own theme.
- **Settings activities** (`SettingsActivity`,
  `SwipeActionsActivity`, `HiddenAppsActivity`,
  `CustomNamesActivity`) — followed the system Day/Night theme.
- **Onboarding** (`OnboardingActivity`) — same.

**Why deferred originally.** Each surface has its own theming
history and text-density profile (a settings list is denser than
the homescreen and may want a stronger contrast surface).
Adopting the abstraction is mechanical; choosing the right
`ResolvedBackground` shape per surface is a per-screen design
question.

**Update (2026-05-13):** Adoption shipped for **the long-press
menu only**. Maintainer self-check ruled the Settings activities
+ Onboarding *out* of scope after walking the four screens
mentally — those are standalone task screens, system Day/Night is
the correct anchor there. The long-press menu sits on top of the
home wallpaper itself, so the disconnect to the wallpaper-
following Home + AppDrawer was real. Implementation in
`AppContextMenuDialogFragment`: inject
`ResolveWallpaperSurfaceUseCase`, observe it via
`collectOnStarted`, push the resulting `LuminanceClassification`
through the existing `app_drawer_surface_light/_dark` colours
(intentional reuse — same transparency intent), tint both the
content root and the Material3 bottom-sheet container so the
rounded top corners track the body colour, and route the WCAG-
derived foreground colour through the adapter's new
`setActionTextColor`. Search overlay was already transitively
covered. The remaining four screens stay Day/Night.

Side effect: the `AppDrawerMode` enum + flow + setting was renamed
to `WallpaperSurfaceMode` (it now drives two surfaces, will likely
drive more if the policy ever broadens). The persisted DataStore
key stayed `"app_drawer_mode"` for state-portability across the
rename.

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
