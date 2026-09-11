# AUDIT-1 — nyx Code Review

Multi-Agent-Code-Review über die **nyx**-Codebase (nur `nyx/app`, `nyx/data`, `nyx/domain`).
Stand: 2026-09-11 · Version `0.1.1-dev` (versionCode 2) · Basis `main`.

**Methode:** 5 Review-Dimensionen (Korrektheit · Vereinfachung/Reuse · Effizienz · Architektur+Spec-Invarianten · Tests), jede Fund-Liste adversariell gegengeprüft (CONFIRMED / PLAUSIBLE / REJECTED). 10 Agenten, 0 verworfen — 15 CONFIRMED, 2 PLAUSIBLE.

Vorgehen laut Maintainer: **High + Medium alle angehen; Low situativ.** Status je Fund unten (`⬜ offen` / `✅ erledigt` / `➖ wontfix`).

**Übersicht:** 2 High · 10 Medium · 5 Low  (17 gesamt)


## Fix-Review (B1–B4)

Adversarieller Multi-Agent-Review gegen die umgesetzten Fixes (Diff `main..chore/nyx-audit-1`,
6 Agenten). 2 Funde, beide behoben:

- **update() ohne Contract-Test** (zu A1-03): `HomeLayoutRepositoryContract` um drei `update()`-Fälle
  erweitert (persist / null-kein-Write / transform-sieht-current) → läuft nun auf Fake **und** Impl. ✅
- **refreshDrawer-Regression** (zu A1-04): eine transiente Enumeration → `emptyList` (via `ENUMERATION_EMPTY`)
  hätte einen gefüllten Drawer beim Re-Open geleert; zusätzlich Race bei schnellem Re-Open. Fix:
  vorherigen Refresh-Job canceln (neuester gewinnt) + gefüllte Liste nie durch leere überschreiben. ✅

## Fix-Review 2 (B5)

Zweiter adversarieller Multi-Agent-Review gegen die Fixes (Diff `main..chore/nyx-audit-1`,
7 Agenten). **Keine neuen Produktions-Bugs** — 6 Funde, alle Test-Lücken bzw. latent. Behoben:

- **Atomarität (A1-03) nicht gepinnt:** die drei `update()`-Contract-Fälle waren strikt sequenziell,
  d.h. das Entfernen des `writeMutex` blieb grün. Neuer Contract-Fall `concurrent_updates_do_not_lose_writes`
  (zwei nebenläufige `update{pages+1}` mit `yield()` zwischen Read und Write, `assert == start+2`) → läuft auf
  Fake **und** Impl; per Mutations-Check verifiziert (Lock entfernt → rot). `FakeHomeLayoutRepository.update`
  hält jetzt selbst einen `Mutex`, modelliert die Atomarität also mit. ✅
- **refreshDrawer ohne Coverage (= A1-02):** neuer `HomeViewModelTest` deckt init-Populate, den Empty-Guard
  (leere Liste überschreibt gefüllte nicht / bleibt leer wenn leer), Cancel-in-flight (neuester gewinnt) und
  die Dispatch-Forwarding-Methoden ab. ✅
- **Latenter Deadlock (PLAUSIBLE):** ein Transform, der in `save()`/`update()` zurückruft, würde am
  nicht-reentranten `writeMutex` verklemmen — kein aktueller Caller tut das. KDoc-Warnung auf der
  Interface-Methode ergänzt (Transform muss rein sein, darf nicht zurückrufen). ✅
- **Rest (LOW, akzeptiert):** `update_returning_null_does_not_write` prüft „keine Emission" statt „kein Write"
  (via StateFlow-Dedup vakuum-sicher genug); ein Write-Counter auf dem Impl wäre invasiv für geringen Nutzen — nicht umgesetzt.

---

## 🔴 HIGH

### A1-01 · InstalledAppsRepositoryImpl.kt:42 — Fail-closed (RHL-INV-1) defeated: an empty getActivityList result becomes Loaded(emptyList), letting reconcile prune and persist the entire home layout

