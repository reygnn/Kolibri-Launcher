# WALLPAPER_COMPOSITE_LIFECYCLE_SPEC

> **Status: DESIGN / greenfield ownership spec (2026-08-18).** Not yet implemented.
> Motivated by three adversarial review rounds + one spike all clustering on the SAME
> bug class on the SAME code (AUDIT-20 F1–F8). This spec exists to stop that churn: name
> the one invariant, assign one owner, enumerate the transitions, define a **bounded**
> verify plan — and only then consolidate the five current call sites onto one
> transactional API. Sibling of `WALLPAPER_DRAWER_HOME_REBUILD_SPEC.md` (which decided
> the composite should exist) and `AUDIT-20.md` (which found the eight lifecycle bugs).
> The spike `WALLPAPER_INMEMORY_COMPOSITE_SPIKE.md` already settled that the disk file
> MUST stay (flatten-from-source is ~227 ms / O(N) vs the ~90 ms / O(1) disk read).

---

## 0. The thesis

The eight AUDIT-20 findings are **not eight bugs. They are one bug class, found eight
times:**

> **The persisted pointer, the on-disk file, and the in-memory bitmap can diverge.**

Every fix closed one divergence path; the next review found another — because the
underlying invariant was never enforced in **one** place. The root cause is
architectural, not careless coding:

> **The composite is a stateful, concurrently-mutated resource with a derived pointer,
> and it has no single owner.** Its lifecycle is spread across five sites in two modules,
> coordinated by an app-layer lock that structurally cannot reach the data-layer callers.

This spec fixes the *ownership*, not the symptoms.

---

## 1. The resource and the invariant

Three pieces of state make up "the composite":

| Name | What | Where today |
|---|---|---|
| **FILE** | 0 or 1 `composite_<…>.webp` (+ transient `.tmp`) | `filesDir/wallpaper_composite/` (`:data` `WallpaperCompositeStore`) |
| **POINTER** | which file is current (`flattenedWallpaperPath`) | DataStore (`:data` `WallpaperRepositoryImpl`) |
| **CACHE** | the decoded HARDWARE bitmap of the current composite | in-memory `@Singleton` (`:app` `WallpaperCompositeCache`) |

**THE INVARIANT (must hold after every transition):**

- **I1 — Uniqueness.** At most one committed composite FILE exists (transient `.tmp`
  excepted, and swept).
- **I2 — Pointer integrity.** A non-null POINTER names an existing FILE that is the
  flattening of the *current* layer set. (This is the invariant F2/F4/F7/F8 broke.)
- **I3 — Cache coherence.** CACHE holds a bitmap for key `K` ⇒ `K` is the current
  composite's identity. A just-superseded bitmap may still be *drawing* (never-recycle),
  but it is keyed by the old identity, so a lookup by the new identity misses. (F3/F9.)
- **I4 — Purity.** The composite is a pure function of the layer set: identical layers ⇒
  identical pixels. This is what makes "stale" *detectable* and "current" *derivable*.

Map of the findings to the invariant they broke — proof they are one class:

