# Architectural Differences: kolibri vs nyx (app handling)

Scope: how the two launchers handle the **installed-app list** — loading, freshness,
uninstalled/"missing" references, and the curated stores (favorites / home layout /
swipe / hidden / custom names). It does **not** cover unrelated subsystems (wallpaper,
backup, clock/events).

This reflects the state **after** the lazy-slot rebuild and the parity patch set
(`LazySlotMembership`, the nyx package-event refresh, the `NoAutoPruneContract` seam,
the DiffUtil adapters, and the provisional-missing change). Where a row was changed or
introduced by that work it is noted.

Legend: **[shared]** one implementation, **[intentional]** a deliberate per-launcher
design, **[asymmetry]** a behavioural difference that could be closed, **[open]** a
flagged-but-undecided point. Each difference below also carries an **Align:** note — the
cost/risk of making the two launchers match (see the summary right after the table).

| Dimension | kolibri | nyx | Status |
|---|---|---|---|
| Installed-apps motor | `:common-data` `InstalledAppsRepositoryImpl` (`AppEnumerator`) | same | [shared] |
| Auto-prune of curated stores | none | none | [shared] |
| "missing" membership rule | `LazySlotMembership.isMissing` | `LazySlotMembership.isMissing` | [shared] |
| Package-event → loader refresh | `KolibriLauncherApp` / `AppManagementDelegate` → `triggerAppsUpdate()` | `PackageEventCoordinator` → `triggerAppsUpdate()` | [shared] |
| No-auto-prune regression guard | `KolibriNoAutoPruneTest` | `NyxNoAutoPruneTest` (shared `NoAutoPruneContract`) | [shared] |
| Adapter list-diffing | `HomeFavoritesAdapter` extends framework `ListAdapter` | `HomeGridAdapter` (plain `RecyclerView.Adapter`, hand-rolled `DiffUtil.calculateDiff`) | [intentional] |
| Home model | flat favorites list | grid + dock + folders + structural reconciler | [intentional] |
| Freshness posture | central holder + keep-last-good | central holder + keep-last-good (shared machinery; Option A) | [shared] |
| Cold-start "missing" paint | grey immediately (provisional) | normal → grey on load | [asymmetry] |
| Drawer custom-name overlay | applied | not applied yet | [asymmetry] |
| Missing folder members | n/a (no home folders) | greyed + removable in the open folder; the folder container itself is neutral | [intentional] |
| Warm enumeration while home visible | holder is the norm | always-warm holder via shared pump (Option A resolved the [open]) | [shared] |
| Gone-app launch feedback | swipe slot pre-checks + specific toast | any launch path catches `ComponentGone` → generic toast | [asymmetry] (wording only) |

---

## Alignment cost at a glance (cheapest → ugliest)

What each *difference* would take to make the two launchers match, and whether it is worth it:

1. **Gone-app launch feedback (§2.3) — trivial / already handled.** nyx already toasts on a
   gone-app launch on every path (incl. gestures); only the toast *wording* differs. Optional
   one-string change.
2. **`installedKeys` posture (§3.1) — small, nyx-local.** A decision plus a small change; the
   package-event refresh (now wired) already enables the event-driven alternative.
3. **Drawer custom names (§2.2) — a feature, not a wiring.** nyx has no app-rename store at
   all (only a `null` placeholder); the projection join is one line, but the store + rename UI
   is the actual work.
4. **Cold-start "missing" paint (§2.1) — ugly; leave it.** True symmetry needs a new nyx
   live-resolve first-paint path (fights the layout-vs-live-favorites home-model split); the
   difference is cosmetic (~150 ms, no data impact).
5. **Freshness architecture (§1.2) — converged on Option A.** nyx adopted the shared central
   holder + value-based keep-last-good (SIA-INV-5, now cross-launcher), fed by the shared pump
   (`core/SyncInstalledAppsToHolder`). No longer a divergence to align — see §1.2 / §3.1.
6. **Home model (§1.1) / folder members (§1.3) — n/a by design.** Aligning §1.1 = rewriting a
   launcher; §1.3 is already resolved (a folder is a container, not an app reference).

Rule of thumb: anything riding the *shared motor / shared rule* is cheap; anything touching an
*overlay* (custom names) or a *launcher-owned architecture* (freshness posture, home model,
cold-start rendering) is expensive — and the last few are deliberate design choices, not bugs.

---

## 0. Shared foundation (converged)

These are a single policy or a single implementation used by both launchers.

- **Installed-apps motor.** Both consume the `:common-data`
  `InstalledAppsRepositoryImpl` (the reactive `Flow<AppLoad>` motor over
  `AppEnumerator` → `LauncherAppsEnumerator`), per `SHARED_INSTALLED_APPS_SPEC`. A load
  failure is a value (`AppLoad.Failed`), never an empty list.