- **Schwere:** HIGH · **Verdikt:** CONFIRMED · **Kategorie:** spec-invariant · **Status:** ✅ erledigt (B1)
- **Datei:** `nyx/data/src/main/java/com/github/reygnn/nyx_launcher/data/home/InstalledAppsRepositoryImpl.kt:42`
- **Detail:** loadInstalledApps() only maps a THROWN enumeration to AppLoadResult.Error (fold.onFailure at line 43). A non-throwing empty list from getActivityList(null, myUserHandle()) succeeds runCatching and maps to Loaded(emptyList()) at line 42. ReconcileHomeLayoutUseCase (line 29-31) treats any Loaded as authoritative -> installed=emptySet -> HomeLayoutReconciler prunes every app/member (key in installed always false) -> ReconcileOutcome.Changed -> save() persists the emptied layout. PackageEventCoordinator.start()/onChanged run reconcile() on cold start and on every package remove/change. This is exactly the anti-pattern the AppLoadResult KDoc (lines 3-9) and the repo impl KDoc (lines 18-19, 'never a silent empty list (RHL-INV-1)') were written to prevent.
- **Fix-Vorschlag:** Treat an empty enumeration as AppLoadResult.Error (a real device always exposes >= 1 launchable activity), keeping only a non-empty result as Loaded, to restore the fail-closed guarantee.
- **Verify-Notiz:** Read the file: line 42 fold.onSuccess wraps the mapped list unconditionally; an empty-but-successful getActivityList yields Loaded(emptyList()). Traced through ReconcileHomeLayoutUseCase.kt:29-38 (Loaded branch reconciles+saves) and HomeLayoutReconciler.kt:44-55,121-126 (empty installed prunes all, returns Changed). The impl's own KDoc and AppLoadResult KDoc explicitly promise this cannot happen, so the unguarded empty path is a genuine hole. Persisted data loss justifies high; the only reason it isn't certain-in-practice is that a non-throwing empty enumeration is an edge case.

### A1-02 · HomeViewModel.kt:35 — HomeViewModel has no test despite being the app's central StateFlow state holder.

- **Schwere:** HIGH · **Verdikt:** CONFIRMED · **Kategorie:** test-coverage · **Status:** ✅ erledigt (B5)
- **Datei:** `nyx/app/src/main/java/com/github/reygnn/nyx_launcher/home/HomeViewModel.kt:35`
- **Detail:** Confirmed: grep for HomeViewModel across all test sources returns nothing. The class has real, silently-breakable logic: init-block drawer load (viewModelScope.launch { _drawerApps.value = getDrawerApps() }, line 57), layout/monochromeIcons StateFlows via stateIn(WhileSubscribed(5_000), lines 47-51), and six mutation dispatch methods (move/place/extractFromFolder/remove/renameFolder/applyDeviceGrid, lines 60-87) each forwarding args to a use case. All fakes (FakeHomeLayoutRepository, FakePreferencesRepository, FakeInstalledAppsRepository) plus MainDispatcherRule exist in :domain testFixtures, so it is directly JVM/Robolectric-testable with the real use cases. CLAUDE.md explicitly calls state holders the thing 'worth pinning'.
- **Fix-Vorschlag:** Add HomeViewModelTest with MainDispatcherRule + runTest(rule.dispatcher) + Turbine: assert drawerApps populates on init and each fun forwards the right id/target/title to its use case and layout re-emits.
- **Verify-Notiz:** Read HomeViewModel.kt in full; confirmed init drawer load, WhileSubscribed StateFlows and all six dispatch methods exist exactly as described. grep confirms zero test references. Fixtures + MainDispatcherRule confirmed present. Central state holder — high severity defensible.

## 🟠 MEDIUM

### A1-03 · HomeLayoutRepositoryImpl.kt:32 — Non-atomic read-modify-write on the shared HomeLayout allows lost updates between concurrent writers

