# WALLPAPER_DRAWER_HOME_REBUILD_SPEC

**Status: GREENFIELD / DESIGN ONLY — not implemented, no decision taken.**
This is a worked-out design space for eliminating (or cheapening) the full
wallpaper rebuild that runs on every drawer→home return. It exists to capture
the measured evidence and the option/tradeoff analysis before any code is
written. Sibling of `WALLPAPER_PARALLEL_DECODE_SPEC`,
`WALLPAPER_AGGREGATE_MEM_SPEC`, `WALLPAPER_RENDER_RES_SPEC`.

---

## 1. Problem

Returning from the app drawer to the home screen rebuilds the **entire**
multi-layer wallpaper from scratch: every layer bitmap is re-decoded from its
content Uri, even though the wallpaper has not changed. With a system wallpaper
underneath a few Kolibri layers, the user sees the **system wallpaper flash
through** for the duration of the rebuild, then the Kolibri layers pop in.

### Measured evidence (Galaxy A36, SM-A366B, Snapdragon 6 Gen 3, release build)

Manual Perfetto capture, user drove drawer→home ~20×, `atrace_apps` +
`android.surfaceflinger.frametimeline`. 5-layer wallpaper.

| Metric (concurrency = 4, shipping default) | Value |
|---|---|
| Per-rebuild wall-clock (first decode → `wallpaper_apply`) | **⌀ 72 ms** (65–89) |
| Per-layer decode (`wallpaper_decode`, off-main) | ⌀ 44 ms, max 67 ms |
| Main-thread work (`wallpaper_apply` + `wallpaper_add_layer`) | **< 0.15 ms** (negligible) |
| Jank (`App Deadline Missed`) | **56 frames over 20 rebuilds ≈ 2.8 / rebuild** |

The perceived system-wallpaper flash duration equals the rebuild wall-clock
(~70–90 ms — roughly 8–11 frames at 120 Hz).

---

## 2. Root cause

`WallpaperViewBinder.bind()` computes its plan as
`WallpaperViewDiff.diff(view.snapshot(), target)`. The plan is a
`RebuildPlan.FullRebuild` whenever the view's current snapshot has no layers.

The decoded bitmaps live **only inside the `ZoomableImageView`**, which lives
**only in `fragment_home.xml`** — i.e. it is view-scoped to `HomeFragment`.
Navigating to the app drawer (a Navigation-Component destination) destroys
`HomeFragment`'s view (`onDestroyView` → `wallpaperRenderScheduler.cancel()`).
On return, `onViewCreated` re-subscribes, the new `ZoomableImageView` is empty,
so the diff of *empty → 5 layers* is always a `FullRebuild`.

`loadBitmapFromUri` (the `BitmapLoader` impl) decodes fresh from the Uri on
every call — `decodeBoundedWallpaperBitmap { contentResolver.openInputStream(uri) }`.
There is **no decoded-bitmap cache anywhere** in the wallpaper path (grep for
`LruCache`/`BitmapCache` finds only an unrelated layout cache).

So the re-decode is structural, not a bug: bitmaps are tied to a view that the
navigation lifecycle throws away.

---

## 3. Rejected lever — decode concurrency (MEASURED, A/B)

Hypothesis going in: the jank is CPU contention from 4 parallel decodes
saturating the A36's cores and starving the RenderThread. **Tested and
rejected.** Same device, same session, 20 rebuilds each, only
`DEFAULT_MAX_PARALLEL_DECODES` changed:

| | c = 4 (default) | c = 2 |
|---|---|---|
| Wall-clock / rebuild | ⌀ 72 ms | **⌀ 83 ms (+15 %)** |
| Per-layer decode | ⌀ 44 ms | ⌀ 28 ms (less contention) |
| App Deadline Missed / rebuild | 2.8 | **3.05 (no improvement)** |
| max concurrent decodes | 4 | 2 (confirmed) |

Halving concurrency made each decode faster (less inter-decode contention) but
added serialization waves → **net slower wall-clock, jank unchanged**. If the
jank were decode-CPU-bound, c = 2 would have reduced it. It did not.

**Conclusion:** the jank is almost certainly at the **reveal** — when
`wallpaper_apply` makes all 5 layers visible, the RenderThread must
upload/composite 5 large bitmaps as GPU textures in one frame, independent of
how the decode was scheduled. The lever is therefore **not** concurrency; it is
**avoiding the rebuild** or **shrinking what gets uploaded**. Concurrency stays
at 4.

