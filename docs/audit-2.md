# Audit 2 — `nyx` launcher (internal)

**Date:** 2026-09-28
**Branch/base:** `main` @ `60d4621`
**Scope:** the **nyx-internal** launcher logic across all three modules —
`:nyx:app` (grid/dock/folder/drag/wallpaper UI + ViewModels + coordinators),
`:nyx:domain` (models, use cases, layout transitions/regridder/reconciler),
`:nyx:data` (DataStore persistence, backup/reset, icon render/cache, DI).
~11.7k LOC production.

**Explicitly out of scope** (to avoid re-litigating settled ground):

- The shared **installed-apps** motor in `:core` / `:common-data` — already
  covered by `docs/audit-1.md` (2026-09-24). Only nyx's *consumers* of it were
  in scope here.
- Items documented in `ACCEPTED_LIMITATIONS.md` (e.g. drag-only home/drawer
  organisation not being TalkBack-operable).
- Already-fixed / already-reviewed work: the `FolderIconRenderer` init-order
  race (comment-hardened), the F1–F3 in-place-icon-targeting series, the
  restored OOM-guard.

## Method

Two-stage multi-agent review, all agents on Opus 4.8 (per the family
sub-agent-model convention):

1. **Review** — 5 topical agents over the three modules: ① domain correctness
   (transitions/regridder/reconciler), ② app concurrency/state/lifecycle,
   ③ architecture & layering, ④a performance (icon caches / adapters /
   wallpaper), ④b robustness & security (backup/persistence/reset/crash paths).
   Each read the actual code + tests + `git`, and was told to say where a
   flagged path is in fact robust.
2. **Verify** — 1 adversarial skeptic per *refutable functional* medium (the
   three cross-module mediums C-sev candidates: self-uninstall, cold-start
   double-decode, backup blob cap), each instructed to *refute* by reading the
   code. The architecture/low findings are code-facts (logic demonstrably lives
   untested in the Activity) or were already traced adversarially in stage 1, so
   they carry the review agent's verdict without a separate verifier.

## Verdict summary

**Headline: no High- or Medium-severity *correctness* bug survived
verification.** The domain (the correctness crown-jewel) is exceptionally
well-guarded by an ~80k-transition seeded property walk plus regression pins,
and all three functional mediums were **downgraded to LOW** by the adversarial
verifiers. The highest-value *actionable* items are two testability/seam
refactors in `MainActivity`.

| ID | Finding | Filed | Verified | Verdict |
|----|---------|-------|----------|---------|
| N1 | Dock-drop insert-index geometry trapped & untested in `MainActivity` (duplicates the tested `gridDropAt`) | med | **med** (testability) | CONFIRMED |
| N2 | `appShortcuts` filter/sort/`take(4)`/enabled/default-launcher policy un-seamed & untestable in `MainActivity` | med | **med** (testability) | CONFIRMED |
| N3 | `requestSelfUninstall` prunes the tile on *any* package disappearance in a 5-min window (incl. external uninstall / cancelled dialog) — contradicts the documented external-uninstall policy | med | **low** | PARTIALLY-CONFIRMED |
| N4 | Cold-start COLOR-default window → second decode + dead WEBP write of visible icons for non-COLOR users | med | **low** | PARTIALLY-CONFIRMED |
| N5 | Backup import: blob loop uncapped (count/size) + blobs extracted before manifest validation → transient disk-fill | med | **low** | PARTIALLY-CONFIRMED |
| N6 | Silent `runCatching { startShortcut }` swallows every `Throwable` incl. programming errors, no log/toast | low-med | **low-med** | CONFIRMED |
| N7 | Same-package icon repaint can be lost mid-drain (value-equal StateFlow + consume-clear) | low | **low** | CONFIRMED |
| N8 | `appliedIconStyle` starts `null` → forced full-surface refresh on first style emission even for COLOR users | low | **low** | CONFIRMED |
| N9 | Factory reset not atomic across the two DataStores + files (partial-failure residue) | low | **low** | CONFIRMED |
| N10 | Drawer folders restored from backup without the post-restore reconcile the home layout gets | low | **low** | CONFIRMED |
| N11 | `FolderMemberAdapter.submit` has no value-equal short-circuit (consistency gap; ~zero cost today) | low | **low** | CONFIRMED |
| N12 | Regridder no-op guard omits a `pages` term → stale trailing pages linger until a separate reconcile | low | **low** (cosmetic) | CONFIRMED |
| N13 | Regridder/reconciler collision checks key on top-left `pos`, not the `span` footprint | latent | **latent** (v2 only) | CONFIRMED (unreachable in v1) |
| N14 | Property-walk oracle asserts structure but not app *conservation* | low | **low** (test-robustness) | CONFIRMED (no live loss found) |
| N15 | ComponentKey mapper does not validate/sanitize pkg/class on import | low | **low** | CONFIRMED (no crash path found) |
| N16 | Scrim applied twice at startup (pre-seed + collector replay) | info | **info** | CONFIRMED (not worth fixing) |