- **No auto-prune (Windows-shortcut model).** Neither launcher ever removes a curated
  reference against the load. kolibri's `ObserveInstalledAppsUseCase` does load →
  keep-last-good → state → emit with **no** store reconcile; nyx's
  `HomeLayoutReconciler` / `ReconcileHomeLayoutUseCase` is **structural-only** (dedup /
  folder-repair / page-trim) and takes no installed set. The old F7 deletion-gate
  apparatus (`AppPresence` / `InstallSessionInspector` / `DeletionGatePass`) is gone
  from both.
- **"missing" membership rule.** The one load-bearing clause — *an empty installed view
  means "not loaded", flag nothing; a non-empty view means an absent reference is
  missing* — lives once in `core/LazySlotMembership.isMissing(key, installed)` and is
  called by nyx `HomeCell.toCell`, kolibri `GetFavoriteAppsUseCase.processApps`, and
  kolibri `HandleSwipeActionUseCase`. Parity by construction, not by mirrored code.
- **Freshness on package events.** Both bridge `PackageUpdateReceiver` →
  `AppUpdateSignal` → `InstalledAppsRepository.triggerAppsUpdate()`, so the reactive
  loader re-enumerates on install/uninstall. (nyx gained this in the patch set; before,
  its `PackageEventCoordinator` only evicted icons + ran the now-structural reconcile,
  so the loader never refreshed on a package event.)
- **No-auto-prune regression guard.** A shared `core` test-fixtures
  `NoAutoPruneContract` with two subclasses (`KolibriNoAutoPruneTest`,
  `NyxNoAutoPruneTest`) pins that a reference absent from a non-empty view survives —
  the successor to the deleted `DeletionGateParityContract`.
- **Adapter list-diffing.** kolibri's favorites adapter already used `ListAdapter`;
  nyx's grid/dock/drawer adapters now use `DiffUtil` too, so a package-event re-render
  rebinds only changed cells instead of re-decoding every icon.

---

## 1. Intentional / inherent differences

Deliberate per-launcher designs; not defects and not targets for convergence.

### 1.1 Home model — [intentional]
- **kolibri:** a flat favorites list (`GetFavoriteAppsUseCase`), plus a top-N
  alphabetical fallback when no favorites are set. No grid, no folders, no layout
  persistence.
- **nyx:** a paged grid + dock + folders home layout, persisted as `HomeLayout`, with a
  pure `HomeLayoutReconciler` and a device-grid re-fit (`FitHomeGridUseCase`).
- **Consequence:** the whole "reconcile" concept (dedup / folder-dissolve / page-trim)
  exists **only in nyx**. kolibri has nothing to reconcile.
- **Align:** n/a by design — matching this means rewriting one launcher's home. This is
  the point of having two launchers.

### 1.2 Freshness architecture — [shared, Option A]
Now converged. Both launchers sit on the same in-RAM holder
(`InstalledAppsStateRepository`) with the value-based keep-last-good fallback
(SIA-INV-5, **now cross-launcher**), fed by the same shared pump
(`core/SyncInstalledAppsToHolder`).
- **Shared machinery:** the loader → holder feed + the empty/failed arbitration live once
  in `core/SyncInstalledAppsToHolder`. kolibri drives it via `ObserveInstalledAppsUseCase`
  (adds the `AppLoadResult` emit + the ACRA no-cache report on top); nyx drives it via an
  app-scoped `InstalledAppsHolderPump` started from `NyxApplication` (drains the outcomes,
  needs no reaction). Parity by construction, like `LazySlotMembership` for the missing rule.
- **nyx consumers now read the holder:** `GetDrawerAppsUseCase` point-reads
  `getCurrentApps()` (last-good on a transient empty — the drawer no longer blanks on a
  reload glitch); `HomeViewModel.installedKeys` maps `rawAppsFlow` (raw view, so greying
  still reflects the genuine current set, empty-guarded by `LazySlotMembership`).
- **Posture:** nyx is now an always-warm holder (the pump keeps the loader subscribed),
  the deliberate trade chosen in Option A — this also resolves the former §3.1 [open].
- **Overlays still diverge, by design:** the shared machinery ends at the raw list; each
  app applies its own overlays in its `Get*UseCase` (kolibri: favorite/hidden/customName/
  sort; nyx: hidden, no customName store yet). That is the intended seam, not a gap.

---

### 1.3 Missing folder members — [intentional]
A folder is a **container**, not an app reference, so "missing" is a property of the
things a folder *holds*, never of the folder itself. Accordingly:
- **Members** (inside the open folder overlay) are classified with the shared
  `LazySlotMembership` rule: a member whose app is gone renders greyed with a placeholder
  and, on tap, offers removal from the folder (`HomeLayoutTransition.deleteFromFolder` —
  no placement, auto-dissolve below two members). Long-press still extracts.