---

## 4. Design options

### Option A — decoded-bitmap cache (survive the view)

Cache `DecodedWallpaperBitmap` keyed by `(uri, sampleSize)` at a scope that
outlives the view, so a post-navigation `FullRebuild` reuses cached bitmaps
instead of re-decoding. The diff still produces `FullRebuild` (empty new view),
but `BitmapLoader.load` becomes a cache hit → the ~44 ms/layer decode collapses
to a map lookup; only `add_layer`/`apply` (sub-ms) remain.

- **A1 — ViewModel-scoped cache.** Lives on `LauncherViewModel`/a wallpaper
  delegate, survives view recreation, dies with the fragment/VM. Simple; bounded
  by "current wallpaper's N layers".
- **A2 — Application-scoped `LruCache<…>` (byte-bounded).** Survives fragment
  death too, evicts under memory pressure, integrates with an explicit
  aggregate-memory budget. More robust, more moving parts.

Kills the re-decode and the flash. Does **not** by itself cheapen the GPU reveal
(§3) — but with cached bitmaps the reveal can happen ~70 ms sooner, so the flash
window closes even if the reveal frame itself is still heavy.

**Cost: retained heap** — see §5. This is the crux, and it collides head-on with
`WALLPAPER_AGGREGATE_MEM_SPEC`.

### Option B — hoist the wallpaper view above the nav host (eliminate the rebuild)

Move the `ZoomableImageView` out of `fragment_home.xml` into a host that the
drawer navigation does not destroy (e.g. an Activity-level view behind
`nav_host_fragment`, or a retained container). Then drawer→home leaves the view
(and its decoded bitmaps) intact → the diff is `Noop` → **no rebuild, no
re-decode, no flash, no reveal frame at all.**

- Best possible outcome (zero rebuild) and **no extra RAM cache** — the bitmaps
  simply stay in the living view rather than being duplicated in a cache.
- Largest change: the wallpaper currently belongs to `HomeFragment`
  (edit mode, gestures, layer picker, transform persistence all wired through
  the fragment). Hoisting the *render surface* while keeping *edit control* in
  the fragment is the hard part — needs a clean split between "display the
  wallpaper" (Activity-owned, persistent) and "edit the wallpaper"
  (fragment-owned, transient).
- Interacts with edit mode, the home/keyguard boundary, and the AUTO-mode
  surface classifier (`ACCEPTED_LIMITATIONS.md`). Feasibility to be confirmed.

### Option C — cheapen the reveal (orthogonal)

Attacks the GPU-upload cost identified in §3 rather than the decode:
- Lower render resolution / larger sampleSize per `WALLPAPER_RENDER_RES_SPEC`
  → smaller textures → cheaper upload and less cache RAM (helps A **and** the
  reveal). Tradeoff: visual sharpness.
- Progressive/staggered reveal (add layers across frames) to spread the upload,
  instead of one all-at-once `apply`.

Composable with A or B; does not on its own stop the re-decode.

### Option D — flatten layers to a single bitmap in display mode *(RECOMMENDED — detailed design in §9)*

The individual layers are only needed in **edit mode** (per-layer transform,
alpha, blend, add/remove). In **display mode** the layer stack is static — so
composite all layers into **one flattened bitmap** once (on edit-commit / save)
and let display mode draw that single bitmap. Edit mode is unchanged: it still
loads the N layers.

Why this dominates the other options:

- **Attacks the measured jank source directly (§3).** The reveal uploads **one**
  texture instead of N — exactly the per-layer GPU-composite cost the concurrency
  A/B proved is the real bottleneck. Neither A nor C removes the N-texture reveal;
  D does.
- **RAM goes DOWN vs today.** Display mode holds one ~display-res bitmap (~10 MB)
  instead of N resident layers (~50 MB). This is *better* than the status quo and
  the opposite of Option A's cost — it *helps* the aggregate-memory budget
  (`WALLPAPER_AGGREGATE_MEM_SPEC`) instead of straining it.
- **Decode drops to 1 bitmap** on drawer→home, closing the system-wallpaper flash
  window quickly; alpha is preserved, so the flattened bitmap still composites
  correctly over the underlying system wallpaper.