**Totals:** 2 CONFIRMED medium (both testability/seam, not bugs),
3 PARTIALLY-CONFIRMED (all medium→low), 11 low/latent/info. Zero correctness
defects.

---

## Medium (testability / maintainability — not correctness bugs)

### N1 — Dock-drop insert-index geometry is trapped in the Activity and untested · CONFIRMED

**File:** `nyx/app/.../home/MainActivity.kt:929-955` (dock `DropZone.onDrop`).

The centre-vs-edge + insert-index decision for a dock drop (which icon a drop
lands *on* vs. which gap a `DockSlot` inserts into) is computed inline in the
Activity, interleaved with RecyclerView child traversal. The **grid's identical
decision is already a pure, JVM-tested helper** — `gridDropAt` in
`HomeGridGeometry.kt` (`HomeGridGeometryTest`), using the same
`GRID_INSERT_EDGE_FRACTION`. The dock path re-implements that geometry with zero
coverage, so the two can silently drift — the in-code comment even asserts "dock
and grid folders behave the same," which is currently unverifiable.

**Minimal fix:** add `dockDropAt(children: List<IconBounds>, localX): DropTarget`
beside `gridDropAt`; the Activity just harvests visible children into the value
list and calls it. Pure, testable as a truth table like the grid.

### N2 — `appShortcuts` holds real launcher policy un-seamed and untestable · CONFIRMED

**File:** `nyx/app/.../home/MainActivity.kt:1585-1616`.

Of the three framework-leak sites flagged, this is the one with enough *logic*
to deserve a seam: the `ShortcutQuery` flag policy, the `isEnabled` filter,
`sortedBy { rank }`, the `take(4)` cap, label fallback
(`shortLabel ?: longLabel`), and the "not default launcher → empty list" policy.
All trapped in the Activity, all untestable. A grep of `:core`/`:common-*`/
`nyx/domain` found no shortcut abstraction — this is nyx-owned un-seamed system
logic, exactly the kind of ordering/cap rule that regresses unnoticed (bump
`take(4)`, drop the enabled filter) with no test to catch it.

**Minimal fix:** a thin data-side source
`LauncherShortcuts.forPackage(pkg): List<AppShortcut(id,label,rank,enabled)>`
returning a domain model; keep the filter/sort/`take(4)` as a pure function over
that list (testable). The `Drawable` resolution + `startShortcut` invocation stay
in the UI.

> **Not seamed (verified appropriate):** `openAppInfo` (`:1650`) and
> `uninstallApp` (`:1662`) are trivial Intent builders whose *gating decision*
> is already pure and tested in `HomeContextMenu.buildHomeContextMenuActions`
> (`HomeContextMenuTest`). `isSystemApp` (`:1671`) is a one-line PM read feeding
> that pure function — no logic to extract. (Minor: it runs as a synchronous
> `getApplicationInfo` IPC on the main thread inside the long-press handler at
> `:1500` — cheap, but a candidate to fold into the already-async shortcut-load
> coroutine if it ever shows in a jank trace.)

---

## Low (functional — all three ex-mediums verified down)

