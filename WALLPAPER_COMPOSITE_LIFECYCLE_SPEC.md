# WALLPAPER_COMPOSITE_LIFECYCLE_SPEC

> **Status: DESIGN (2026-08-18, v4.2 — in-memory memo, classifier re-shelved).** Supersedes the
> disk designs (v1 disk-ownership, v3.x immutable disk tier) and the earlier in-memory sketch
> (v2). Reached after three AUDIT-20 review rounds + three multi-agent design reviews, which showed
> the remaining hard part was never the cache — it was two concerns tangled into the word "cache"
> that don't belong to it. v4 removes them. Sibling of `WALLPAPER_DRAWER_HOME_REBUILD_SPEC.md`,
> `AUDIT-20.md`, `ACCEPTED_LIMITATIONS.md #1`.
>
> **v4.1** made the software→HARDWARE bitmap copy explicit (the disk round-trip used to hide it) and
> named the second cache writer + the new warm predicate. **v4.2** folds in the third multi-agent
> review (R1–R3): it adds **§3a, the read path** — the review's decisive catch that v4 specified the
> *write* side exhaustively but left undefined how a warmed composite actually reaches the screen
> once `flattenedWallpaperPath` (which was signal + key + decodable URI in one) is deleted; resolved
> with a `composite://<compositeKey>` synthetic cache URI that reuses the whole single-image render
> path, one pinned metric source, a render-time warm trigger that also covers rotate/fold (R2), and
> the sibling-doc updates (R3).

---

## 0. Decision & why

**The cache was never the hard part.** The *read* path (decode once, hold the bitmap, reuse it
across drawer→home) came back clean in every AUDIT-20 round. Single-layer caching is trivial and
already works. All the churn (F1–F9 + two design reviews) came from **three separable concerns
the word "cache" hid**, only two of which are actually hard, and neither of which is caching:

1. **It is a memo of an expensive compute, not a cache of an image.** A multi-layer wallpaper has
   no "image" until you flatten N layers (decode + blend/alpha/transform, ~227 ms). Correctness
   therefore lives entirely in the **cache key** (§2), not in holding the bitmap.
2. **Persisting that compute across process death added a lifecycle.** A disk file + a "which file
   is current" pointer = three things that can diverge = the entire F1–F9 class. Self-inflicted by
   one optimization: the smooth 1-texture cold-start reveal.
3. **A second consumer behind a module wall.** The AUTO light/dark classifier (`:domain`) wants to
   sample the composite's pixels, produced in `:app`, and must be re-triggered when it lands — a
   cross-module + re-trigger seam that has nothing to do with caching and kept re-appearing (it
   sank the v3.3 fix: the delegate emitted onto a flow the `:domain` classifier never observes).

**Decision:** pay for **neither** hard concern.

- **Drop the disk tier entirely** (concern 2). The composite lives **only** in the in-memory
  `@Singleton` cache, as a pure memoization of the layer set. Missing/wrong cache is always safe —
  re-flatten from the DataStore-persisted layers. The F1–F9 class is gone *by construction*.
- **Re-shelve composite AUTO-classification** (concern 3). The classifier reverts to the
  `layers[0]` bottom-layer heuristic it used before commit `9ce46d8d`; `ACCEPTED_LIMITATIONS.md #1`
  is re-opened. No cross-module signal, no derived key crossing into `:domain`.

What remains is exactly what caching *is*: **compute once, hold in RAM, drop on key mismatch.** No
disk file, no persisted pointer, no lock, no module signal. The two accepted costs are in §8 —
both are already-documented, already-lived-with limitations.

---

## 1. The resource & the (trivial) invariant

One piece of derived state, entirely inside `:app`:

**`WallpaperCompositeCache`** — one decoded HARDWARE bitmap + its key. Source of truth = the
layers in DataStore. The key has two shapes:

| Wallpaper | Cache key | Why |
|---|---|---|
| **single-layer** | `imageUri` | the decoded bitmap is transform-independent (the view applies scale/translate at draw time); a new pick → new URI → new key |
| **multi-layer** | `compositeKey(layers, renderConfig)` (§2) | the flatten *bakes in* every layer transform/blend/alpha + the target resolution, so all of it must be in the key |

**THE INVARIANT (single):** the cache holds a bitmap for key `K` ⇒ `K` equals the key of the
current wallpaper. On mismatch → miss → re-decode (single) / re-flatten (multi). On clear →
`invalidate()` (drop the reference; **never recycle** — a superseded bitmap may still be drawing).

That is the whole state machine. `compositeKey` may be a pure function (it hashes `WallpaperState`
fields + two `Int` metrics — it could even live in `:domain`), but its **result string never
crosses a module boundary**: it is only ever computed and consumed in `:app`, because the one
consumer that would have forced it into `:domain` — the classifier — no longer samples the
composite (§5). That is the simplification that removes concern 3's seam. Everything below is
*performance placement* and the *key*, not a lifecycle — there is no file, no pointer, no lock.

---

## 2. The composite key — the ONE correctness condition (multi-layer only)

The multi-layer composite is a pure function of **(layer set, render config)** — NOT the layer set
alone. Omit a non-layer pixel input and two visually-different composites collide on one key → the
stale one is served from RAM until the key otherwise changes. `compositeKey` = a stable hash over
EVERY input the flatten (`drawLayers` / `composeToBitmap`) reads:

- **Per layer, order-sensitive:** `imageUri`, `scale`, `translateX`, `translateY`, `alpha`,
  `blendModeName`, `isVisible`, `captureSampleSize`.
- **Render config:** target **width** and **height** (display metrics) — omit it and a
  rotate/fold/external-display serves a wrong-resolution composite. A metrics change now changes
  the key → natural miss → re-flatten at the new resolution.
- **A `RENDER_BUDGET_VERSION` prefix** — bumped when `RENDER_WALLPAPER_PIXELS` /
  `MAX_WALLPAPER_TEXTURE_SIDE` / the `captureSampleSize` compensation changes.
- **`layerBackgroundColor`** — transparent today (safe to omit now), but named in the contract so a
  future configurable background can't regress silently.

**Completeness contract (enforced):** `compositeKey` MUST cover every input the draw path reads.
Pin it with a property test that fails if a pixel-affecting field is added without a corresponding
key term (seed: the spike's `WallpaperCompositeContentKeyTest`, extended to assert the
width/height/budget terms). This is *the* correctness guard — it is what makes the memo correct.
(Cheaper to get right now than before: the key is a pure `:app`-local value; it never has to be
resolvable to a file or reproducible in `:domain`.)

---

## 3. Display & refill — the memo, and the "never block" rule

The ~227 ms flatten (software decode of N layers + compose + HARDWARE copy; **O(N)**) is paid only
on a **miss**. Misses happen at: cold start (process death dropped the `@Singleton` cache),
edit-commit (layers changed → new key), render-config change (rotate/fold → new key).

**Rule: the display path NEVER blocks on a flatten.** On a multi-layer miss it returns *fast* — "no
composite yet" — and the fragment renders **per-layer** (the existing `applyFullRebuild` path, which
already handles a composite-less multi-layer state) while an **async warm** flattens off the
critical path and populates the cache. The next drawer→home is a hit (~0 ms, one texture, smooth).
Single-layer needs no flatten — decode the source image and cache it.

### 3a. The read path — how a warmed composite reaches the screen (review R1)