- **The folder container** carries no `missing` state (`HomeCell.Folder` has no such
  field) and its composite tile is never greyed — deliberately, since the folder has no
  target app to be "gone". A folder that ends up with only dead members is an
  import/edit anomaly the user clears via the members (or the structural reconciler
  dissolves once it drops below two members); it is not a container-level condition.
- **kolibri:** no analog (no home folders).
- **Align:** n/a — already resolved. The member level is closed (greyed + removable); the
  container level is intentionally neutral. Nothing to match.

---

## 2. UX asymmetries (candidates to close)

Behavioural differences a user could notice. Each is closeable but involves a
trade-off; none is currently a correctness bug.

### 2.1 Cold-start "missing" paint — [asymmetry]
- **kolibri:** since the provisional-missing change, a favorite whose app is gone is
  painted **greyed immediately** on the first frame (`buildProvisional` resolves labels
  live; a `null` resolve becomes a "missing" entry).
- **nyx:** a tile whose app is gone renders **normal** during the cold-start window
  (empty `installedKeys` → `isMissing = false`), then greys once the loader lands.
- **Why it stands:** nyx tiles come from the persisted layout classified against an
  installed set, not from a live per-favorite resolve, so there is no direct analog to
  kolibri's provisional path.
- **Align:** ugly — leave it. Closing it means giving nyx a live-resolve first paint (a new
  mechanism tied to the home model); you cannot just flip the empty-guard (that greys
  *everything* at cold start). The only cheap "symmetry" is the wrong direction — reverting
  kolibri's pop-in fix. Cosmetic (~150 ms, no data impact).

### 2.2 Drawer custom-name overlay — [asymmetry, verified]
- **kolibri:** custom names are folded into its lists (`applyCustomNames`), so a renamed
  app shows the custom name everywhere.
- **nyx:** has **no app-rename feature at all** — only a `customName: String?` field on
  `LauncherApp` that `GetDrawerAppsUseCase` sets to `null` (a documented "OVERLAY GAP").
  There is no custom-names store and no rename UI (the `rename*` code in nyx is for
  *folders*, not apps).
- **Align:** a feature, not a wiring. The projection join is one line (join the store by
  `AppInfo.key` before the sort — the sort already keys on `displayName`), but nyx first
  needs the whole feature: a custom-names DataStore store + a rename entry point. Medium.

---

### 2.3 Gone-app launch feedback — [asymmetry, wording only]
- **kolibri:** the swipe slot pre-checks the installed set at trigger time
  (`HandleSwipeActionUseCase`, via `LazySlotMembership`) and shows a specific
  "no longer installed" toast without attempting a launch.
- **nyx:** does not pre-check; any launch path (tile, gesture, folder member) attempts the
  launch and catches `AppLaunchResult.ComponentGone` → a generic "app launch failed" toast
  (`MainActivity.launchApp`). So a gone app is a clean toast on both launchers, never a
  silent no-op — this was verified in code (correcting the earlier "unaudited" note).
- **Align:** trivial — both already give feedback; only the wording differs. Optionally a
  more specific nyx string, or a nyx pre-check before launching. Not a policy gap.

---

## 3. Open / undecided

### 3.1 Warm enumeration vs. pull-on-open — [resolved: Option A]
Decided. nyx adopted the shared holder (§1.2): `InstalledAppsHolderPump` keeps the loader
subscribed for the process lifetime, so the holder is always warm and every nyx consumer
reads it. This is the always-warm end of the former trade-off, chosen deliberately over
the historical pull-on-open. The package-event refresh (`triggerAppsUpdate()`) drives the
re-enumeration that the pump then lands in the holder. (If battery telemetry ever argues
against always-warm, the fallback is to gate the pump on process-foreground rather than
revert to per-consumer pull-on-open.)

---

## 4. Verification status

The pure-Kotlin layers were compiled and exercised with a standalone `kotlinc` (the Gradle
build is unavailable in this environment — Maven Central / Google Maven are unreachable, so
`androidx` / `mockk` / `truth` / `turbine` and the `:app` Android modules cannot be built):

- **Compiled clean, with the patch set applied:** all of `:core`, `:nyx:domain` and
  `:kolibri:domain` (minus the independent backup / serialization / DI files, which need
  blocked artifacts).
- **Executed against the real compiled code:** `LazySlotMembership` (the shared rule);
  `HomeLayoutTransition.deleteFromFolder` (shrink / dissolve+promote-survivor / tile-reuse /
  no-op); and `GetFavoriteAppsUseCase.favoriteApps` for both the cold-start
  provisional-missing paint **and** the authoritative missing path (with the real project
  fakes). All green.
- **Not run here:** the JUnit / Robolectric test *files* themselves and everything in the
  `:app` modules — they need the blocked artifacts. Their behaviour was confirmed via the
  harnesses above, but the test files' own compilation / turbine sequencing is unconfirmed;
  run `./gradlew :core:test :nyx:domain:test :kolibri:domain:test …` to close that.
