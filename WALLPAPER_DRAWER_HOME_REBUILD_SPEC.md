# WALLPAPER_DRAWER_HOME_REBUILD_SPEC

**Status: IMPLEMENTED (Option D, Phases 1–3.5) on branch
`feature/wallpaper-flatten-spike`, measured on a Galaxy A36 — not yet merged.**
Started as a greenfield design; the option analysis below is kept as the record
of how the decision was reached, with the as-built + as-measured results folded in
(§9.4 correction, §9.4a cache = the actual win). Only §9.5 (classifier /
ACCEPTED_LIMITATIONS #1) remains unbuilt. Eliminates the full wallpaper rebuild on
drawer→home. Sibling of `WALLPAPER_PARALLEL_DECODE_SPEC`,
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

## 5. The RAM tradeoff

**Two different memory pools — do not conflate them** (this section originally
did; corrected in review):

- Today's layers are **HARDWARE** bitmaps (`BoundedBitmapDecoder`), so their
  pixels live in **graphics memory, off the Java heap** (`ZoomableImageView`
  :637–639 notes the GPU-side allocation may differ from `w·h·4` due to
  format/stride). So today's N layers cost ~N GPU textures in graphics memory,
  **not** Java heap.
- A HARDWARE bitmap's size is still ≈ `w · h · 4` (`ARGB_8888`-equivalent, and
  **alpha-independent** — the "transparency ≠ less RAM" fact,
  `WALLPAPER_AGGREGATE_MEM_SPEC`). At display res (1080×2340) that is ≈ **10 MB
  per layer**, so 5 layers ≈ **50 MB of graphics memory** + 5 live GPU textures.

Per option:

- **Option D (display steady state):** one HARDWARE composite ≈ **10 MB graphics
  memory + 1 texture** — below today's N-layer footprint *and* the 1-texture
  reveal is the jank win (§3). **But Approach A adds a transient cost the naive
  view misses:** the flatten re-decodes N layers as **software (`ARGB_8888`) on
  the Java heap** at commit — a one-time ~12–50 MB **heap** spike (render-res
  dependent) that must not OOM on a low-RAM device with many/huge layers. Off the
  hot path, but real.
- **Option A (cache):** if the cache holds software bitmaps it is **Java heap**
  (~12–50 MB always-resident) — a direct conflict with the aggregate-memory
  budget. If it holds HARDWARE bitmaps it is graphics memory but still N textures.
  Either way it *keeps* N, unlike D.
- **Option B:** no cache; the N layers stay in the one living view (graphics
  memory, no duplication).
- **Option C** (render res) shrinks every number above — the size dial for all
  options.

Net: D is the only option whose *steady-state* footprint drops below today
(N→1 texture), at the price of a transient heap spike during the flatten.

---

## 6. Recommendation (for discussion)

1. **Preferred: Option D** (flatten to a single display-mode bitmap). It is the
   only option that improves *every* axis at once — kills the re-decode, turns
   the N-texture reveal into a 1-texture reveal (the measured jank source), and
   *lowers* resident RAM below today's — and it unblocks
   `ACCEPTED_LIMITATIONS.md` #1 for free. Does not need Option B's navigation
   refactor. **The two review-surfaced risks (§9.2) are now retired by the
   Phase-1 spike:** the HARDWARE config is handled by a transient software
   re-decode (Approach A), and the software-vs-hardware fidelity was measured at
   mean 0.11 / max 1 (§9.7) — essentially rounding noise. Residual watch item is
   the transient flatten heap spike (§5), not fidelity.
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

### 9.2 Rendering — the HARDWARE-bitmap constraint (the critical part)

**The decode config makes the naive flatten impossible.** Wallpaper layers are
decoded to `Bitmap.Config.HARDWARE` (`BoundedBitmapDecoder:66`; ARGB_8888 only as
a fallback when a HARDWARE decode fails) — pixels live in graphics memory, off the
Java heap, drawn on the view's **hardware-accelerated** canvas. A HARDWARE bitmap
**cannot be drawn onto a software `Canvas`** and its pixels **cannot be read**
(`getPixel`/`copyPixelsToBuffer` throw).

`ZoomableImageView.composeToBitmap()` (line 811) — which allocates a software
`ARGB_8888` bitmap and composes the layers onto its `Canvas` — is therefore
**dead code precisely because it is HARDWARE-incompatible**, not merely unused.
Its own KDoc (lines 799–807) and `WALLPAPER_RENDER_RES_SPEC §6.2` say so: drawing
a HARDWARE layer onto that software canvas throws *"unable to draw hardware
bitmaps"*. **Do not treat it as a ready-made flatten routine.**