| Finding | Broke | Via ownership seam |
|---|---|---|
| F1, F6, F7, F8 | I1 / I2 | concurrent unowned mutation (two doors, app-lock can't span :data) |
| F2, F4 | I2 | POINTER names a missing / corrupt FILE |
| F3, F9 | I3 | CACHE / FILE outlive a cleared POINTER |

---

## 2. The design: content-addressing + one owner

Two moves, together, make most of the class **structurally impossible** rather than
patched.

### 2a. Content-address the FILE → delete the POINTER (kills the I2 class)

By I4 the composite is a pure function of the layers. So name the file by a **content
hash of the layer set** (the `WallpaperState.compositeContentKey` the spike already
prototyped: SHA-256 over each layer's uri, transform, alpha, blend, visibility, order).
Then:

> **The "current composite path" is DERIVED from the persisted layers, not persisted
> separately.** The layers already live in DataStore; the expected composite path is a
> pure function of them. There is **no independent POINTER to go stale.**

- Display: compute `expectedPath = hash(currentLayers)`. FILE exists → decode + cache.
  Missing → flatten → atomic write → done. (Backfill and first-render collapse into the
  same path.)
- A layer edit changes the hash → a different expected path → automatic miss → re-flatten.
  No "null the pointer on every mutation" dance, no dangling pointer possible.
- **F2 vanishes** (no pointer to dangle). **F7 simplifies** (no latest-wins *pointer*
  write to order against pruning). **F8's data-side pointer write is gone.**

`flattenedWallpaperPath` is dropped from DataStore. Per Rule 5 (no in-code migration):
the composite is *derived*, not user data — an updated install simply finds no
content-addressed file yet and backfills on first display; any old fixed-name composite is
pruned on the first content-addressed write. No migration code.

### 2b. One owner with one lock (kills the I1 concurrency class)

Introduce a single authority — extend `WallpaperCompositeStore` into the **owner of FILE +
CACHE**, with its existing internal `dirLock` (added in F8) as the *only* serialization
point. It exposes a small transactional API, and **nothing else may touch the composite
dir or the cache**:

```
suspend fun display(state, flatten: suspend () -> Bitmap?): DecodedComposite?
    // hash(state) -> cache hit? return it.
    // file exists? decode HARDWARE, cache, return.
    // else: flatten() (caller-supplied, :app owns the pixels) -> atomic write -> decode/cache.
    // all under dirLock; prune non-current files after a successful write.

suspend fun clear()
    // under dirLock: delete every composite file + invalidate cache. Idempotent.
```

- The flatten stays in `:app` (it needs a `ZoomableImageView`); it is **passed in** as a
  suspend lambda. The owner never flattens — it owns *persistence + lifecycle*. Clean
  layering: `:app` produces pixels, `:data` owns the resource.
- Every caller — delegate commit / backfill / display, repo clear / purge, backup restore,
  factory reset — routes through `display()` / `clear()`. **No caller writes the DataStore
  key or calls `deleteAll()` directly.** The cross-layer race is gone because **there is no
  second door**, not because a lock is disciplined across modules.

---

## 3. Transition table (the interleaving space, written down)

The space three reviews discovered incrementally, now enumerated. Every row is atomic
under `dirLock`; every row preserves I1–I4.

| Event | Owner action | Preserves |
|---|---|---|
| **Display multi-layer** (drawer→home, cold start) | `display()`: cache hit \| file hit (decode) \| flatten→write→prune | I1,I3,I4 |
| **Edit-commit** (layers changed) | new hash ⇒ `display()` misses ⇒ flatten new, prune old | I1,I2,I4 |
| **Backfill** (multi-layer, no file yet) | identical to Display miss — collapses into `display()` | I1,I4 |
| **Layer mutation mid-edit** | no file op; hash of the new state simply differs | I2,I4 |
| **Clear** (user remove) | `clear()`: delete all + invalidate cache | I1,I3 |
| **Factory reset** | `clear()` (re-emits NONE, no process restart) | I1,I3 |
| **Backup restore** | `clear()` then the restored layers drive a fresh `display()` | I1,I2,I3 |
| **Superseded flatten** (latest-wins loss) | its write is content-addressed to ITS state; if not current, prune drops it; it never unlinks the current file | I1,I2 |

The key property: because the filename is the content hash, a "superseded" or "concurrent"
flatten writes to a **different path** than the current one, so it can never unlink the
file the current state needs. Prune keeps exactly `hash(currentState)`.

---

## 4. Verify plan — BOUNDED (not "until perfect")

"Verify until perfect" is unbounded and a trap. Verify the **named invariant** against the
**enumerated transitions**, then stop:

1. **Property tests** — `compositeContentKey` is stable for identical content and changes
   on every pixel-affecting field (the spike's `WallpaperCompositeContentKeyTest` is the
   seed).
2. **Concurrency gate tests** — the F1-mutex-test pattern (a `CompletableDeferred` gate
   parks one transition inside the lock) for each interleavable pair: commit×clear,
   backfill×restore, commit×backfill, display×clear. Assert I1–I3 hold afterward. Pure
   JVM, deterministic (Rule 10 — no device).
3. **One** adversarial multi-agent review **against the invariant in §1** — not
   open-ended. The question is precisely "can any transition leave I1–I4 violated?", not
   "find anything."
4. **On-device sanity** — the spike's measurement harness (force-stop→home, logcat) to
   confirm no regression in the drawer→home / cold-start paths.

Done = the invariant provably holds under §3, verified by (1)+(2), audited once by (3).

---

## 5. What this explicitly does NOT change

- **The flatten cost / the disk file.** The spike settled it: flatten-from-source is
  ~227 ms and O(N); the disk read is ~90 ms and O(1). The file stays, amortized at
  commit/backfill. This spec is about *owning* it, not removing it.
- **The never-recycle invariant** (I3 encodes it).
- **The AUTO classifier**, which samples the composite — it reads through the owner.
- **The `:app → :data → :domain` dependency chain** — the owner sits in `:data`, the
  flatten (pixels) stays in `:app` and is injected as a lambda.

---

## 6. Why this is worth doing now (and why it is NOT a rewrite)

Three review rounds + a spike on the same write/cleanup side, all one bug class, is a
signal that the **abstraction is wrong (distributed ownership)**, not that we are unlucky.
That is the moment to invest.

But it is **consolidation, not a from-scratch rewrite.** Rewriting working concurrent code
trades known, fixed bugs for unknown new ones; all F1–F8 were `low`/`med`, mostly
self-healing, review-found, **never user-reported** — the code *works*, the *churn* is the
cost. The leverage is: one owner (one door), content-addressing (no independent pointer).
Most of F1–F8 stop being reachable. The path stays intrinsically delicate — concurrent
mutation of a shared resource is hard — but the delicacy becomes **explicit, owned, and
bounded by a written invariant** instead of rediscovered one review at a time.

### Rollout

1. This spec reviewed / agreed.
2. Owner API + content-addressing in `:data` (`WallpaperCompositeStore` → transactional
   `display()`/`clear()`), `compositeContentKey` promoted from the spike branch.
3. Route the five call sites through it; delete the DataStore `flattenedWallpaperPath` key,
   `validatedCompositePath`, `maybeBackfillComposite`'s separate gate (collapses into
   `display()`).
4. The §4 verify plan.
5. One adversarial review against §1.