### N3 — `requestSelfUninstall` over-broad tile removal · PARTIALLY-CONFIRMED (was medium)

**File:** `nyx/app/.../home/HomeViewModel.kt:286-296`.

`requestSelfUninstall` waits `installedKeys.first { isPackageMissing(pkg, it) }`
(up to `SELF_UNINSTALL_TIMEOUT_MS = 300_000`) then calls `removeItem(id)`. There
is no attribution to the confirmed uninstall — `installedKeys` is the global
installed set, so **any** disappearance of the package within 5 minutes fires the
removal. `MainActivity.uninstallApp` (`:1662-1669`) launches `ACTION_DELETE` with
plain `startActivity` (no `registerForActivityResult`), so cancelling the system
dialog does **not** cancel the coroutine. This contradicts the documented policy
(`ARCHITECTURAL_DIFFERENCES.md:36`, `HomeViewModel` KDoc `:279-280`,
`nyx/TODO.md:210-215`): an *external* uninstall is supposed to keep the greyed
tile. `HomeViewModelTest.kt:462` pins exactly the over-broad behaviour.

**Why low:** the trigger is a rare, self-inflicted sequence (tap Uninstall →
cancel dialog → externally uninstall the same package within 5 min), and the
removal is strictly id-scoped — it drops one greyed broken-shortcut placeholder,
no data loss, trivially recreatable. **Clean fix if wanted:** gate removal on the
actual uninstall result (`registerForActivityResult` / `PackageInstaller`
callback) instead of polling the global set.

### N4 — Cold-start COLOR-default double-decode · PARTIALLY-CONFIRMED (was medium)

**Files:** `nyx/data/.../data/icon/IconLoaderImpl.kt:71-85` (+ cache key
`IconCacheKey.kt:19-30`), `nyx/app/.../home/MainActivity.kt:492-503`.

`_currentStyle` seeds to `IconStyle.COLOR`; the real preference arrives async and
`bitmap()` reads `_currentStyle.value` with no gate. The **cache key includes the
style** (`variant.name` folded into the content hash), so a re-decode under the
real style is a genuine memory+disk miss → `source.load()` + WEBP write, not a
cache hit. For a MONOCHROME/GRAYSCALE user whose first bind loses the race, the
visible tiles decode once under COLOR (dead ADAPTIVE-keyed `.webp` written) and
again under the real variant when `appliedIconStyle` (`null`→first emission)
forces a `refreshIcons()`.

**Why low, not "every cold start":** it is a race, and the ordering is biased
*against* it — the `layout` flow is `WhileSubscribed(5_000)` and only starts its
disk read once the STARTED collector subscribes, typically *after* the icon-style
collector has begun; the grid can't bind until layout emits. Bounded to a
page+dock of icons, one-time, self-correcting, non-COLOR-only, dead files
auto-pruned. Possible transient COLOR→MONO flicker on a lost race is the only
user-facing symptom. **Fix if wanted:** gate the first bind on
`iconStyle().first()` (pairs with N8).

### N5 — Backup import: uncapped blob loop + extract-before-validate · PARTIALLY-CONFIRMED (was medium)

**File:** `nyx/data/.../data/home/NyxBackupManager.kt:137-157`
(+ `WallpaperFileManager.copyFromInputStream`, `:common-data`).

Only the manifest is bounded (`MAX_MANIFEST_BYTES = 5 MiB`). The `wallpapers/`
blob loop streams every entry via an unbounded `inputStream.copyTo(output)` —
no `ZipEntry.getSize()` precheck, no per-entry/total-size cap, no entry-count
cap, no free-space check — and blobs are written to disk **before** the manifest
is deserialized/validated (extraction is gated only on the `wallpapers/` name
prefix, not on the manifest referencing the file). A crafted/oversized "launcher
setup" backup can transiently fill the disk. No test covers a blob cap.