So Option D needs one of two compositing strategies:

- **Approach A — software compose (simplest).** Obtain the layers as
  `ARGB_8888` (software) *at flatten time only*: re-decode each layer's source
  with a software config at edit-commit (keeps edit-mode display HARDWARE; N
  one-time decodes off the hot path), compose on a software `Canvas`
  (`composeToBitmap`'s shape, once it is fed software bitmaps), and persist. The
  persisted composite is a single file, so **display mode decodes it back as a
  HARDWARE bitmap** — HARDWARE is preserved for the steady-state home; software is
  transient, scoped to the one-time flatten. (This is the "back to 8888" cost, but
  only for the composite step, not app-wide.)
- **Approach B — hardware-accelerated offscreen compose.** Draw the HARDWARE
  layers into a `RenderNode` via `HardwareRenderer`, then read back to a bitmap.
  Keeps everything HARDWARE and same-rasterizer, but there is **no existing
  readback pattern in the codebase** (novel; more moving parts, a GPU→CPU
  readback).

**RESOLVED — Approach A, measured across all blend modes (Phase-1 spike).**
`WallpaperFlattenParityInstrumentedTest` on a Galaxy A36 measured the software
flatten vs. the hardware render over NORMAL + all 11 `AVAILABLE_BLEND_MODES` at
0.6 alpha. Worst case **EXCLUSION: mean 0.55 / max 2** per channel (0..255); most
modes < 0.2, SCREEN and COLOR_BURN exactly 0. That is rounding noise, so
**Approach A is chosen**; the hardware-offscreen readback (B) is not needed for
correctness (the spike built it anyway, as the fallback and as reusable readback
machinery). Per-mode table: §9.7.

**Fidelity caveat (now measured).** Approach A composes in **software** while the
live view composes on a **hardware** canvas — two rasterizers whose blend / AA /
filtering math *could* differ. The earlier draft called this "pixel-identical by
construction", which was too strong; the parity test (§9.7) measures the real
delta, and it came out ~0 (mean 0.11 / max 1 above). So the tolerance is real but
tiny. Approach B would remove even that (same rasterizer end-to-end).

**Done in Phase 1:** `composeToBitmap` used to duplicate the `onDraw` layer loop
(its own `exportPaint`/`exportMatrix`) — a latent drift liability. Both now
delegate to one shared `drawLayers(canvas, paint, matrix, outputScale,
drawSelection)`, so the live and flatten paths cannot drift. (The routine is still
rasterizer-sensitive — software vs hardware canvas — which is exactly what the
parity test bounds.)

**Two-stage composition is preserved.** Blend modes compose *within* the Kolibri
layer stack against the (transparent) offscreen bitmap — exactly as they compose
against the view's own canvas today. The resulting ARGB bitmap (alpha intact) is
then composited *over the system wallpaper* at the window level in display mode —
exactly as the live layer view is today. Both stages are reproduced, so the
system-wallpaper-shows-through-transparency behavior is unchanged.

### 9.3 Flatten-on-commit + persistence

At edit-commit (`WallpaperEditController` save path): produce the composite per
§9.2 — under Approach A the in-memory layers are HARDWARE and cannot be
software-composed, so re-decode each layer's source with a software config for the
flatten (do NOT reuse the HARDWARE display bitmaps). Persist via
`WallpaperFileManager` (data/) as **lossless WEBP or PNG** (must preserve alpha —
no lossy format), and store the composite reference in the wallpaper state. The
extra decodes + offscreen render happen once per save — off the hot path,
invisible to the user.

Regenerate the composite on **every** commit (add/remove/transform/alpha/blend
change). A baked composite is robust against later source-Uri deletion — it no
longer needs the originals to display.

### 9.4 Display-mode render (reuses the existing single-image path)

Display mode renders the composite through the **existing**
`RebuildPlan.SwitchToSingleLayer` → `applySingleLayer` path
(`WallpaperViewBinder.kt:113/213`) with the composite file as the single image.
So drawer→home becomes a single-bitmap load instead of a multi-layer rebuild.

**MEASURED CORRECTION — a single decode is NOT the win (§9.4a is).** The first
draft claimed "1 decode, 1 texture upload → the jank source collapses". Measured
on the A36, that was wrong: one composite decode is **~93 ms** (a single,
non-parallelisable lossless WEBP), which is NOT faster than the 5-layer parallel
decode (~72 ms wall-clock). Rendering the composite eliminates the flash and the
multi-layer rebuild, but on its own it does not win decode time — jank stayed
~2.5/rebuild. Fewer/bigger decodes aren't automatically faster.

View-size / rotation changes are handled by the single-image center-crop/scale
that path already owns (rotation is a user setting, `rotationLockedFlow`, so do
not assume portrait); regenerating the composite on a persisted orientation
change is an optional refinement.

**Composite bitmap lifecycle** (keeps the HARDWARE constraint honest): the
display composite is loaded as a HARDWARE bitmap and is **display-only** — never
read back or re-composed. The software copy exists only transiently during the
flatten (§9.2 Approach A). Neither is ever both HARDWARE and pixel-read.

### 9.4a In-memory composite cache — the actual win (MEASURED)

Since the composite still decodes ~93 ms from disk on each drawer→home, the real
lever is to **not decode it repeatedly**: cache the ONE decoded HARDWARE composite
bitmap (~10 MB — the size §5 flagged as affordable, unlike the N-layer cache) in
an application-scoped `WallpaperCompositeCache`. It survives the fragment view, so
drawer→home re-attaches the wallpaper without decoding.

- Populated by the wallpaper bitmap loader, but **only** while rendering the
  composite (`renderingCompositeNow()` — display mode + a multi-layer state with a
  composite; that is the only state whose sole decoded bitmap is the composite, so
  it is a sound cache gate without a fragile uri-vs-path string compare).
- Invalidated when a new composite is written at commit (the path is a fixed slot,
  so a stale decode would otherwise be served). Invalidate only **drops the
  reference** — never recycles — because the composite may still be on screen; the
  view never recycles bitmaps (it relies on GC), so a cached reference is safe and
  the old bitmap is GC'd once both the view and the cache release it.

**Measured on the A36 (6 drawer→home cycles, cache warm): all `wallpaper_decode`
slices ~0.0 ms** — i.e. cache hits, the composite decoded once and reused. This is
where Option D delivers: ~0 ms vs ~72 ms (baseline) / ~93 ms (single composite).
Jank dropped to ~1.8/rebuild; the remainder is the drawer→home transition itself
(drawer-close + view re-creation + layout), no longer the wallpaper.
**Cross-checked on a Pixel 9a (Tensor G4): same ~0.0 ms cache hits, 0
App-Deadline-Missed frames** — and the contrast run before the composite existed
(multi-layer, 20 layer decodes 6–55 ms, per-layer rebuild) shows the delta.

**Single-layer wallpapers are cached too.** A genuine single-layer wallpaper has
the same "re-decode one image on every drawer→home" cost as the composite, so the
cache gate (`renderingSingleImageNow` in `HomeFragment`) covers both: DISPLAY mode
rendering a single image — composite (multi-layer + path) OR single-layer — is
cached; edit mode and multi-layer-without-composite (per-layer rebuild) are not.
Single-layer needs no explicit invalidation (a new image pick → new uri → new key
→ natural miss; a transform change keeps the same bitmap); the composite keeps its
commit-time invalidate (fixed path).

### 9.5 AUTO-mode classifier — DONE (closes ACCEPTED_LIMITATIONS #1)

`ClassifyWallpaperUseCase.pickDominantUri` now classifies the flattened composite
for a multi-layer wallpaper when one exists — the resolved composition of all
layers + blend + alpha — instead of only the bottom layer (which punted on a
transparent `layers[0]` or a non-Normal blend). It samples a downsampled SOFTWARE
decode of the composite file via `WallpaperBitmapLuminance` (256², as it already
does for single images), never `getPixel` on the HARDWARE display bitmap (§9.2);
the existing pixel-coverage gate routes a mostly-transparent composite to the
system signal (correct — the system wallpaper shows through). No composite yet
(pre-Option-D / restored / mid-backfill) keeps the bottom-layer fall-back.
`ACCEPTED_LIMITATIONS.md` #1 is updated to "largely resolved" — its own re-eval
trigger predicted this. This is the "editor grows a 'preview composite as bitmap'"
path it named as the fix.

### 9.6 Robustness / fallback

- **Composite missing — DONE (lazy backfill).** An existing multi-layer wallpaper
  with no composite (set up before Option D, restored from backup, cache cleared)
  falls back to the multi-layer `FullRebuild` and then **auto-generates** the
  composite: `WallpaperDelegate.maybeBackfillComposite`, fired from the wallpaper-
  state observer (same one-shot + edit-mode guard as the orphan GC), runs the
  flatten in the BACKGROUND — deliberately NOT at process start / in the launch hot
  path (the flatten re-decodes N layers, which would regress the cold-start path the
  launch benchmark protects), once per process, never during edit mode. So the user
  never has to re-edit + save to get a composite. Graceful degradation, no
  destructive migration.
- **Backup/restore**: the composite is derived, so backup may omit it and let the
  restored layers regenerate it on first display — store-agnostic, consistent
  with the export/reset/restore philosophy.

### 9.7 Testing

- **Instrumented pixel-parity test — BUILT (Phase 1):**
  `WallpaperFlattenParityInstrumentedTest` renders the same layer stack live
  (hardware, via `view.draw` → `RenderNode` → `HardwareRenderer`/`ImageReader`
  readback) vs. the software flatten (`composeToBitmap`) and reports the
  per-channel delta, looping NORMAL + all 11 `AVAILABLE_BLEND_MODES` at 0.6 alpha
  and asserting on the worst. Measured on a Galaxy A36 (mean / max, 0..255):
  SCREEN 0/0, COLOR_BURN 0/0, SOFT_LIGHT 0.03/1, COLOR_DODGE 0.04/1, DARKEN
  0.08/1, LIGHTEN 0.08/1, MULTIPLY 0.11/1, OVERLAY 0.14/1, DIFFERENCE 0.17/1,
  HARD_LIGHT 0.25/1, NORMAL 0.34/2, **EXCLUSION 0.55/2 (worst)**. It EARNS
  `androidTest` under Rule 10's value bar — HARDWARE bitmaps, `Canvas` blend-mode
  compositing, and true `Bitmap` semantics are real-device behavior Robolectric
  cannot reproduce (reference: darkroom `:app:halo`). The hardware-readback side
  also prototypes Approach B.
- Unit-test the plan/state transition (multi-layer state → composite-ref state)
  and the invalidation-on-commit logic on the JVM.

### 9.8 Phasing (each phase independently shippable)

1. **Extract `drawLayers(...)` + parity spike — DONE.** Shared compositor landed
   (`onDraw` + `composeToBitmap` delegate to it); parity measured (§9.7), Approach
   A chosen.
2. **Flatten-on-commit + persist — DONE.** State field + `WallpaperCompositeStore`
   + detached-view flatten at commit (`WallpaperFlattener`), instrumented-verified.
3. **Display reads the composite — DONE.** Single-image path (§9.4). Delivers the
   **flash + multi-layer-rebuild** win — but NOT decode time (see §9.4's measured
   correction).
3.5. **In-memory composite cache — DONE, the decode-time win (§9.4a).** Measured
   ~0 ms drawer→home on A36 and Pixel 9a. This is the phase that actually delivers
   latency. Extended to cover single-layer wallpapers too (§9.4a).
3.6. **Lazy composite backfill — DONE (§9.6).** Existing multi-layer wallpapers
   without a composite auto-generate one in the background on first display, off the
   launch hot path.
4. **Classifier samples the composite — DONE (§9.5).** Closes
   ACCEPTED_LIMITATIONS #1 for composited wallpapers.

**Option D is complete** (all phases shipped). A follow-on refactor also
content-versioned the composite path (`composite_<n>.webp`): a new composite ⇒ new
path ⇒ natural cache miss and a distinct value for the classifier's
`distinctUntilChanged`, which removed the fixed-path cache-invalidate coupling
(§9.4a) — the cache and classifier are now correct by key, not by invariant.

### 9.9 Risks / non-goals recap

- **HARDWARE decode config (§9.2) — surfaced in review, retired by the Phase-1
  spike.** Layers are HARDWARE bitmaps, so the flatten uses a transient software
  re-decode (Approach A, "back to 8888" for the composite step only). The feared
  fidelity cost was measured at mean 0.11 / max 1 (§9.7), so this is now a known,
  small cost — the transient heap spike (§5) is the residual thing to watch, not
  fidelity.
- Fidelity: measured ~0 under Approach A across NORMAL + all 11 blend modes
  (worst EXCLUSION 0.55 / max 2, §9.7). Was the top open risk; now closed on the
  A36 — only residual is other-device/other-GPU variance (max 2 LSB makes a blow-up
  unlikely).
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
