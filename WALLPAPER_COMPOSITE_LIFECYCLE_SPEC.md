# WALLPAPER_COMPOSITE_LIFECYCLE_SPEC

> **Status: DESIGN (2026-08-18, v2 — in-memory).** Supersedes the disk-ownership design
> (v1 of this file), which an adversarial design review falsified — see §0. Motivated by
> three review rounds + a spike + this review all showing the on-disk composite lifecycle
> is not cleanly solvable. Decision: **delete the on-disk composite entirely; the composite
> lives only in the in-memory cache as a pure memoization of the layers.** Sibling of
> `WALLPAPER_DRAWER_HOME_REBUILD_SPEC.md`, `AUDIT-20.md`, `WALLPAPER_INMEMORY_COMPOSITE_SPIKE.md`.

---

## 0. Decision & why

**Diagnosis (unchanged):** AUDIT-20 F1–F8 are one bug class — *the persisted pointer, the
on-disk file, and the in-memory bitmap can diverge* — from one root: the composite is a
concurrently-mutated stateful **disk** resource with no single owner.

**Why not the disk-ownership design (v1):** it was adversarially reviewed to falsify its
"structurally impossible" claim, and **failed** with new HIGH-severity risks it *introduces*:

- **F7 resurrected** — a `display()` that prunes keyed on its own (possibly stale) argument
  can unlink the *current* composite; the transition table's "content-addressing prevents
  this" justification is unsound (it actually relies on a FIFO/last-writer property).
- **`dirLock` across the 227 ms flatten** — a `clear()` (backup-restore / factory-reset, the
  very cross-layer callers the lock exists for) starves for the whole flatten; the read path
  (drawer→home decode, AUTO classifier) newly serializes behind an in-flight flatten — a
  throughput regression today's design does not have.

So even the *carefully-owned* disk design carries the class forward. Combined with the spike
(the disk file is **not** the drawer→home latency win — the in-memory cache is; its only real
benefit is a 1-texture cold-start reveal), the disk file's cost/benefit is negative.

**Decision:** the composite exists ONLY in the in-memory `@Singleton` cache, as a pure
memoization of the layer set. A missing/wrong cache is always safe — re-flatten from the
source layers (the DataStore-persisted authority). This makes correctness **trivial** and
removes the entire F1–F8 class *by construction*: no disk file, no persisted pointer, no
cross-module lifecycle. **The one accepted cost is in §8.**

---

## 1. The resource & the (now trivial) invariant

Only one piece of derived state: `WallpaperCompositeCache` — one HARDWARE bitmap keyed by a
**composite key**. Source of truth = the layers in DataStore.

**THE INVARIANT (single):** the cache holds a bitmap for key `K` ⇒ `K == compositeKey(currentLayers, renderConfig)`. On mismatch → miss → (re)flatten. On clear → `invalidate()` (drop the reference; never recycle — a superseded bitmap may still be drawing). No pointer, no file, no lock spanning pixel work.

That is the whole state machine. Everything below is *performance placement*, not correctness.

---

## 2. The composite key — the ONE correctness condition (review finding, HIGH)

The composite is a pure function of **(layer set, render config)** — NOT the layer set alone.
The review's decisive finding: omit a non-layer pixel input and two visually-different
composites collide on one key → the stale one is served indefinitely.

`compositeKey` = SHA-256 over EVERY input `drawLayers` / `composeToBitmap` reads:

- **Per layer, order-sensitive:** `imageUri`, `scale`, `translateX`, `translateY`, `alpha`,
  `blendModeName`, `isVisible`, `captureSampleSize`.
- **Render config (the HIGH finding):** target **width** and **height** (display metrics) —
  omit it and a fold/unfold, external display, or resolution override serves a
  wrong-resolution composite forever. A metrics change now changes the key → natural miss →
  re-flatten at the new resolution.
- **A `RENDER_BUDGET_VERSION` prefix** — bumped when `RENDER_WALLPAPER_PIXELS` /
  `MAX_WALLPAPER_TEXTURE_SIDE` / the `S_render`/`captureSampleSize` compensation changes
  (covers the cross-update collision; no data migration, just a prefix bump).
- **`layerBackgroundColor`** — hard-wired transparent today (so safe to omit now), but named
  in the completeness contract so a future configurable background can't regress silently.

**Completeness contract (enforced):** `compositeKey` MUST cover every input the draw path
reads. Pin it with a property test that fails if a pixel-affecting field is introduced
without a corresponding key term (the seed is the spike's `WallpaperCompositeContentKeyTest`,
extended to assert width/height/budget terms). This is the single most important guard in the
design — it is what makes "derived, re-flatten on miss" actually correct.

---

## 3. Refill strategy — the "strategic place" (review-validated)

The ~227 ms flatten (software decode of N layers + compose + HARDWARE copy; **O(N)**) is paid
only on a **miss**. Misses happen at three moments, **none on the drawer→home critical path:**