- **Bonus: unblocks `ACCEPTED_LIMITATIONS.md` #1.** The AUTO-mode surface
  classifier ignores layer composition today because compositing "requires
  loading every layer, applying transforms/blend/alpha in order, and sampling
  luminance from the result." The flattened bitmap **is** that composite — and
  the limitation doc already names "the wallpaper editor grows a 'preview
  composite as bitmap'" as the sanctioned path. D produces exactly that artifact,
  so the classifier could sample the real composite.

Risks / requirements:

- **Blend/alpha fidelity.** Layers carry `BlendMode` (MULTIPLY/SCREEN/OVERLAY/
  SOFT_LIGHT/HARD_LIGHT/DARKEN/LIGHTEN) + per-layer alpha. The flatten MUST
  reproduce the live multi-layer render exactly — reuse `ZoomableImageView`'s own
  layer-draw into an offscreen `Canvas`/`Bitmap`, do not re-implement the
  compositing, or display mode will diverge from edit mode. Needs a pixel-parity
  test (edit-render vs flattened bitmap).
- **Resolution baked in.** The composite is rendered at a fixed resolution with
  transforms baked; fine because display mode is static (pan/scale are edit-only,
  confirmed — no per-layer parallax/scroll exists). Pick render res per
  `WALLPAPER_RENDER_RES_SPEC`.
- **Invalidation.** Regenerate + persist the flattened bitmap on every
  edit-commit. Persist as a file so it survives process death (display mode then
  never needs the layers at all until the user re-enters edit mode).
- **Assumption to keep true:** no future display-mode per-layer effect (parallax,
  independent animation). If one is ever added, D breaks and must be revisited.

---

## 5. The RAM tradeoff (crux of Option A)

`Bitmap` is `ARGB_8888` = **4 bytes/px regardless of alpha** — a mostly-
transparent collage overlay costs the same heap as an opaque one (the standing
"transparency ≠ less RAM" fact, see `WALLPAPER_AGGREGATE_MEM_SPEC` and the
chiaroscuro-overlay use case).

Rough retained-heap estimate for an N-layer cache at render resolution R:

```
per_layer_bytes ≈ (R_width × R_height) × 4
```

- Full display res (1080×2340), 5 layers: ≈ 5 × 10.1 MB ≈ **50 MB** retained.
- Downsampled ×2 (sampleSize 2), 5 layers: ≈ 5 × 2.5 MB ≈ **12.5 MB**.

So Option A trades **~12–50 MB of always-resident heap** (depending on
`WALLPAPER_RENDER_RES_SPEC` render res and layer count) for eliminating the
~70–90 ms re-decode on every drawer→home. That is a direct, quantified conflict
with the aggregate-memory budget — **not a free win.** Option B avoids the cache
entirely (bitmaps stay in the one living view, no duplication). Option C shrinks
both the reveal and any A-cache at the cost of sharpness. **Option D inverts the
tradeoff entirely**: display mode holds one flattened bitmap (~10 MB) instead of
N layers (~50 MB), so it *reduces* resident RAM below today's baseline while also
removing the re-decode — the reason it is the recommended direction.

---

## 6. Recommendation (for discussion)

1. **Preferred: Option D** (flatten to a single display-mode bitmap). It is the
   only option that improves *every* axis at once — kills the re-decode, turns
   the N-texture reveal into a 1-texture reveal (the measured jank source), and
   *lowers* resident RAM below today's — and it unblocks
   `ACCEPTED_LIMITATIONS.md` #1 for free. Main risk is blend/alpha fidelity, which
   a pixel-parity test pins. Does not need Option B's navigation refactor.
2. **Fallback: Option B** (hoist the view) if flattening's fidelity proves
   unacceptable — removes the rebuild without a cache, at the cost of a
   display/edit-split refactor and keeping N layers resident.
3. **Option A only as a last resort** — it pays RAM to keep the layers around;
   D and B both avoid that. If used, prefer A2 (byte-bounded, evictable) + C.
4. **Do NOT** touch decode concurrency — measured net-negative (§3).

Pair the winner with **Option C** (render resolution) as a size/quality dial.

## 7. Open questions

- B feasibility: can the render surface be Activity-owned while edit mode stays
  fragment-owned, without regressing edit/gesture/keyguard behavior?
