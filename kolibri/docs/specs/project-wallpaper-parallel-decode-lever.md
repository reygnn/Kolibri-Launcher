# project-wallpaper-parallel-decode-lever.md

Deferred optimization — parallelize the wallpaper layer decode to cut the
drawer→home pop-in; plan + caveats + re-eval trigger. This is the "*ob*"
(deferred-note) half of the pair; the "*wie*" is
`WALLPAPER_PARALLEL_DECODE_SPEC.md`, which links back here.

**Deferred optimization (2026-08-12).** On drawer→home the HomeFragment view is
destroyed and the wallpaper is fully re-decoded (no bitmap cache) → FullRebuild.
Measured on-device (Pixel 9a, 4-layer wallpaper, trace
`~/kolibri-traces/drawer-home-redraw_ba735840_2026-08-12.perfetto-trace`): the
redraw is **~65–72 ms**, decode-bound and **off-main**. The layers decode
**sequentially** in `WallpaperViewBinder.applyFullRebuild`'s
`for (spec in plan.layers) { bitmapLoader.load(); addLayer() }` loop
(`wallpaper_decode` ~15–33 ms per big layer; `add_layer`/`apply` <1 ms Main). The
view stays INVISIBLE (Option-A flicker guard) until `wallpaper_apply` reveals it,
so the wallpaper "pops in" ~70 ms after landing on Home.

**Imperceptible at current layer counts → NOT done.** Scales linearly: ~12 large
layers ≈ 250–400 ms, which WOULD be visible. Re-eval trigger: user builds
high-layer-count wallpaper collages.

**The lever = parallel decode.** Decode all layers in parallel (`coroutineScope {
plan.layers.map { async { load() } }.awaitAll() }`), then `addLayer` sequentially
in list order (main thread, z-order preserved). Cuts ~70 ms → ~max-single-layer
(~33 ms), roughly halved.

**Diff is small (one function) but NOT trivial — three caveats:**
1. **Cancellation.** `WallpaperViewBinder` is on the `checkConventions`
   `cancel_files` whitelist. The `catch (CancellationException) { throw }` must be
   INSIDE each `async` (rethrow cancellation, real errors → `null`). Latest-wins
   wallpaper switch cancels the rebuild; swallowing it files bogus ACRA reports —
   the exact bugs `e1ef671d`/`fb62b88d`/`4c09c30b`. Linter re-scrutinizes.
2. **Partial-failure skip.** The try/catch must be inside each `async` (returns
   `null` → add-phase skips that layer), NOT around `awaitAll`, or one bad layer
   aborts the whole rebuild. Pinned by `WallpaperViewBinderCancellationTest`
   ("layer whose decode throws is skipped without aborting the rebuild").
3. **Memory peak.** Parallel = N concurrent decode buffers. Fine at 4; at 12 large
   layers it's a 12× decode-memory spike (OOM risk on weak devices) — add a
   concurrency cap (semaphore / `chunked(4)`). Ironically the motivating case is
   also the riskiest.

Also add a test for parallel ordering + skip. Related:
`WALLPAPER_RENDER_RES_SPEC.md` (the same rebuild path; that was the
render-budget/jank fix, this is a separate lever on the same `applyFullRebuild`).