*This is the point of the cache and it must be specified, not just the write side.* Today the
composite renders as one texture by a trick: `displayTargetFor` (`HomeFragment.kt:1526`) rewrites a
multi-layer state into a fake **single-layer** state whose `imageUri` **is** the composite file path
(`WallpaperState(imageUri = compositePath)`), so it flows through the existing
`SwitchToSingleLayer(Uri)` → `applySingleLayer` → `loadBitmapFromUri` machinery, cached by the path
string. `flattenedWallpaperPath` is playing three roles at once: the *composite-exists signal*, the
*cache key*, and a *decodable `Uri`*. Deleting it (§6) breaks all three — the render gates won't
even compile, and the naive `imageUri = compositeKey` makes `loadBitmapFromUri` call
`openInputStream(hash)` → null → a **blank** wallpaper.

**Replacement — a synthetic cache URI, reusing the whole single-image path:** address the cached
composite by an opaque `composite://<compositeKey>` string.

- **Warm-write** puts the HARDWARE composite under key `composite://<K>` (K = §2 `compositeKey`).
- **Render-read:** `renderingCompositeNow()` becomes key-based — *multi-layer, display mode, and
  `compositeCache.get("composite://" + K) != null`* — instead of `flattenedWallpaperPath != null`.
  `displayTargetFor` returns `WallpaperState(imageUri = "composite://" + K)` on a hit; the plan is
  `SwitchToSingleLayer("composite://…".toUri())` exactly as today.
- **`loadBitmapFromUri` gains a scheme branch:** a `composite://` URI is resolved from the cache
  **only** (`compositeCache.get(uri)`), **never** `openInputStream`'d. It already short-circuits on a
  cache hit (`HomeFragment.kt:1581`); the scheme guard makes a *miss* return `null` (→ the binder
  skips → the fragment is on the per-layer path anyway) rather than trying to decode a hash.
- **Single-layer is unchanged:** a real `file://` URI, decoded and cached as today. The two
  namespaces (`file://` vs `composite://`) cannot collide, which also settles the single-entry-cache
  key-collision worry.

**Pin ONE metric source (review R1 sub-risk, a §2 completeness concern).** `compositeKey`'s
width/height MUST come from the **same** call on both the warm-write side (today
`context.resources.displayMetrics`, `WallpaperDelegate:645`) and the render-read side — any
divergence (window insets vs. raw `displayMetrics`, view size vs. display size) yields two different
keys → permanent miss → the warm re-flattens forever and the hit never lands. Name it once:
**`context.resources.displayMetrics.{widthPixels,heightPixels}`**, used verbatim on both sides.

### 3b. Async warm sequence

`flatten (SOFTWARE) → (only if COMPLETE) copy to HARDWARE → key-gated put → recycle the software
temp`.

- **The software→HARDWARE copy (do not skip — today the disk hid it).** `composeToBitmap` produces
  a **SOFTWARE `ARGB_8888`** composite (it composes on a software `Canvas`); the cache holds a
  **HARDWARE** bitmap (never CPU-read after upload, the ~10 MB display invariant). Today that
  transition happens *implicitly* via the disk round-trip — the flatten wrote a software webp and
  `HomeFragment` decoded the **file** back to HARDWARE (the only cache writer today,
  `HomeFragment.kt:1596`). With no file, the warm must do the copy itself:
  `softwareComposite.copy(Bitmap.Config.HARDWARE, /*mutable=*/false)`, cache the HARDWARE result,
  and **recycle the software temp** (it is never cached or drawn — this is the one bitmap the design
  recycles, mirroring `WallpaperDelegate:641` today). The never-recycle invariant is untouched: the
  HARDWARE copy that gets cached/drawn is never recycled; only the local software source is.
- **Two cache writers now (was one).** Deleting the disk splits population by wallpaper kind:
  **single-layer** stays `HomeFragment`-populated (decode the source URI → HARDWARE → put, as
  today); **multi-layer** moves to the **delegate warm** (flatten → HARDWARE copy → put). Both go
  through the same `WallpaperCompositeCache` and the same key-gated put. The delegate reaching the
  `@Singleton` cache is fine (both `:app`).