- A sizing: what is the actual render `sampleSize` in production
  (`WALLPAPER_RENDER_RES_SPEC`), i.e. where in the 12–50 MB range does the real
  cache land?
- Is the ~2.8 dropped-frames/rebuild reveal cost (§3) actually perceptible to
  the user, or is the **flash** the only real UX complaint? If only the flash,
  A/B both fix it; if the reveal jank matters, C becomes necessary.
- Does the flash even occur without an underlying *system* wallpaper (blank vs
  system-wallpaper background during the hidden window)?

## 8. Re-eval triggers

- Layer count grows well past 5 (wall-clock scales with `ceil(N/4)` decode waves).
- `WALLPAPER_RENDER_RES_SPEC` render resolution increases (raises both decode
  cost and any A-cache size).
- A future navigation change stops destroying `HomeFragment`'s view (would make
  this moot — re-measure first).

## 9. Option D — implementation design

Greenfield, still design-only. Grounded in the current code (file/line
references are the touch points, not a diff).

### 9.1 Data model

- **Edit mode** keeps the current layer list (`WallpaperState` multi-layer,
  `WallpaperLayer` with transform/alpha/`blendMode`). Unchanged.
- **Display mode** reads a single **composite** artifact (a flattened bitmap
  file + its render metadata), NOT the layer list.
- The composite is a **derived cache**, not user data: it can always be
  regenerated from the layers. This keeps it outside the backup contract and
  clear of the NO-migration policy (CLAUDE.md Rule 5) — it is not schema, it is
  a rebuildable artifact.

### 9.2 Rendering — fidelity by construction (the critical part)

**An offscreen compositor already exists.** `ZoomableImageView.composeToBitmap()`
(line 811) allocates a bitmap and composites every visible layer with its
`alpha` + `blendMode` + matrix (plus output scaling) — exactly the flatten Option
D needs. It is currently `@Suppress("unused")` **dead code**: nothing in
production, `test/`, or `androidTest/` calls it. Option D gives it a purpose.

The catch — and it is the fidelity risk made concrete: `composeToBitmap`
**duplicates** the layer-draw loop from `onDraw` (lines 922–941) with its own
`exportPaint`/`exportMatrix`. Two independent implementations of the same
compositing can (and over time will) drift, and `composeToBitmap` is untested
against `onDraw` today. So the compositing must NOT stay duplicated.

**Phase-1 work is therefore a UNIFICATION, not a from-scratch build:** collapse
`onDraw` and `composeToBitmap` onto one shared routine —

```
private fun drawLayers(canvas: Canvas, drawSelection: Boolean)
```

- `onDraw` → `drawLayers(canvas, drawSelection = isEditMode)` (live).
- `composeToBitmap` → `drawLayers(offscreenCanvas, drawSelection = false)` on an
  `ARGB_8888` bitmap at render resolution.

Same code path → **pixel-identical by construction**, and an existing latent
drift liability (two compositors) is removed as a side effect. The parity test
(§9.7) pins it.

**Two-stage composition is preserved.** Blend modes compose *within* the Kolibri
layer stack against the (transparent) offscreen bitmap — exactly as they compose
against the view's own canvas today. The resulting ARGB bitmap (alpha intact) is
then composited *over the system wallpaper* at the window level in display mode —
exactly as the live layer view is today. Both stages are reproduced, so the
system-wallpaper-shows-through-transparency behavior is unchanged.

### 9.3 Flatten-on-commit + persistence

At edit-commit (`WallpaperEditController` save path, where the layers are already
decoded in memory): call `renderComposite()`, persist via `WallpaperFileManager`
(data/) as **lossless WEBP or PNG** (must preserve alpha — no lossy format), and
store the composite reference in the wallpaper state. One extra offscreen render
per save — off the hot path, invisible to the user.

Regenerate the composite on **every** commit (add/remove/transform/alpha/blend
change). A baked composite is robust against later source-Uri deletion — it no
longer needs the originals to display.

### 9.4 Display-mode render (reuses the existing single-image path)

Display mode renders the composite through the **existing**
`RebuildPlan.SwitchToSingleLayer` → `applySingleLayer` path
(`WallpaperViewBinder.kt:113/213`) with the composite file as the single image.
So drawer→home becomes a single-bitmap load: **1 decode, 1 texture upload** — the
measured jank source (§3) collapses. View-size / rotation changes are handled by
the single-image center-crop/scale that path already owns (rotation is a user
setting, `rotationLockedFlow`, so do not assume portrait); regenerating the
composite on a persisted orientation change is an optional refinement.

