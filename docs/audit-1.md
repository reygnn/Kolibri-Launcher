# Audit 1 — `feature/shared-installed-apps`

**Date:** 2026-09-24
**Branch:** `feature/shared-installed-apps`
**Base (merge-base with `main`):** `e05e4ff74ae92b261114b75b4afc34849578c4d2`
**Scope:** the ~180-file change that introduces a shared installed-apps
enumerator/state layer in `:core` + `:common-data` and migrates the `kolibri`
and `nyx` launchers off their old local installed-apps clusters onto it.

## Method

Two-stage multi-agent review, all agents on Opus 4.8:

1. **Review** — 3 loops × 4 topical agents (12 total) over the branch diff:
   Loop 1 correctness & concurrency, Loop 2 design/reuse/simplification,
   Loop 3 robustness/tests/regressions. Produced 19 raw findings that collapse
   to **9 distinct issues** (many were the same issue seen by several loops).
2. **Verify** — 1 adversarial skeptic per distinct finding (9 total), each
   instructed to *refute* by reading the actual code and running `git`.

The concurrency/races agent found **nothing** — the shared enumerator's
thread-safety held up under review.

## Verdict summary

| ID | Finding | Filed | Verified severity | Verdict |
|----|---------|-------|-------------------|---------|
| F1 | `PackageUpdateReceiverTest` uses wrong test dispatcher | high | **medium** | CONFIRMED |
| F2 | Shared motor `InstalledAppsRepositoryImpl` untested + false ADR marker | high | **high** | CONFIRMED |
| F3 | Enumeration parity androidTest cannot pass (no `QUERY_ALL_PACKAGES`) | medium | **medium** | CONFIRMED |
| F4 | `sortedByDisplayName` duplicated; `:core` copy dead in production | medium | **low** | CONFIRMED |
| F5 | `RefreshAppsUseCase` duplicated across `:core` and `kolibri` | medium | **low** | CONFIRMED |
| F6 | `ReconcileResult.SkipReason` KDoc describes obsolete StateFlow prime-window | medium | **low** | CONFIRMED |
| F7 | Nyx reconcile may prune home layout on a partial snapshot | low | **low** | CONFIRMED (reachability unproven) |
| F8 | `GetInstalledAppsUseCase` KDoc claims a sort that no longer happens | low | **low** | PARTIALLY_CONFIRMED |
| F9 | `PackageEventCoordinator.refreshApps()` is dead/redundant per-event work | low | **low** | CONFIRMED |

**Totals:** 8 CONFIRMED, 1 PARTIALLY_CONFIRMED, 0 REFUTED.
One functional (F7) issue; one real test-coverage gap (F2); everything else is
test-hygiene (F1, F3), dead code / incomplete unification (F4, F5, F9) or stale
docs (F6, F8).

---

## High

### F2 — Shared motor has zero tests, yet the ADR marker claims one exists · CONFIRMED (high)

**Files:** `core/src/main/java/com/github/reygnn/launcher/core/InstalledApps.kt:21-24`,
`common-data/src/main/java/com/github/reygnn/launcher/common/data/installedapps/InstalledAppsRepositoryImpl.kt`

The ADR marker reads: *"it keeps its historical `NO CONTRACT TEST (ADR)` marker
(the enumerator seam is verified by an androidTest against the platform, plus a
JVM test of the fail-closed/debounce motor)."* That JVM motor test does not
exist on the branch:

- `grep -rn "InstalledAppsRepositoryImpl(" --include=*.kt .` → **zero** hits (nothing constructs the class).
- `find . -name 'InstalledAppsRepositoryImplTest*'` → nothing.
- `grep -rn "reloadTriggers"` → only the production file
  (`InstalledAppsRepositoryImpl.kt:45,79,115`); the `@VisibleForTesting internal
  fun reloadTriggers` seam (lines 114-119), which exists precisely to be pinned
  on a virtual-time dispatcher, has no test referencing it.
- The `:common-data` test tree has only `InstalledAppsStateRepositoryImplContractTest`
  (stage-2 holder), `LauncherAppsEnumeratorTest` (enumerator seam) and
  `PackageUpdateReceiverTest` — none exercises the stage-1 motor
  (debounce/prime/fail-closed catch at `InstalledAppsRepositoryImpl.kt:79-104,135-161`).