- **All-or-nothing put.** `WallpaperFlattener.loadSoftware` returns `null` per layer on any
  `Throwable`; the binder skips it and `composeToBitmap` returns a **non-null partial composite**.
  The warm must therefore only proceed to the HARDWARE copy + `put` when the flatten was
  **complete — every *visible* layer decoded** (an `isVisible=false` layer that fails to decode
  contributes no pixels and must not block the put). A partial flatten is discarded (nothing
  cached); the display keeps rendering per-layer (which self-heals each frame) and re-flattens on
  the next miss. The flattener reports completeness via its own `loadSoftware` lambda (it owns the
  loader, so it observes the null returns) — a local signal, not a change to the shared live-path
  binder.
- **Key-gated put.** `put(K, bmp)` is a no-op unless `K` equals the current wallpaper's key at put
  time. Bounded memory hygiene, not a correctness gate: reads are always key-matched (a stale entry
  can never be *served*) and the cache is single-entry (the next real put overwrites it) — the gate's
  only real job is the **clear-to-NONE** case, where no next display arrives to overwrite a
  late-completing warm's ~10 MB bitmap.

**Warm trigger — state change ∪ render-config change (review R2).** `maybeBackfillComposite`'s
predicate changes from `state.flattenedWallpaperPath == null` (a deleted field) to **a cache miss for
the current `compositeKey`** (`compositeCache.get("composite://" + K) == null`) on a multi-layer,
non-edit state. But the *trigger set* must widen too: today the warm fires only from the DataStore
state collector (`WallpaperDelegate:400`), and a **rotate/fold/multi-window resize emits no new
`WallpaperState`** (the layers are unchanged, and `MainActivity` has `configChanges` so it is not
recreated — `onConfigurationChanged` currently only re-spaces). Since §2 folds width/height into `K`,
such a resize flips `K` → a guaranteed miss that the state-only trigger never services → drawer→home
renders per-layer for the *entire new orientation*. So the warm must also fire on a **render-config
change**: `onConfigurationChanged` (or a display-metrics observer) re-evaluates the current
multi-layer state at the new metrics and requests a warm on the miss. Cleanest framing: **the
render-read (§3a) IS the trigger** — whenever the fragment renders a multi-layer state and finds a
`compositeKey` cache miss, it renders per-layer *and* requests the warm; because both a state
emission and a config change cause a render, this one rule covers cold-start, edit-commit, clear, and
rotate/fold uniformly. Same single-flight, same off-main dispatch.

**Single-flight.** At most one flatten in flight (`backfillInProgress`, already exists) so a
backup-restore's intermediate emissions can't storm. On completion, re-check the current key; if it
changed during the flatten and is still a composite-less multi-layer miss, warm once more (chase the
current key rather than strand the final one).

---

## 4. Transition table (short — there is no disk and no signal)

| Event | Action | Invariant |
|---|---|---|
| **drawer→home (single-layer)** | cache hit (`file://` key) → image; miss → decode source + key-gated put | held |
| **drawer→home (multi-layer)** | cache hit (`composite://K` key, §3a) → composite; miss → per-layer render + request warm | held |
| **cold-start first render** | cache empty (process died) → per-layer (multi) / decode (single); render requests async warm after home is visible | held |
| **edit-commit** | key changes → miss → per-layer until warm; async warm (complete-only put) | held |
| **layer mutation mid-edit** | editor renders the real layers (unchanged today); no cache op | held |
| **clear / factory reset** | `invalidate()` — drop the reference (never recycle) | held |
| **backup restore** | restored layers drive display; single-flight async warm | held |
| **rotate / fold / display change** | key changes (width/height, §2) → miss → per-layer + warm at new resolution, **fired by `onConfigurationChanged` re-render** (§3a/R2), not a DataStore emission | held |

No file operation, no persisted pointer, no cross-module lock or signal. `clear()` is dropping a
reference. There is no interleaving that can strand a pointer or orphan a file, because neither
exists.

---