**Why low:** import is behind a user-initiated SAF `OpenDocument` pick (no
drive-by), runs off the main thread (`withContext(ioDispatcher)` — no ANR), is
self-limiting (ENOSPC → `copyTo` throws → `InvalidData`, layout applied last so
it's untouched), and orphaned blobs **are** reclaimed by the startup
`reclaimOrphans()`/`gcOrphans` sweep (the review's "never cleaned up" aggravator
was **refuted**). **Fix if wanted:** cap per-entry size + entry count (or extract
only manifest-referenced blobs, which also fixes the ordering), plus a zip-bomb
test.

### N6 — Silent `runCatching { startShortcut }` swallows programming errors · CONFIRMED (low-med)

**File:** `nyx/app/.../home/MainActivity.kt:1608`.

`runCatching { launcherApps.startShortcut(...) }` discards the `Result` — no
`.onFailure`, no log, no toast. Any `Throwable` (incl. an NPE / programming
error) becomes a silent no-op, so a failed shortcut tap does nothing and leaves
no trace. This is the exact too-broad-catch case `nyx/CLAUDE.md` names as the
first Exception-breadth review candidate, and it's inconsistent with
`startActivitySafe` (`:1677-1693`), which distinguishes failure classes, toasts,
and `reportToAcra`/`silentError`. **Fix:** narrow to the documented throwers and
route failure through `showToastSafe(R.string.app_launch_failed)` +
`TimberWrapper.silentError`. (The sibling `runCatching { getShortcutIconDrawable }`
at `:1603` is broad too but degrades gracefully via `.getOrNull()` — lesser
issue.)

### N7 — Same-package icon repaint lost mid-drain · CONFIRMED (low)

**Files:** `nyx/app/.../PackageEventCoordinator.kt:200-202` (`consumeIconRepaints`)
+ `MainActivity.kt:513-521` (drain loop).

`pendingIconRepaints` emits `{A}`; the Main-thread drain re-decodes A; a second
in-place event for A on the IO scope does `evict(A)` then
`update { it + "A" }` → set stays `{A}` (value-equal, no re-emission); the Main
thread then `consumeIconRepaints({A})` clears it. If the Main re-decode read A's
icon before the IO evict, the tile is stuck on the stale bitmap. A *different*
package re-added mid-drain is safe (consume is a set-difference). Window is
microseconds and needs two rapid same-package invalidations — rare.

### N8 — First style emission forces a full-surface refresh even for COLOR users · CONFIRMED (low)

**Files:** `nyx/app/.../home/MainActivity.kt:493-503`,
`home/drawer/AppDrawerFragment.kt:100,201-205`.

`appliedIconStyle` starts `null`, so the first `currentStyle` emission always
passes the dedupe guard and fires `refreshIcons()` / `notifyDataSetChanged()`
across pages + dock + drawer — even for the common COLOR user whose surfaces were
just bound correctly. Mostly redundant *rebinds* (absorbed by the icon memory
cache), not re-decodes, but an avoidable full-surface churn at startup.
**Fix:** initialise `appliedIconStyle` to the loader's default (COLOR) — pairs
with N4.

### N9 — Factory reset is not atomic across two DataStores + files · CONFIRMED (low)

**File:** `nyx/data/.../data/home/NyxResetManager.kt:41-44`.

Three sequential moves — `dataStore.edit{clear()}` → `purgeRepository()`
(separate `nyx_usage` store) → `fileManager.clearAll()`. A failure at step 2 or 3
(caught, returns `false`) leaves residue (layout cleared but usage/wallpaper
files remain). Self-heals on a retried reset and the UI sees `false`, so low —
but the per-step partial-failure window is real and unguarded.

### N10 — Drawer folders restored without the post-restore reconcile · CONFIRMED (low)

**File:** `nyx/data/.../data/home/NyxBackupManager.kt:169`.

Home layout gets `reconcileHomeLayout()` after restore; drawer folders are
persisted verbatim, and `DrawerFolder` has no constructor validation — a crafted
backup can persist a 0/1-member or duplicate-member folder (violating
DFOLD-INV-1/-2). Not a crash: the drawer is a self-healing projection reconciled
at read time in `GetDrawerContentUseCase`, so at worst a transient malformed
folder until the read-side reconcile runs.

### N11 — `FolderMemberAdapter.submit` lacks the value-equal short-circuit · CONFIRMED (low)

**File:** `nyx/app/.../home/FolderMemberAdapter.kt:37-40`. Unconditional
`notifyDataSetChanged()`, unlike the sibling `DockAdapter`/`HomePagerAdapter`
which got the `==` guard. Real cost is ~zero today (a fresh adapter per
folder-open, `submit` fires once on open + once per bulk-add — not a reactive
flow). Consistency gap that would bite if `submit` is ever wired to a reactive
members flow.

---

## Latent / test-robustness (no live defect)

### N12 — Regridder trailing-page guard asymmetry · CONFIRMED (cosmetic)

`nyx/domain/.../home/transition/HomeLayoutRegridder.kt:59-73`: the no-op guard
omits any `pages` term, so a layout whose grid already matches the device grid
but carries a stale `pages` count (reachable — `remove()`/emptying-move never
decrements `pages`) is left `Unchanged`; only the reconciler's Pass 5 trims it,
and the edit use-cases don't reconcile. Items are never dropped/relocated wrongly
(the item partition *is* identical) — only the derived `pages` lingers, likely
invisible if the page-dot UI derives from occupied pages. Test gap: no regridder
case for matching-grid + stale trailing pages.

### N13 — `span`-footprint blind spot (v2 forward-compat) · CONFIRMED (unreachable in v1)

`HomeLayoutRegridder.kt:61-64,92-93` and the reconciler key collision/off-grid
checks on the top-left `pos` only, while the IHM-INV-3 oracle
(`HomeLayoutInvariants.kt:31-38`) checks the full `x+span.w`/`y+span.h` footprint.
Unreachable in v1 (no transition/mapper sets span > 1×1), but an imported/hand-
edited v2 `Span(2,…)` blob could carry a footprint the regridder classifies as
in-bounds/non-colliding and heals neither. Worth a footprint-aware check when the
v2 widget lift lands.

### N14 — Property oracle doesn't assert app conservation · CONFIRMED (test-robustness)

`invariantViolations()` (the sole assertion of the ~80k-transition property walk)
checks structural well-formedness but **not** that no top-level tile / folder
member silently disappears or duplicates across scopes. Current transitions were
traced and lose nothing, so this is a latent gap in the safety net, not a bug.
The fuzzer also seeds only *valid* layouts (transitions over invalid imported
blobs are example-covered only — defensible, since reconcile/regrid heal imports
first). Adding a conservation assertion would harden the net.

### N15 — Import ComponentKey not validated · CONFIRMED (no crash path)

`HomeLayoutMappers.kt:60` / `HomeLayoutDto.kt:51` accept any pkg/class string,
persisted verbatim. Traced the primary consumers of a crafted/empty key — icon
resolution (throws → caught, see robust list), launch (routes through
`AppLaunchResult` → toast), dot/grouping (string compare) — no crash found.
Medium confidence (primary consumers, not exhaustive).

### N16 — Scrim applied twice at startup · CONFIRMED (info)

`MainActivity.kt:436-437` pre-seeds+applies the scrim, then the collector replay
at `:543` applies the identical value once more. One redundant `applyScrim()` per
launch; not user-visible. The pre-seed is the intentional paint-before-first-frame
optimization; noted for completeness, not worth a fix.

---

## Verified robust (flagged, then cleared — recorded so they aren't re-flagged)

**Domain correctness (① — property-test-backed):**
- `removeFromFolder` dissolve ordering — survivor placed first onto the freed
  cell, extracted member re-computes its fallback against the survivor-present
  base; own-cell target rejected on the original layout. Collision structurally
  impossible; regression-pinned.
- `insertOnGrid` reorder-shift / full-page spill / MAX_PAGES reject — traced
  clean on 2×2 and 4×6.
- Folder-creation target-first ordering; dock capacity in
  `moveToDock`/`moveOntoDockItem`/`emptyTargetReason` (capped at columns,
  regression-pinned).
- Reconciler per-scope dedup (Dock>Grid, per-folder), folder repair, trailing
  trim, idempotency — no app-key lost, no new collisions. Over-capacity-dock is
  intentionally the regridder's job, not the reconciler's.
- `DrawerFoldersTransition` DFOLD-INV-1/-3 (dissolve-<2, strip-before-add).

**Concurrency (②):** `installedKeys` `WhileSubscribed` staleness (reads only
while STARTED + hot combine collector); `refreshDrawer` newest-wins on
`Main.immediate` + never-blank guard; `NyxApplication` onCreate init order
(cold-start reconcile is structural-only, order-independent; empty-set guard
covers the window); wallpaper edit mirror/rollback (`editRollbackGeneration`,
`backdropToggleMutex`, all Main-confined); `wallpaperRenderScheduler` +
`putIfCurrent`/generation; `contextMenuGeneration`; **dock drop-zone index math**
(consistent coordinate space — the geometry is correct; N1 is about *testability*,
not a coordinate bug); `InstalledAppsHolderPump` single-writer + retry.

**Architecture (③):** all five repo **contract-test triples are complete** — the
mapping phase's "`HomeLayoutRepositoryImplContractTest` missing" was **stale**;
the file exists and every interface has both a Fake- and an Impl-side contract
test. DI scoping verified correct (stateful DataStore repos + app caches all
`@Singleton`; `@UsageDataStore` qualifier wired end-to-end; the unscoped `@Binds`
are stateless/pure aliases). `MainActivity` largely honours Rule 10 — pure logic
is already extracted (`HomeGridGeometry`, `EdgeAdvanceController`,
`HomeContextMenu`, gesture/title/anchor helpers, wallpaper/clock coordinators);
what remains is genuine view glue plus N1/N2.

**Performance (④a):** no icon decode on the main thread; token-gating correct
under fast scroll; `IconLoaderImpl` await-outside-lock + `inFlight` coalescing +
byte-bounded LRU + CAS-throttled prune; `HomeGridAdapter` positional diff rebinds
only changed cells with guarded layout writes; payload channels (dot / style /
per-package repaint) avoid full re-decode incl. coalesced batches; drawer
content-identity diff; complete, level-graded `onTrimMemory`;
`WallpaperLayerBitmapCache` byte-bounded + generation-guarded + cleared on
UI_HIDDEN; never-recycle on cached/shared output bitmaps is correct (GC reclaims).

**Robustness (④b):** backup zip-entry-name path traversal **not** possible
(`entry.name` used only for equality/prefix/map-key, never as a filesystem path;
on-disk names are internally generated); **all** `IconLoader.bitmap` call sites
guard the `NameNotFoundException` throw for uninstalled packages
(`loadIconGated` `runCatching` / `FolderIconRenderer` own `runCatching`); the
two-tier **fail-open-read / fail-closed-RMW** persistence contract holds on every
destructive write path; every serializer `deserialize` is `runCatching → null →
default`, so a corrupt blob can't crash a read; import ordering
prefs→wallpaper→layout-LAST holds (most-valuable layout preserved on partial
failure).

---

## Recommended priority

1. **N1 + N2** — the two `MainActivity` extraction/seam refactors. Highest value:
   they turn currently-untestable launcher policy (dock-drop geometry, shortcut
   filter/sort/cap) into pinned pure logic, and N1 closes a real grid/dock drift
   risk. Small, local, high leverage.
2. **N6** — narrow the silent `startShortcut` catch and route it through the
   existing toast/ACRA path. Tiny, and removes a swallow-everything site the repo
   conventions explicitly flag.
3. **N5** — add a per-entry/count cap (or manifest-referenced-only extraction) to
   backup import + a zip-bomb test. Cheap hardening on an untrusted-input path.
4. **N3 / N4 / N8** — nice-to-have correctness/efficiency polish (gate self-
   uninstall on the real result; gate first icon bind on the real style; seed
   `appliedIconStyle` to COLOR). N4 + N8 share a fix.
5. Everything else (N7, N9–N16) is low/cosmetic/latent — batch opportunistically;
   N13 (span footprint) is a note to honour when the v2 widget work lands.

_No code was changed by this audit; all fixes are left to a separate decision._