The old test was **deleted, not migrated**:
`git diff <base>...HEAD --stat -- '*InstalledAppsRepositoryImplTest*'` shows
`kolibri/data/.../InstalledAppsRepositoryImplTest.kt` (-272) and
`nyx/data/.../home/InstalledAppsRepositoryImplTest.kt` (-70), 342 deletions, no
additions. The deleted kolibri test contained exactly the motor guards the
marker claims: `reloadTriggers primes immediately and is not delayed by the
window` (DBNC-INV-1), `reloadTriggers coalesces a burst of triggers into one
reload` (DBNC-INV-4), `triggerAppsUpdate - when flow emit fails - does not
crash`.

**Impact:** a refactor that breaks the debounce coalescing, drops the priming
emit, or collapses `Failed` into `Loaded(empty)` (SIA-INV-2) would be caught by
nothing, and the ADR exemption is self-justifying on a test that isn't there.

**Recommended fix:** port the deleted kolibri test into
`common-data/src/test/.../installedapps/`, adapted to the shared motor:
(1) `reloadTriggers` priming-at-t0 + burst-coalescing on a virtual-time
dispatcher via `MainDispatcherRule` / `runTest(rule.dispatcher)`;
(2) `loadFromEnumerator` mapping `enumerate()` throw → `AppLoad.Failed` and empty
→ `Loaded(emptyList())` (SIA-INV-2 / §9.2); (3) `triggerAppsUpdate` emit-failure
safety. Until then, correct the `InstalledApps.kt` marker so it no longer claims
a JVM motor test exists.

---

## Medium

### F1 — `PackageUpdateReceiverTest`: three tests use the wrong test dispatcher · CONFIRMED (medium)

**File:** `common-data/src/test/java/com/github/reygnn/launcher/common/data/installedapps/PackageUpdateReceiverTest.kt:90,117,188`

The three tests use plain `runTest { … advanceUntilIdle() … assertTrue(finishCalled) }`
(assertions at lines 101-103, 130-132, 199-201). But:

- `PackageUpdateReceiver.kt:106` launches on `CoroutineScope(SupervisorJob() + Dispatchers.Main)`
  (`.launch { withTimeout(...) { processPackageUpdate(...) } }`), and `finishCalled`
  is set **only** inside `processPackageUpdate`'s `finally { safeOnFinish(onFinish) }`
  (lines 166-168) — i.e. only from within the launched coroutine.
- The rule is `MainDispatcherRuleBase(StandardTestDispatcher())` (line 36), which
  `Dispatchers.setMain(testDispatcher)` — a scheduler distinct from the one plain
  `runTest {}` creates. `advanceUntilIdle()` therefore advances the *runTest*
  scheduler while the coroutine sits on the *rule's* Main scheduler, which is
  never advanced.

**Impact:** test-only (no production path broken). When actually run, the three
assertions **fail outright** (assertTrue on a still-false `finishCalled`) — this
is not a vacuous pass. They were committed staged-not-run (commit `0b093bd`), so
the failure was never observed. The three non-`runTest` tests (null-action :70,
irrelevant-action :80, replace-skip :136) finish synchronously and are correct.

**Recommended fix:** change the three signatures from `= runTest {` to
`= runTest(mainDispatcherRule.dispatcher) {` (family convention:
`runTest(rule.dispatcher)` is non-negotiable when touching `Dispatchers.Main`).

### F3 — Enumeration parity androidTest cannot pass as written · CONFIRMED (medium)

**File:** `common-data/src/androidTest/java/com/github/reygnn/launcher/common/data/installedapps/InstalledAppsEnumerationParityTest.kt`

The test builds `pm` via `packageManager.queryIntentActivities(MAIN+LAUNCHER)`
(L56-63) and `la` via `launcherApps.getActivityList(null, myUserHandle())`
(L67-70), then asserts `onlyInPm.isEmpty() && onlyInLa.isEmpty()` (L82-94) with
`KNOWN_DIVERGENCE = emptySet()` (L104). But `:common-data` has **no** source or
androidTest manifest granting `QUERY_ALL_PACKAGES` and no MAIN/LAUNCHER
`<queries>` block (grep confirms `QUERY_ALL_PACKAGES` exists only in
`kolibri/app` and a scoped `nyx/app` `<queries>`). On Android 11+/targetSdk 37,
`queryIntentActivities` is package-visibility-filtered while `getActivityList`
is not, so `la` = the full launchable set while `pm` = only the test app's own
declared LAUNCHER activities → `onlyInLa` non-empty → the parity assertion
always fails.