## 5. The AUTO classifier — re-shelved (concern 3, deleted)

`ClassifyWallpaperUseCase` (`:domain`) reverts to the state it was in before commit `9ce46d8d`: for
a multi-layer wallpaper it classifies **`layers[0]`** (the bottom-most, painted-first layer) behind
its existing two gates — the layer-level alpha gate (`alpha ≥ DOMINANT_ALPHA_THRESHOLD` **and**
Normal blend) and the pixel-level coverage gate (`WallpaperBitmapLuminanceImpl`) — and falls through
to the system-wallpaper `colorHints` signal when either gate fails. Single-layer is unchanged
(classify the one image). **The composite is never sampled for classification.**

Why this is the right cut, not a regression hidden: sampling the composite required the composite's
pixels to reach a `:domain` use case that cannot read `:app` and must be re-triggered when the warm
lands — a cross-module signal + a derived-key-in-`:data` that is the same cost whether the composite
is on disk or in RAM, and that is the seam that sank v3.3. It buys only the AUTO light/dark *surface
choice* for multi-layer wallpapers whose bottom layer is unrepresentative — a polish case with a
manual LIGHT/DARK override, and an *accepted* limitation for most of this project's life.

**Action:** re-open `ACCEPTED_LIMITATIONS.md #1` (the AUTO classifier not compositing multi-layer
wallpapers before choosing a surface), with the re-evaluation trigger: *revisit if a clean
cross-module composite-luminance signal becomes cheap (e.g. the classifier moves to `:app`, or a
buffered `CompositeReadySignal` is justified by another feature).*

---

## 6. What this deletes / keeps

**Deletes:** `WallpaperCompositeStore` (the whole file: write/prune/delete/clear/`dirLock`);
`flattenedWallpaperPath` from DataStore and from `WallpaperState`; `validatedCompositePath`; the
`wallpaper_composite/` dir and all its clear/purge wiring in `WallpaperRepositoryImpl`; the
composite branch of `ClassifyWallpaperUseCase.pickDominantUri` (`state.flattenedWallpaperPath?.let
{ return it }`). **The AUDIT-20 F1–F9 class and the classifier cross-module seam are both gone by
construction.**

**Rewires (not deletes) — the read path (§3a, review R1).** The three render gates that read the
deleted field are re-pointed at the `composite://<K>` cache key, not removed:
`displayTargetFor`/`renderingCompositeNow`/`renderingSingleImageNow` (`HomeFragment.kt:1526-1567`)
become `compositeCache.get("composite://"+K) != null` decisions, and `loadBitmapFromUri` gains the
`composite://` scheme branch. These must compile against the deleted field — the delete forces the
rewire (a good failure mode: it cannot ship half-done).

*Deploy cleanup (Rule 5, no migration):* an updated install stops reading
`KEY_WALLPAPER_FLATTENED_PATH` (a stale value is inert); any old `composite_*.webp` files in
`filesDir/wallpaper_composite/` are orphaned — delete the dir once on first launch (a one-shot
`deleteRecursively`, not a migration), then never touch it again.

*Docs to update (review R3 — else they assert the deleted design as shipped):*
`WALLPAPER_DRAWER_HOME_REBUILD_SPEC.md` is headed **"Status: IMPLEMENTED (Option D)"** and its §9 /
§9.5 still document the on-disk composite, the persisted pointer, and "the AUTO classifier closes
`ACCEPTED_LIMITATIONS #1`" — all false under v4. Add a superseded-by-note pointing here and correct
§9.5. The `ClassifyWallpaperUseCase` KDoc's composite-sampling half must revert with the code.
`ACCEPTED_LIMITATIONS.md #1` re-opens (§5). Part of the implementation, not optional cleanup.