- **Schwere:** MEDIUM · **Verdikt:** CONFIRMED · **Kategorie:** correctness · **Status:** ✅ erledigt (B2)
- **Datei:** `nyx/data/src/main/java/com/github/reygnn/nyx_launcher/data/home/HomeLayoutRepositoryImpl.kt:32`
- **Detail:** save() at HomeLayoutRepositoryImpl.kt:32-35 does dataStore.edit { it[KEY] = raw } with raw computed OUTSIDE the transaction; every mutating use case reads repository.layout().first() then repository.save(...). Grep confirms no Mutex and no DataStore updateData anywhere in the use cases. PackageEventCoordinator.kt:40 runs reconcile() on its own independent CoroutineScope(SupervisorJob()+IoDispatcher) at cold start (:68) and every package add/change/remove (:79), while user edits run on viewModelScope->DefaultDispatcher. Two read-modify-write cycles reading the same v0 and both writing -> last writer wins, the other operation silently lost (e.g. a background package-change reconcile reads a just-committed user move as v0 and overwrites it, with no re-trigger to heal).
- **Fix-Vorschlag:** Serialize all layout mutations through a single Mutex, or use DataStore's transactional updateData so read+transform+write is atomic. Pure transitions return NoOp on no-change, so a compare-and-set retry loop composes cleanly.
- **Verify-Notiz:** Read HomeLayoutRepositoryImpl.kt:32-35: save() serializes and writes a value computed before the edit block. Grep across usecase/ shows the layout().first()+save() pattern in Move/Place/RemoveFromFolder/RemoveItem/RenameFolder/FitHomeGrid/Reconcile with no Mutex/updateData. PackageEventCoordinator.kt:40 has its own scope+dispatcher and launches reconcile independently at :68/:79. DataStore.edit is atomic per-write but the read is outside it, so interleaved RMW loses updates. Genuine lost-update race.

### A1-04 · HomeViewModel.kt:57 — Drawer app list is loaded once in init and never refreshed, contradicting PackageEventCoordinator's 'drawer re-queries on next open' comment

- **Schwere:** MEDIUM · **Verdikt:** CONFIRMED · **Kategorie:** correctness · **Status:** ✅ erledigt (B3)
- **Datei:** `nyx/app/src/main/java/com/github/reygnn/nyx_launcher/home/HomeViewModel.kt:57`
- **Detail:** HomeViewModel.init loads drawerApps exactly once (line 57: _drawerApps.value = getDrawerApps()) from the one-shot suspend GetDrawerAppsUseCase (returns List, not a Flow). There is no refresh method. AppDrawerFragment only collects the StateFlow (line 90) with no re-query trigger on open (onAddToHome is empty; fragment stays added via visibility toggling). PackageEventCoordinator.onPackageAdded (line 51) is a deliberate no-op justified by the comment (line 50) 'the drawer re-queries on next open' -- which no code implements. Since HomeViewModel is activityViewModels()-scoped to the resident launcher activity, the drawer stays stale until process death: newly installed apps never appear, uninstalled apps linger and fail to launch.
- **Fix-Vorschlag:** Refresh drawerApps on package add/remove (wire PackageEventCoordinator to the ViewModel) or expose an observing Flow from InstalledAppsRepository / re-query on drawer open; fix the PackageEventCoordinator comment to match.
- **Verify-Notiz:** HomeViewModel.kt:53-58 sets _drawerApps once in init from a one-shot suspend (GetDrawerAppsUseCase.kt:21 returns List<LauncherApp>, not Flow). AppDrawerFragment.kt:88-94 only collects the StateFlow, no re-query. PackageEventCoordinator.kt:49-51 onPackageAdded=Unit with the 're-queries on next open' comment that no code backs. Staleness and comment-vs-code drift both confirmed; long-lived because the ViewModel is activity-scoped on a resident launcher. Medium is fair for a launcher UX bug.

### A1-05 · AppDrawerAdapter.kt:26 — AppDrawerAdapter carries onAddToHome + itemLayout params/branches its only call site never exercises

- **Schwere:** MEDIUM · **Verdikt:** CONFIRMED · **Kategorie:** dead-code · **Status:** ✅ erledigt (bereits mit B4/A1-08: `onAddToHome`+`itemLayout` entfernt, `onItemLongPress` verpflichtend, `item_app_grid` hart, `item_app_row.xml` gelöscht)
- **Datei:** `nyx/app/src/main/java/com/github/reygnn/nyx_launcher/home/drawer/AppDrawerAdapter.kt:26`
- **Detail:** onAddToHome (line 26) is never invoked; the fallback at line 52 is unreachable because onItemLongPress is always non-null. itemLayout default (line 28) is unused and item_app_row.xml is otherwise unreferenced (dead resource).
- **Fix-Vorschlag:** Make onItemLongPress a required (view, app)->Unit, drop onAddToHome and the else fallback, pass item_app_grid directly, delete item_app_row.xml.
- **Verify-Notiz:** Confirmed AppDrawerAdapter has exactly one instantiation (AppDrawerFragment.kt:76-84). That site passes onAddToHome = { } (empty), a non-null onItemLongPress, and itemLayout = R.layout.item_app_grid. So the else-branch `else onAddToHome(app)` (line 52) is unreachable (onItemLongPress is always non-null), and the itemLayout default R.layout.item_app_row (line 28) is never used. grep shows item_app_row is referenced ONLY at line 28 (the default) — the only other hits are under build/ intermediates — so item_app_row.xml is a dead layout. All claims hold. Severity medium is slightly generous for pure dead code but defensible.