**Impact:** the guard that is supposed to catch a real enumeration-set
divergence between the retired PM path and the new LauncherApps path is
undeployable as designed. A maintainer following the KDoc ("run once, capture
differences in `KNOWN_DIVERGENCE`") would dump the entire device app list into
`KNOWN_DIVERGENCE`, permanently neutering it. (Currently no CI breakage — it is
a staged/not-run skeleton, commit `0b093bd`.)

**Recommended fix:** add `common-data/src/androidTest/AndroidManifest.xml` with
`<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>` (matches
`kolibri/app`) **or** a MAIN/LAUNCHER `<queries>` block, so both APIs enumerate
the same set before parity is asserted. Since the PM path is retired, also
reconsider whether this guard earns its keep; if kept, fix the KDoc/`KNOWN_DIVERGENCE`
framing (without the grant the divergence is systematic, not per-package).

---

## Low

### F4 — `sortedByDisplayName` duplicated; `:core` copy dead in production · CONFIRMED (low)

**Files:** `core/src/main/java/com/github/reygnn/launcher/core/AppInfoSort.kt:15-16`
vs `kolibri/domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/model/AppInfoSort.kt:23-24`

The branch added `core.sortedByDisplayName` (commit `4c84567`) whose KDoc claims
it was *"Moved here (neutral `:core`) … so both apps' consumers call the same
greppable function."* Both bodies are identical (`sortedBy { it.displayNameLower }`),
but the **only** importer of the `:core` version is a test:
`kolibri/app/src/test/.../ui/CustomNamesViewModelTest.kt:9`. Every production /
fixture consumer (~12 files: `GetDrawerAppsUseCase`, `GetOnboardingAppsUseCase`,
`GetFavoriteAppsUseCase`, `ApplyCustomNames`, `GetInstalledAppsUseCase`,
`FavoritesOrderRepositoryImpl`, `CustomNamesShaping`, `HiddenAppsViewModel`,
`OnboardingViewModel`, `FavoritesSortViewModel`, `SwipeActionsViewModel`, plus
the fake) imports the `kolibri.domain.model` copy. `nyx` never uses it — it sorts
its own `LauncherApp` projection inline (e.g. `GetDrawerAppsUseCase.kt:60
sortedBy { it.displayName.lowercase() }`) and cannot consume an `AppInfo`
extension anyway.

**Impact:** no live bug (bodies identical, all consumers sort correctly), but the
`:core` copy is dead in production, `CustomNamesViewModelTest` guards a different
copy than the code path it covers, and the two can silently diverge. The stated
unification is incomplete. *(Original finding said "~14 sites"; precise count is
~12 non-test import sites.)*

**Recommended fix:** delete `kolibri/domain/.../model/AppInfoSort.kt`, repoint
all kolibri consumers (and `CustomNamesShapingTest` / `AppInfoSortTest` /
migration-characterization tests) at `com.github.reygnn.launcher.core.sortedByDisplayName`
(kolibri:domain already depends on `:core`). If `:core` is meant to host the
canonical copy but nyx legitimately can't use it, at minimum soften the `:core`
KDoc's "both apps" claim.

### F5 — `RefreshAppsUseCase` duplicated across `:core` and `kolibri` · CONFIRMED (low)

**Files:** `core/src/main/java/com/github/reygnn/launcher/core/InstalledApps.kt:51-57`
vs `kolibri/domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/usecase/RefreshAppsUseCase.kt:6-11`

The branch added a shared `core.RefreshAppsUseCase` (`@Inject`, wraps
`InstalledAppsRepository.triggerAppsUpdate()`), consumed by nyx's
`PackageEventCoordinator.kt:13,74`. kolibri keeps its own semantically-identical
copy (differs only by a trailing comma) used by `LauncherViewModel.kt:60,116`
and `AppManagementDelegate.kt:33,71`. Both wrap the **same** `core.InstalledAppsRepository`
(kolibri copy imports it at line 3), and `kolibri/domain/build.gradle.kts:120`
already declares `api(project(":core"))`, so the shared use case is fully
reachable from kolibri — the copy is redundant.

**Impact:** pure duplication; two same-named `@Inject` use cases on the classpath
invite an accidental wrong import.

**Recommended fix:** delete the kolibri copy, repoint the two production
call-sites + test imports to `core.RefreshAppsUseCase`, and drop the redundant
kolibri `RefreshAppsUseCaseTest` (the core use case can carry the single test).
No Gradle change needed; no behavior change. *(Not zero-touch: ~7 kolibri test
files reference it.)*

### F6 — `ReconcileResult.SkipReason` KDoc describes an obsolete StateFlow prime-window · CONFIRMED (low)

**File:** `nyx/domain/src/main/java/com/github/reygnn/nyx_launcher/home/model/ReconcileResult.kt`
(LOAD_FAILED KDoc lines 28-31, LOAD_EMPTY KDoc lines 33-48)

The KDoc says `LOAD_FAILED` = *"an explicit `AppLoad.Failed` was observed within
the prime window"* and `LOAD_EMPTY` = *"the prime window elapsed without ever
observing a non-empty `Loaded` … the shared contract is a hot `StateFlow<AppLoad>`
whose initial value is itself `Loaded(emptyList())`, so a one-shot reconcile call
cannot separate settled empty from initial/priming empty."* This is contradicted
by the only producer, `ReconcileHomeLayoutUseCase.kt:43-57`, which now reads
`enumerator.enumerate()` directly (`AppEnumerator.enumerate(): List<AppInfo>` — a
suspend one-shot, not a Flow): `LOAD_FAILED` = it threw, `LOAD_EMPTY` =
`apps.isEmpty()`. The use case's own KDoc even states *"no StateFlow race and no
prime timeout"* (branch commit `aae8f29` did the migration).

**Impact:** docs-only, but actively misdescribes the fail-closed RHL-INV-1
mechanism for anyone editing the reconcile logic.

**Recommended fix:** rewrite both KDoc blocks to match the one-shot `enumerate()`
contract (LOAD_FAILED = threw non-cancellation `Throwable`; LOAD_EMPTY = returned
empty list); drop all "prime window" / "hot StateFlow" / "cannot separate
settled-empty from priming-empty" language. Keep the RHL-INV-1 observability note.

### F7 — Nyx reconcile may prune the home layout on a partial snapshot · CONFIRMED, medium confidence (low severity)

**File:** `nyx/domain/src/main/java/com/github/reygnn/nyx_launcher/home/usecase/ReconcileHomeLayoutUseCase.kt`

`invoke()` fail-closes **only** on thrown (→ `LOAD_FAILED`, line ~45) and
`if (apps.isEmpty())` (→ `LOAD_EMPTY`, line 52); a non-empty-but-**partial** list
passes both. Lines 58-68 then build `installed = apps.mapTo(HashSet()){ it.key }`
and `layoutRepository.update { … reconcile(current, installed, …) … outcome.layout }`,
persisting the pruned layout unconditionally on `Changed`. `HomeLayoutReconciler.kt:44-60`
prunes every key `!in installed` with no per-item presence recheck. By contrast
kolibri gates each removal through `PackagePresence` /
`FavoritesRepositoryImpl.kt:251-253` (`filterNotTo(HashSet()){ isStillPresent(it) }`,
R-INV-2).

`LauncherAppsEnumerator.kt:112-117` has an outer per-item `catch (Throwable)` that
skips a bad entry and continues, so the enumerator *can* itself yield a
non-empty-but-incomplete result — **but** the label read (lines 92-99) falls back
to `packageName`, so only a throwing `componentName`/`packageName`/`className`
access actually drops an item, which is rare. The more plausible source is a
transient `getActivityList` result mid-restore / early post-unlock, which is a
platform-behavior assumption not provable from the code.

Note the misleading comment at `ReconcileHomeLayoutUseCase.kt:52-55`, which claims
"an empty/partial load … is treated as suspicious and skipped" — the guard only
catches *empty*, so the stated partial-load protection is not implemented.

**Impact:** if a partial snapshot is reachable, every app missing from the
partial set has its home/dock placement pruned and saved, permanently. Filed and
confirmed at low severity because reachability is unproven.

**Recommended fix (if judged reachable):** add a per-item presence recheck before
pruning (mirror kolibri's `PackagePresence` / R-INV-2 gate), or a keep-last-good /
sanity-floor guard that skips the reconcile if the enumerated count drops
implausibly (not only when it hits zero). At minimum, correct the misleading
comment at lines 52-55. Documenting the limitation (per nyx `ACCEPTED_LIMITATIONS.md`)
is a defensible interim choice.

### F8 — `GetInstalledAppsUseCase` KDoc claims a sort that no longer happens · PARTIALLY_CONFIRMED (low)

**File:** `kolibri/domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/usecase/GetInstalledAppsUseCase.kt:33-35`

The KDoc claims *"the enumeration still emits a deterministic order
(`InstalledAppsRepositoryImpl` sorts by original name), so this flow's own
`distinctUntilChanged` behaves identically."* After the migration the shared
enumerator returns the **raw** list: `LauncherAppsEnumerator.kt:34` ("Returns the
raw list (SIA-INV-3): no sort"), `InstalledAppsRepositoryImpl.kt:135-142` emits
`AppLoad.Loaded(fresh)` with comment "No sort: the holder holds raw." `git diff`
shows only import changes to this use case — the KDoc text is unchanged and now
stale.

**Partial:** the finding (echoing the KDoc) says the impl "sorts by original
name"; the pre-branch kolibri impl actually sorted by *display* name
(`sortedByDisplayName()` at merge-base `InstalledAppsRepositoryImpl.kt:280`) — so
the KDoc was already imprecise before the branch. Core defect unchanged.

**Impact:** no live display bug (all consumers re-sort: `GetDrawerAppsUseCase.kt:151,166`,
`GetOnboardingAppsUseCase.kt:62`), but the false "deterministic order" claim
invites a maintainer to drop a consumer-side sort, and the `distinctUntilChanged`
"behaves identically" rationale is unsound (`getActivityList` order is
system-determined).

**Recommended fix:** drop the "still emits a deterministic order … sorts by
original name" clause; state that the shared enumerator returns the raw unsorted
`getActivityList` order (SIA-INV-3) and justify `distinctUntilChanged` on value
equality of the applied list. Keep the "collectors must sort for themselves"
warning.

### F9 — `PackageEventCoordinator.refreshApps()` is dead/redundant per-event work · CONFIRMED (low)

**File:** `nyx/app/src/main/java/com/github/reygnn/nyx_launcher/PackageEventCoordinator.kt:124`

On every package event the handler calls `refreshApps()` (line 124, comment
"force a shared re-enumeration (freshness)") then `requestReconcile()` (line 125).
The class KDoc claims the trigger "forces the shared motor to re-enumerate …so
the debounced reconcile primes a fresh list." But:

- `ReconcileHomeLayoutUseCase` reads `enumerator.enumerate()` **directly**
  (injects `AppEnumerator`, never `InstalledAppsRepository`), bypassing the
  motor/loader — so this trigger never feeds the reconcile (see F6).
- The loader is
  `stateIn(scope, SharingStarted.WhileSubscribed(FLOW_SHARING_TIMEOUT_MS=5000L), Loaded(emptyList()))`
  (`InstalledAppsRepositoryImpl.kt`, `AppConstants.kt:146`). Its trigger is a
  `MutableSharedFlow(replay = 0, extraBufferCapacity = 1)`
  (`SystemServiceModule.kt:32`) — an emit with no subscriber is dropped.
- The only loader consumer is `GetDrawerAppsUseCase`, a one-shot
  (`withTimeoutOrNull { getInstalledApps().filterIsInstance<AppLoad.Loaded>().first { it.apps.isNotEmpty() } }`)
  re-invoked on each drawer open, which re-primes itself regardless.

**Impact:** with the drawer closed, `refreshApps()` no-ops (no subscriber); within
the 5s sharing window it fires a full `getActivityList` enumeration no active
collector reads and which the next drawer open would recompute anyway. Dead /
redundant work with a misleading justification.

**Recommended fix:** remove the `refreshApps()` call (and the injected
`RefreshAppsUseCase` dependency + import) from the package-event handler, keeping
`iconLoader.evict`, `folderRenderer.clear`, and `requestReconcile()`. Correct the
class KDoc / inline comment that claim the trigger keeps the reconcile fresh. If a
future live-drawer flow is planned, keep the call but fix the justification.

---

## Suggested order of action

1. **F2** (high) — port the motor test or fix the ADR marker. Real coverage gap.
2. **F1, F3** (test hygiene) — both are the "staged, not run" batch biting back;
   fix before the batch is ever run in CI.
3. **F6, F8** (stale docs) — cheap, prevents future maintainers from trusting
   invalidated invariants.
4. **F4, F5, F9** (dead code / incomplete unification) — low-risk cleanup that
   finishes the "shared" goal the branch is named for.
5. **F7** — decide whether a partial snapshot is reachable; if yes add a presence
   recheck, else at minimum fix the misleading comment and document the limitation.