### 9.5 AUTO-mode classifier (closes ACCEPTED_LIMITATIONS #1)

With a real composite available, the AppDrawer surface classifier can sample
luminance from the flattened bitmap instead of the current single-dominant-layer
heuristic. This is the "wallpaper editor grows a 'preview composite as bitmap'"
path that `ACCEPTED_LIMITATIONS.md` #1 already names as its fix. Fold in as a
follow-up once the composite exists.

### 9.6 Robustness / fallback

- **Composite missing** (first run after update; generation failed; cache
  cleared): fall back to the current multi-layer `FullRebuild` (today's exact
  behavior), then lazily generate + persist the composite once. Graceful
  degradation, never a broken wallpaper — and no destructive migration.
- **Backup/restore**: the composite is derived, so backup may omit it and let the
  restored layers regenerate it on first display — store-agnostic, consistent
  with the export/reset/restore philosophy.

### 9.7 Testing

- **Instrumented pixel-parity test** (`androidTest`): render the same layer stack
  live vs via `renderComposite()` and assert pixel equality (or a tight
  tolerance). This EARNS `androidTest` under Rule 10's value bar — `Canvas`
  blend-mode compositing and true `Bitmap` semantics are real-device behavior
  Robolectric cannot reproduce (reference: darkroom `:app:halo`). This test is
  what guards §9.2's fidelity claim.
- Unit-test the plan/state transition (multi-layer state → composite-ref state)
  and the invalidation-on-commit logic on the JVM.

### 9.8 Phasing (each phase independently shippable)

1. **Extract `drawLayers(canvas, drawSelection)`** — pure refactor, no behavior
   change, covered by the parity test.
2. **Flatten-on-commit + persist** the composite (not yet consumed).
3. **Display mode reads the composite** via the single-image path (§9.4) — this
   is the phase that delivers the latency/RAM/flash win.
4. **Classifier samples the composite** (§9.5) — closes ACCEPTED_LIMITATIONS #1.

### 9.9 Risks / non-goals recap

- Fidelity risk is contained by §9.2 (shared draw) + §9.7 (parity test).
- Resolution/quality is the dial (Option C / `WALLPAPER_RENDER_RES_SPEC`); baking
  too low softens the wallpaper, too high grows the file + decode.
- Hard assumption: **no per-layer effect in display mode** (parallax, independent
  animation). None exists today; adding one later breaks D — re-open this spec.
- **Blend modes are wired but not UI-exposed.** They are fully plumbed (domain
  `WallpaperBlendMode`, persisted `blendModeName`, backup round-trip tested),
  rendered by `onDraw`, and the setter is unit-tested (`WallpaperDelegateTest`) —
  but **no UI calls `onSetLayerBlendMode`**, so a non-normal blend only enters via
  backup import today; app-created layers are Normal. The flatten must still
  handle blend+alpha correctly (backup wallpapers, future UI), but the common case
  today is Normal-blend. The §9.5 classifier limitation is likewise latent — it
  only defers when `blendModeName != null` (`ClassifyWallpaperUseCase:119`).
  (Per-layer alpha's UI-exposure was not verified here — treat both blend and
  alpha as correctness requirements, not as "known dead".)

---

## Appendix — reproducing the measurement

```
# 60s Perfetto capture, app-custom slices + frametimeline, on a real device:
adb shell 'cat /data/local/tmp/wp_trace.pbtxt | perfetto --txt -c - \
  -o /data/misc/perfetto-traces/wp.pftrace'
# config data sources: linux.ftrace { atrace_apps:"com.github.reygnn.kolibri_launcher"
#   atrace_categories: gfx,view,wm,am,sched,freq } + android.surfaceflinger.frametimeline
```

Key trace_processor_shell queries: per-rebuild wall-clock (window over
`wallpaper_apply` minus first `wallpaper_decode` in the interval), jank
(`actual_frame_timeline_slice` grouped by `jank_type` for the kolibri process),
and max decode concurrency (self-join of overlapping `wallpaper_decode`). Slices
consumed: `wallpaper_decode`, `wallpaper_add_layer`, `wallpaper_apply`,
`drawer_open_navigate` (all in `LaunchTrace.Names`).
```
```