### A1-06 · HomeGridAdapter.kt:86 — Token-gated async icon load (ICL-INV-9) duplicated verbatim across four adapters

- **Schwere:** MEDIUM · **Verdikt:** CONFIRMED · **Kategorie:** duplication · **Status:** ✅ erledigt (B7 — Token-Load war schon via B4/A1-09 dedupliziert; jetzt auch der geteilte App/Folder-when-Block über `bindLaunchableCell`)
- **Datei:** `nyx/app/src/main/java/com/github/reygnn/nyx_launcher/home/HomeGridAdapter.kt:86`
- **Detail:** Same token-gated load in HomeGridAdapter/DockAdapter/FolderMemberAdapter/AppDrawerAdapter, plus a duplicated App/Folder when-branch between grid and dock adapters.
- **Fix-Vorschlag:** Extract a shared ImageView.loadIconGated helper (or a base ViewHolder holding bindToken + a bindCell extension) and route all four adapters through it.
- **Verify-Notiz:** Verified the stale-binding token pattern (val token = ++holder.bindToken; icon.setImageDrawable(null); scope.launch { runCatching{...}.getOrNull() ?: return; if (bindToken==token) setImageBitmap } + onViewRecycled { bindToken++; setImageDrawable(null) }) appears in HomeGridAdapter (62-117), DockAdapter (44-76), FolderMemberAdapter (41-55) and AppDrawerAdapter (56-69). HomeGridAdapter (86-111) and DockAdapter (50-70) additionally share the near-identical when(cell){App->onLaunch+longpress+iconLoader.bitmap(System(key)); Folder->onOpenFolder+longpress+folderRenderer.render(members)} block. Four copies of the same invariant is a real maintenance hazard; claim holds.

### A1-07 · IconLoaderImpl.kt:121 — Disk icon cache has no size/age bound or pruning — it only shrinks on evict(pkg)

- **Schwere:** MEDIUM · **Verdikt:** CONFIRMED · **Kategorie:** icon-cache · **Status:** ⬜ offen
- **Datei:** `nyx/data/src/main/java/com/github/reygnn/nyx_launcher/data/icon/IconLoaderImpl.kt:121`
- **Detail:** Verified against ICON_LOADER_SPEC §5 line 143: 'Byte-/Alters-beschränkt (LRU per mtime), Pruning lazy im Io-Kontext.' The implementation never prunes by size/age: writeDisk (125-130) writes a WEBP_LOSSLESS file for every (package, sizePx, variant, contentHash) requested via loadFromDiskOrResolve (line 121), and the only deletion path is evict(pkg)'s prefix glob (line 102). No listFiles/mtime/size sweep exists anywhere in the class. Distinct icon sizes (grid, folder-cell, drawer 48dp), the THEMED monochrome variant, and stale content-hash files for never-evicted packages accumulate unbounded until the OS clears cacheDir under storage pressure.
- **Fix-Vorschlag:** Implement the §5 lazy mtime-LRU prune (cap total bytes / max age on the IO dispatcher after writes) or at minimum a periodic size-bounded sweep.
- **Verify-Notiz:** Spec §5 explicitly mandates a byte/age-bounded mtime-LRU disk prune; code has none — only evict(pkg) glob deletion. Confirmed direct spec violation. Not catastrophic (OS reclaims cacheDir) so medium is fair. The unbounded WEBP_LOSSLESS writes accumulate across sizes/variants/content-hashes.

### A1-08 · HomeGridAdapter.kt:50 — Every home-layout mutation triggers a full notifyDataSetChanged rebind of all grid pages, cells and the dock