**Keeps (smaller):** `WallpaperCompositeCache` — already a content-keyed single-entry memo with
`invalidate()`; its key changes from a path string to the §1 key (`imageUri` / `compositeKey`), and
it gains a **second writer** (the delegate warm for multi-layer; §3). `WallpaperFlattener` — gains
a local **completeness report** (all *visible* layers decoded) so a partial flatten is not cached,
and the **software→HARDWARE copy** the disk round-trip used to provide (§3). `maybeBackfillComposite`
becomes the async warm — no DataStore write, no pointer; its gate moves from `path == null` to a
`compositeKey` cache miss (§3). Net: **materially less code than today** — a whole `:data` store, a
DataStore key, and a cross-module classifier path all deleted; one in-memory memo remains, with the
one bitmap-config copy the disk used to hide made explicit.

---

## 7. Verify plan (bounded — the interleaving space is gone)

1. **`compositeKey` completeness (§2)** — property test over every pixel input, failing on a new
   un-hashed pixel field. *The* critical test; it is what makes the memo correct.
2. **Cache memoization** — single-layer hit/miss by URI; multi-layer hit/miss by `compositeKey`;
   `invalidate` drops the reference (extends `WallpaperCompositeCacheTest`). JVM, no device.
3. **Key-gated put** — `put(K, bmp)` is a no-op when `K` ≠ current key; a warm completing after a
   clear→NONE does not re-insert the stale bitmap. Bounded memory-hygiene pin, not correctness.
4. **All-or-nothing complete-flatten put** — a flatten with one *visible* layer skipped does NOT
   cache; an incomplete flatten never reaches L1 (so no frozen partial on screen). A failed
   *invisible* layer does NOT block the put.
5. **Fast-return on miss + self-rescheduling single-flight** — a multi-layer miss returns the
   per-layer signal and fires the async warm; one flatten at a time; a warm completing against a
   changed key kicks one more (no stranded final key).
6. **Cancellation cleanliness** — a cancelled flatten recycles the software bitmap, no leak; the
   warm's broad catch carries the `CancellationException`-first arm (Rule 11).
7. **Classifier re-shelf** — `ClassifyWallpaperUseCase` for a multi-layer state classifies
   `layers[0]` behind its gates, never a composite; `ACCEPTED_LIMITATIONS.md #1` updated.
8. **One** adversarial review against §1's invariant — a tiny surface (one in-memory single-entry
   memo, no file, no pointer, no lock, no signal; the only stateful point is the key-gated put).

Done = §1's invariant holds under §4, verified by (1)–(7), audited once by (8).

---

## 8. The two accepted costs (state them plainly)

1. **Cold-start reveal reverts to a per-layer render.** Without a persistent tier the first
   drawer→home / cold start of a multi-layer wallpaper renders N textures (the brief
   system-wallpaper flash, ~2.8 dropped frames) instead of one disk-composite decode, paid **once
   per process life** amid other cold-start cost. This was the disk file's *only* real benefit (the
   spike proved it is not the drawer→home win — the in-memory cache is). drawer→home stays ~0 ms via
   the warmed cache **once the §3a read bridge is built** — it is not free-by-default: the cached
   composite only renders as one texture if the `composite://<K>` read path (R1) is in place; without
   it the multi-layer state falls to `FullRebuild` every drawer→home while the cache is read by
   nobody. (§3a is therefore load-bearing, not an optimization.)
2. **AUTO light/dark uses the bottom-layer heuristic for multi-layer wallpapers.** A multi-layer
   wallpaper whose `layers[0]` is unrepresentative of the composited appearance may get the wrong
   AppDrawer surface in AUTO mode. Mitigated by the manual LIGHT/DARK override and the existing
   alpha/coverage gates that fall through to the system signal. Tracked as
   `ACCEPTED_LIMITATIONS.md #1` (re-opened, §5).

The deliberate trade: a brief cold-start flash + a bottom-layer AUTO heuristic, in exchange for
deleting the entire disk lifecycle **and** the cross-module classifier signal — the two concerns
that made this "so hard," neither of which was the cache.