1. **Cold start** — process death dropped the `@Singleton` cache.
2. **Edit-commit** — layers changed → new key.
3. **Render-config change** — rotate / unfold → new key.

**Rule: the display path NEVER blocks on a flatten.** On a cache miss it returns *fast* —
"no composite yet" — and the fragment renders **per-layer** (the existing `applyFullRebuild`
path, which already handles a composite-less multi-layer state) while an **async** flatten
warms the cache off the critical path. The next drawer→home is a cache hit (~0 ms, 1 texture,
smooth). The review derived this independently: `display()` must fast-return on a miss and kick
the flatten asynchronously — do NOT fold backfill into a synchronous display call.

**Triggers for the async warm:** after the first home render post-cold-start; after
edit-commit; on a render-config change. A **single-flight guard** (`backfillInProgress`, which
already exists) prevents a backup restore's intermediate emissions from storm-flattening.

---

## 4. Transition table (short — because there is no disk)

| Event | Action | Invariant |
|---|---|---|
| **drawer→home** | cache hit → composite; miss → per-layer render + async warm | trivially held |
| **cold-start first render** | per-layer render; async warm after home is visible | held |
| **edit-commit** | key changes → per-layer until warm; async warm | held |
| **layer mutation mid-edit** | editor renders the real layers (unchanged today) | held |
| **clear / factory reset** | `cache.invalidate()` — drop the reference (never recycle) | held |
| **backup restore** | restored layers drive display; single-flight async warm | held |
| **rotate / display change** | key changes → miss → per-layer + async warm | held |

No file operation, no persisted pointer, no cross-layer lock. `clear()` is dropping a
reference. There is no interleaving that can leave a dangling pointer or an orphan file,
because neither exists.

---

## 5. The AUTO classifier (the in-memory wrinkle — review-flagged)

Today the AUTO light/dark classifier (`WallpaperBitmapLuminanceImpl`) decodes a *separate*
downsampled bitmap from the composite **file** (HARDWARE bitmaps can't be pixel-read). With no
file, it needs another source. **Design:** the flatten produces `(hardwareBitmap, luminance)`
together — luminance is sampled from the SOFTWARE `ARGB_8888` composite *during* the flatten,
before the HARDWARE copy, when the pixels are still readable. The cache stores the luminance
alongside the composite key; the classifier reads the cached luminance keyed by `compositeKey`.
This removes the classifier's file dependency and keeps its result perfectly coherent with the
displayed composite (same key). On a miss the classifier, like the display, falls back to its
current per-layer-unavailable behavior until the warm lands (`ACCEPTED_LIMITATIONS.md #1`
already documents the AUTO classifier's best-effort nature).

---

## 6. What this deletes / keeps

**Deletes:** `WallpaperCompositeStore` (file/write/prune/delete/clear/`dirLock`),
`flattenedWallpaperPath` in DataStore (retain the `KEY_WALLPAPER_FLATTENED_PATH` literal in
`removeAllKeys()` for one release as a one-shot orphan cleanup, then drop — review low finding),
`validatedCompositePath`, the composite-dir clear wiring in `clearWallpaper`/`purgeRepository`,
the `wallpaper_composite/` dir. **The AUDIT-20 F1–F8 class is gone by construction.**

**Keeps (smaller):** `WallpaperCompositeCache` (gains the `compositeKey` + the luminance
entry), `WallpaperFlattener` (gains the luminance output + a HARDWARE-copy step; the disk
write is removed), `maybeBackfillComposite` becomes the async warm.

Net: **less code than today**, and the delicate concurrent disk lifecycle is deleted, not
re-owned.

---

## 7. Verify plan (bounded — and now tiny)

1. **`compositeKey` completeness** — property test over every pixel input, with the guard that
   fails on a new un-hashed pixel field (§2). *The* critical test.
2. **Cache memoization** — hit / miss / `invalidate` (extends `WallpaperCompositeCacheTest`).
3. **Fast-return on miss** — a miss returns the per-layer signal and fires the async warm
   exactly once (single-flight).
4. **Cancellation cleanliness** — a cancelled flatten recycles the software bitmap, no leak
   (the one `UNCERTAIN` review finding worth pinning).
5. **One** adversarial review against §1's invariant — a much smaller surface than the disk
   design (no interleaving space to enumerate).

---

## 8. The one accepted cost (state it plainly)

The **cold-start wallpaper reveal** reverts from a 1-texture disk-composite decode (~90 ms,
smooth) to a **per-layer render** (N-texture reveal — the brief system-wallpaper flash,
~2.8 dropped frames), paid **once per process life**, amid other cold-start cost. This was the
disk file's *only* real benefit (the spike proved it is not the drawer→home win). The
deliberate trade: **a brief cold-start flash in exchange for deleting the entire disk
lifecycle and its recurring bug class.** drawer→home — the frequent, latency-sensitive
interaction — stays ~0 ms via the warmed cache, unchanged.