- **Schwere:** MEDIUM · **Verdikt:** CONFIRMED · **Kategorie:** recyclerview-bind-cost · **Status:** ✅ erledigt (B4)
- **Datei:** `nyx/app/src/main/java/com/github/reygnn/nyx_launcher/home/HomeGridAdapter.kt:50`
- **Detail:** Verified: HomeGridAdapter.submit() (line 48-51), HomePagerAdapter.submit() (line 33-36) and DockAdapter.submit() (line 30-32) all call notifyDataSetChanged(). MainActivity.renderLayout() (line 231) runs on every emission of viewModel.layout (collected at line 124) and unconditionally calls pagerAdapter?.submit(...) (line 254) and dockAdapter.submit(...) (line 256). Each mutation therefore rebinds every visible cell: onBindViewHolder nulls the ImageView (line 63), sets two fresh click-listener lambdas (94-95), and launches a new coroutine into iconLoader.bitmap (96-100). HomePagerAdapter.onBindViewHolder re-submits each page's cells (line 59), cascading a full grid rebind per page. Icons are memory-cached so fetches are cheap, but per-mutation coroutine fan-out, view teardown, and loss of item animations are avoidable.
- **Fix-Vorschlag:** Back the cell/dock/page adapters with DiffUtil (ListAdapter or notifyItem* diffing on HomeCell identity) so a single move updates only the affected positions.
- **Verify-Notiz:** Confirmed all three submit() methods call notifyDataSetChanged; renderLayout collects the layout StateFlow and unconditionally re-submits on every emission (MainActivity 124/254/256). Real avoidable churn, not a correctness bug — severity medium is appropriate. Cited line 50 is exact.

### A1-09 · HomeLayoutTransition.kt:21 — Specced silentError fail-loud path for programmer-error inputs is entirely unimplemented; the KDoc claims the use-case fires it but none does

- **Schwere:** MEDIUM · **Verdikt:** CONFIRMED · **Kategorie:** spec-invariant · **Status:** ✅ erledigt (B4)
- **Datei:** `nyx/domain/src/main/java/com/github/reygnn/nyx_launcher/home/transition/HomeLayoutTransition.kt:21`
- **Detail:** The transition collapses structurally-impossible inputs to a plain NoOp indistinguishable from a legitimate NoOp: move() unknown id (line 37), app already in target folder (line 91), removeFromFolder non-folder/member-absent (lines 132-133), place() folder-member (line 166), remove() unknown id (line 174), renameFolder non-folder (line 179). The KDoc (lines 21-22) asserts the use-case fires silentError, but grep across nyx shows silentError appears only in this comment and in SettingsActivity.kt:104 -- never in MoveItemUseCase/RemoveFromFolderUseCase/PlaceItemUseCase/RemoveItemUseCase/RenameFolderUseCase. The specs mandate it (MOVE_ITEM_SPEC MIU-INV-2, REMOVE_FROM_FOLDER_SPEC RFF-INV-4, HOME_EDIT_USECASES_SPEC id-unknown/non-folder, ICON_HOME_MODEL_SPEC IHM-INV-7 'silentError, nicht NoOp'). Because the transition returns the same NoOp for a bug as for a real no-op, the use-case cannot distinguish them.
- **Fix-Vorschlag:** Add a distinct programmer-error signal from the transition branches and fire TimberWrapper.silentError in the use-cases (or at those branches), then reconcile the KDoc with actual behavior.
- **Verify-Notiz:** grep confirms silentError only at HomeLayoutTransition.kt:21 (KDoc) and SettingsActivity.kt:104; no domain use case fires it. Read MoveItemUseCase.kt: it just calls the transition and saves on change, no silentError, no way to tell a programmer-error NoOp from a real one. Spec grep confirms MIU-INV-2, RFF-INV-4, HEU (lines 96/138), IHM-INV-7 all require silentError explicitly 'not NoOp'. KDoc-vs-code drift and unimplemented invariant both real. Medium is right: diagnostic/defensive gap, not user data corruption.

### A1-10 · DragLayer.kt:50 — DragLayer.dispatchTouchEvent (DRG-INV-1 capture + mid-drag gesture gating) is untested.

- **Schwere:** MEDIUM · **Verdikt:** CONFIRMED · **Kategorie:** test-coverage · **Status:** ⬜ offen
- **Datei:** `nyx/app/src/main/java/com/github/reygnn/nyx_launcher/home/drag/DragLayer.kt:50`
- **Detail:** Confirmed: dispatchTouchEvent (lines 50-62) holds the regime switch — while dragController.isDragging it consumes the stream (returns true, routes MOVE->onMove, UP->onDrop, CANCEL->onCancel) and never consults gestureCore; while idle it delegates to gestureCore.dispatch. DragControllerTest covers only the controller behind the DragViewHost seam, not the DragLayer switch itself. A refactor that consulted gestureCore mid-drag or dropped the return-true would ship silently. Testability nuance: dragController is an inline non-injectable val, and isDragging only flips via startDrag -> addDragView -> source.drawToBitmap(), which needs a measured/laid-out source View (drawToBitmap throws on a 0-size view). This is achievable under Robolectric but is more involved than simply 'toggling isDragging'.
- **Fix-Vorschlag:** Add a Robolectric DragLayerTest dispatching DOWN/MOVE/UP/CANCEL in idle and dragging states (driving a laid-out source through startDrag), asserting capture-and-forward while dragging and gesture delegation while idle.
- **Verify-Notiz:** Read DragLayer.kt and DragController.kt; the untested regime-switch is real and matches the description. Testable claim slightly optimistic (isDragging can't be toggled directly — must go through startDrag->drawToBitmap on a sized view) but still Robolectric-feasible. Gap and severity stand.

### A1-11 · InstalledAppsRepositoryImpl.kt:29 — InstalledAppsRepositoryImpl untested; RHL-INV-1 error-wrapping path has no coverage.

- **Schwere:** MEDIUM · **Verdikt:** CONFIRMED · **Kategorie:** test-coverage · **Status:** ✅ erledigt (B1)
- **Datei:** `nyx/data/src/main/java/com/github/reygnn/nyx_launcher/data/home/InstalledAppsRepositoryImpl.kt:29`
- **Detail:** Confirmed: no InstalledAppsRepositoryImplTest exists and there is no InstalledAppsRepository contract (only FakeInstalledAppsRepository). loadInstalledApps (lines 29-45) wraps getActivityList in runCatching{...}.fold(onSuccess=Loaded, onFailure=Error(ENUMERATION_FAILED)) — the exact RHL-INV-1 behavior its KDoc claims. If onFailure were changed to Loaded(emptyList()), reconcile would prune every home item as uninstalled and nothing catches it. Testable via Robolectric with a mocked LauncherApps (the service is obtained in the constructor via context.getSystemService, so the test mocks Context to return a MockK LauncherApps).
- **Fix-Vorschlag:** Add a Robolectric test: getActivityList throws -> assert AppLoadResult.Error(ENUMERATION_FAILED); returns activities -> assert mapped LauncherApp list.
- **Verify-Notiz:** Read InstalledAppsRepositoryImpl.kt; runCatching/fold error-wrapping is exactly as described. No impl test and no contract confirmed via file listing + grep. Constructor-time getSystemService makes the mock slightly more work but the path is coverable.

### A1-12 · FitHomeGridUseCase.kt:30 — FitHomeGridUseCase untested despite read-once/save-only-on-change logic run every layout pass.

- **Schwere:** MEDIUM · **Verdikt:** CONFIRMED · **Kategorie:** test-coverage · **Status:** ✅ erledigt (B6)
- **Datei:** `nyx/domain/src/main/java/com/github/reygnn/nyx_launcher/home/usecase/FitHomeGridUseCase.kt:30`
- **Detail:** Confirmed: grep in test/testFixtures returns nothing. invoke (lines 27-33) reads layout().first(), delegates to HomeLayoutRegridder.fit, and saves only on RegridOutcome.Changed (no-op on Unchanged). Per its KDoc it runs on every MainActivity layout, so dropping the Unchanged branch would cause a persist storm on every layout/orientation event. HomeLayoutRegridder itself is tested but the use case's save-gating is not. FakeHomeLayoutRepository exposes saveCount (verified) and MoveItemUseCaseTest already has a noop_does_not_save asserting saveCount==0 — the exact pattern to mirror. Pure JVM testable.
- **Fix-Vorschlag:** Add FitHomeGridUseCaseTest: (a) already-matching grid -> saveCount==0, (b) differing grid -> saves the regridded layout exactly once.
- **Verify-Notiz:** Read FitHomeGridUseCase.kt (Unchanged->Unit, Changed->save) and confirmed FakeHomeLayoutRepository.saveCount + MoveItemUseCaseTest.noop_does_not_save exist, so the suggested test is directly implementable. Real, actionable gap.

## 🟡 LOW

### A1-13 · HomeLayoutTransition.kt:114 — Dragging an existing dock item and dropping past the last dock icon is rejected as OFF_GRID

- **Schwere:** LOW · **Verdikt:** CONFIRMED · **Kategorie:** correctness · **Status:** ⬜ offen
- **Datei:** `nyx/domain/src/main/java/com/github/reygnn/nyx_launcher/home/transition/HomeLayoutTransition.kt:114`
- **Detail:** MainActivity.kt:187-191 sets slot=dockSize (=layout.dock.size, set at :233, includes the still-persisted dragged item) when the finger misses any dock child (trailing empty area). In moveToDock (HomeLayoutTransition.kt:109-120) dockWithoutSource excludes the source, so for an already-in-dock source its size is dockSize-1; the guard index > dockWithoutSource.size at line 114 becomes dockSize > dockSize-1 -> true -> Rejected(OFF_GRID). The same slot=dockSize correctly appends a grid-origin item (dockWithoutSource = full dock, dockSize > dockSize is false). So the 'drag a dock icon to the end of the dock' gesture is silently a no-op; paths are inconsistent. Lossless.
- **Fix-Vorschlag:** In the dock zone onDrop clamp the append index for an in-dock source (treat slot >= dockWithoutSource.size as append), or have moveToDock treat index == source-inclusive dock size as append rather than OFF_GRID.
- **Verify-Notiz:** MainActivity.kt:189-190: slot = child adapter position or dockSize when child is null (finger past last icon). dockSize=layout.dock.size at :233 includes the dragged item. moveToDock: currentDockIndex (0..dockSize-1) != dockSize so no NoOp; dockWithoutSource.size=dockSize-1; index=dockSize>dockSize-1 -> OFF_GRID at line 114-115. Grid-origin source at same slot appends fine. Confirmed off-by-one; narrow trailing-empty-area gesture, low severity, item stays put.

### A1-14 · MoveItemUseCase.kt:26 — Five edit use-cases repeat the identical read-transform-save-on-change IO shell

- **Schwere:** LOW · **Verdikt:** CONFIRMED · **Kategorie:** duplication · **Status:** ⬜ offen
- **Datei:** `nyx/domain/src/main/java/com/github/reygnn/nyx_launcher/home/usecase/MoveItemUseCase.kt:26`
- **Detail:** All five use-cases share the same read-once/transform/save-only-on-change/dispatcher-hop body, differing only in which transition they call.
- **Fix-Vorschlag:** Add a shared inline runLayoutEdit(dispatcher){ current -> R } helper and reduce each use-case body to a single transition call.
- **Verify-Notiz:** Read all five: MoveItemUseCase (27-32), PlaceItemUseCase (26-31), RemoveFromFolderUseCase (30-35), RemoveItemUseCase (18-21), RenameFolderUseCase (18-21). Each is withContext(dispatcher){ current = repository.layout().first(); result = HomeLayoutTransition.xxx(...); result.layout?.let { repository.save(it) }; result }. The last two use .also{} form but are structurally identical. Result types (MoveResult/FolderEditResult/LayoutEdit) all expose layout: HomeLayout? with null<=>no-change, so the save-on-change step is genuinely uniform. Duplication is real; extraction is a legitimate low-severity simplification.

### A1-15 · IconLoaderImpl.kt:137 — removeFromIndex scans the entire package index per evicted key instead of using the key's package

- **Schwere:** LOW · **Verdikt:** CONFIRMED · **Kategorie:** icon-cache · **Status:** ⬜ offen
- **Datei:** `nyx/data/src/main/java/com/github/reygnn/nyx_launcher/data/icon/IconLoaderImpl.kt:137`
- **Detail:** Verified: removeFromIndex (137-144) iterates over every packageIndex entry and every CacheKey in each entry's set per evicted key. Called per-key from the bitmap() eviction loop (86-89) and from trim() (108-111), all under the lock monitor — O(evicted × totalKeys). Total keys is bounded by the memory byte-budget (4-64MB) so realistically tens-to-hundreds of entries, making this minor. NOTE: the finding's suggestion to use pkgOf(ref) is partly wrong — an insertion for one package can evict LRU keys belonging to OTHER packages, so pkgOf(ref) is not the evicted key's package. The valid fix is a reverse CacheKey->pkg map or resolving the package from the key's encoded prefix.
- **Fix-Vorschlag:** Maintain a reverse CacheKey->pkg lookup (or parse the package from the key prefix) so eviction removes from exactly one packageIndex set instead of scanning the whole index.
- **Verify-Notiz:** The O(total keys) scan under the lock is real (137-144, called from 88 and 110). Severity low is correct — index size is memory-budget-bounded and cascades are rare. Flagged that the finding's pkgOf(ref) suggestion is flawed since evicted keys may belong to other packages; a reverse map / key-prefix parse is the sound fix.

### A1-16 · FakePreferencesRepository.kt:1 — PreferencesRepository/InstalledAppsRepository lack the shared contract-test triple HomeLayoutRepository has.

- **Schwere:** LOW · **Verdikt:** PLAUSIBLE · **Kategorie:** test-coverage · **Status:** ⬜ offen
- **Datei:** `nyx/domain/src/testFixtures/java/com/github/reygnn/nyx_launcher/home/repository/FakePreferencesRepository.kt:1`
- **Detail:** Facts confirmed: HomeLayoutRepository has the full triple (HomeLayoutRepositoryContract abstract + FakeHomeLayoutRepositoryContractTest + HomeLayoutRepositoryImplContractTest), pinning Fake and impl to the same behavior. PreferencesRepository has only FakePreferencesRepository + a plain PreferencesRepositoryImplTest (not a contract extension, verified — it constructs PreferencesRepositoryImpl(FakeDataStore()) directly), so the Fake is never checked against the same assertions as the impl. InstalledAppsRepository has only a Fake, no impl test at all. Whether these repos are *meant* to follow the triple pattern is debatable — the reviewer itself hedges, and it is plausibly intentional that only the serialization/DataStore-backed HomeLayoutRepository got the full contract while a single-boolean flow got a simpler test. More of a convention observation than a definite defect.
- **Fix-Vorschlag:** If following the HomeLayoutRepository pattern, extract PreferencesRepositoryContract (and InstalledAppsRepositoryContract) run against both Fake and impl.
- **Verify-Notiz:** All cited facts verified: the triple exists only for HomeLayoutRepository; PreferencesRepositoryImplTest is a plain test not a contract; InstalledAppsRepository has no impl test. But 'this is a gap' is a judgment call — the reviewer hedges ('if meant to follow') and limited-scoping the trivial repos may be intentional. Facts correct, issue-status uncertain -> PLAUSIBLE, low.

### A1-17 · HomeLayout.kt:45 — PlacedItem.span is threaded through model/DTO/mappers/regridder but never set non-default

- **Schwere:** LOW · **Verdikt:** PLAUSIBLE · **Kategorie:** unused-abstraction · **Status:** ⬜ offen
- **Datei:** `nyx/domain/src/main/java/com/github/reygnn/nyx_launcher/home/model/HomeLayout.kt:45`
- **Detail:** span defaults to Span() and no path constructs a non-default span; the concept spreads across model, DTO (spanW/spanH), mappers, and the regridder relocation queue for a multi-cell feature that doesn't exist yet.
- **Fix-Vorschlag:** If multi-cell items aren't imminent, drop span from the domain model + regridder (keep only DTO defaults for forward-compat) or flag it as reserved; if imminent, no change needed.
- **Verify-Notiz:** Factual claim verified: every production PlacedItem construction uses PlacedItem(item, pos) with the default Span() — HomeLayoutTransition.kt:76,84,241,249 and the regridder line 75 (PlacedItem(item, cell, span)) only carries forward a span that itself originated as default. Every Span( construction is default: Span() (regridder:57), Span(spanW, spanH) (mapper:52) where the DTO defaults spanW/spanH=1. So span is never non-default anywhere. However this is documented, spec-driven forward-compat (PlacedItem KDoc 'pinned to a grid cell with a span'; DTO field order follows ICON_HOME_MODEL_SPEC §2.3), so whether it's an 'issue' vs deliberate reserved surface is a judgment call — hence PLAUSIBLE rather than CONFIRMED. Severity low is right.

