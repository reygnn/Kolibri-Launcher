# Execution Plan — Shared Installed-Apps + LauncherApps swap

Companion to `docs/specs/SHARED_INSTALLED_APPS_SPEC.md` (SIA) and
`docs/specs/MONOREPO_MERGE_SPEC.md` (MRG). This is the *how* for the request:
**drop Kolibri's `PackageManager` enumeration for `LauncherApps`, lift the whole
installed-apps machinery into the shared modules (`:core` port + `:common-data`
impl), and adapt experimental Nyx onto it.**

It records the resolution of the spec's open points (§9), sequences the work
against SIA §5, and — crucially — lists the mechanical remainder that is
deliberately **not** blind-authored in this drop, because it is either a
large-blast-radius sweep that must run as one atomic refactor or an
integration/timing decision that needs a human call.

> Honesty note: these files are **review-ready drafts against `main`**, not a
> verified build. This environment has no Android SDK and no Google Maven mirror,
> so `./gradlew :nyx:app:assemble` / `checkConventions` could not be run. Treat
> every file as a PR draft to compile-check locally.
>
> **[SUPERSEDED — this is now a committed post-execution record.]** The migration was
> executed in-repo (branch `feature/shared-installed-apps`): both apps build, all JVM
> suites + the characterization pins + `checkConventions` are green, and the on-device
> `LauncherApps`↔`PackageManager` enumeration parity passed with an empty
> `KNOWN_DIVERGENCE` on the A17 (SM-A176B / Android 16) and the Pixel 9a (Android 17).
> The original planning text is kept intact; the **POST-EXECUTION CORRECTIONS** below
> record the six assumptions that were disproven — that list is what shipped.

---

## POST-EXECUTION CORRECTIONS (record of what actually landed)

The migration was executed in-repo, compiler+tests as oracle. Six planning
assumptions below were disproven and are corrected here — the original text is left
intact so the deltas are visible. **Where a section below conflicts with this list,
this list is what shipped.**

- **[CORR-1 · F3/F5 — reconcile reads the ENUMERATOR, not the loader].** §2/§5 (F3)
  say Nyx's `ReconcileHomeLayoutUseCase` reads the shared *loader*
  (`getInstalledApps()` / `rawAppsFlow`). That is exactly what caused **F5**: the
  loader is a `WhileSubscribed` `StateFlow` that replays a conflated/stale cached
  value, so a one-shot reconcile could prune a package-removal against the
  pre-removal list. **Shipped:** reconcile injects the shared `AppEnumerator` and
  calls `enumerate()` directly — a deterministic fresh one-shot (throws→`LOAD_FAILED`,
  empty→`LOAD_EMPTY`, non-empty→reconcile; RHL-INV-1 intact). The drawer keeps the
  cached loader. F5 rejected alternatives, recorded so nobody re-proposes them:
  **(i-a)** subscribe-then-`drop(1)` is not correct — a `StateFlow` only emits on
  value change, so a no-op reload (common `PACKAGE_CHANGED`) never yields the
  post-trigger emission → 10 s prime timeout that blocks the reconcile pump;
  **(i-b)** a generation token in `AppLoad` forces an emission per reload and thereby
  regresses Kolibri's `ObserveInstalledAppsUseCase` dedup. A new pin
  (`reconcile_prunes_against_the_fresh_enumeration_not_a_stale_snapshot`) guards it.

- **[CORR-2 · §4e — no auto-aggregation; app-side binding (decision B)].** §4e claims
  the shared wallpaper/timeinfo impls auto-aggregate via `@InstallIn(SingletonComponent)`
  in `:common-data`. They do **not** — they are bound app-side in each app's
  `RepositoryModule`. So the shared installed-apps subsystem is bound the same way:
  Kolibri's and Nyx's `RepositoryModule` each `@Binds` the `:common-data` impls
  (`InstalledAppsRepositoryImpl`, `LauncherAppsEnumerator`) against the neutral
  `:core` interfaces. No `@Binds`/`@Provides` module is shipped in `:common-data`
  (only the `@EntryPoint InstalledAppsEntryPoint`, trimmed to `getAppUpdateSignal()`
  so it resolves in an app that hasn't wired the repo). `provideLauncherApps` +
  `provideAppsUpdateTrigger` live per-app (Kolibri `AppModule`, Nyx
  `SystemServiceModule`).

- **[CORR-3 · §4a — `SystemServiceModule` is KEPT].** §4a says delete Nyx's
  `SystemServiceModule` because its `provideLauncherApps` duplicates a shared
  provider. That was an option-A leftover. Under decision B there is **no** shared
  `provideLauncherApps`, so Nyx's is required (the shared enumerator, bound in Nyx,
  injects it) and is **not** a duplicate. `SystemServiceModule` stays and gained
  `provideAppsUpdateTrigger`.

- **[CORR-4 · F1 (§4f/§5) — did NOT fire].** `checkConventions` never flagged the
  motor: `kolibri/tools/check-conventions.sh` `src_roots` is only the Kolibri
  `app`/`domain`/`data` roots, so `:common-data` is never scanned, and the
  cancellation check is a positive list. The motor was **not** added to
  `cancel_files`. The only `cancel_files` edits were **removals** in C3b (the deleted
  Kolibri `InstalledAppsRepositoryImpl` + `PackageUpdateReceiver`), because the
  positive-list check ERRORS on a listed-but-missing file. The motor's
  CancellationException-first arms are kept regardless (house idiom).

- **[CORR-5 · §4d / Step F — receivers are CODE-registered, no manifest].** Neither
  app has a manifest `<receiver>`. Both register the shared `:common-data`
  `PackageUpdateReceiver` at runtime (`registerReceiver`, `RECEIVER_EXPORTED`):
  Kolibri in `KolibriLauncherApp`, Nyx in `PackageEventCoordinator`. There were no
  `AndroidManifest.xml` receiver edits.

- **[CORR-6 · Nyx freshness — F2 route (a), bus].** Nyx's `LauncherApps.Callback` was
  fully replaced by the shared broadcast → `AppUpdateSignal` bus (the callback is
  subsumed: the `PackageEvent` carries the package name for icon eviction too).
  `PackageEventCoordinator` collects the bus. Reconcile freshness is CORR-1 (the
  enumerator), not the bus trigger; `refreshApps()` remains only to keep the drawer's
  cached loader warm — no double-trigger.

---

## 1. Decisions taken (SIA §9 resolved)

The request settles all three open points:

- **§9.1 Enumeration API → `LauncherApps` is canonical.** This is the point of
  the task. Consequence per SIA §5-step-4: the `AppEnumerator` port collapses to
  **one** shared implementation — `LauncherAppsEnumerator` in `:common-data`.
  Kolibri's `PackageManager` path (`queryIntentActivities` + per-app
  `loadLabel`) is retired, not ported. There is no second (PackageManager) port
  binding; SIA-INV-4 (product variance only via the port) is preserved with a
  single impl.

- **§9.2 Empty-policy → value-honest, no `Reason` enum.** The shared `AppLoad`
  carries `Loaded(apps)` (possibly empty) and `Failed(cause)` only. An empty
  enumeration is a legitimate `Loaded(emptyList())`, **not** a failure. The
  "empty ⇒ suspicious" rule (Nyx's former `ENUMERATION_EMPTY`) becomes a
  **per-app reconcile policy at the Klasse-B edge**, not part of the shared
  envelope. SIA-INV-2 still holds: a *caught* load error is `Failed`, never an
  empty list; `CancellationException` always propagates.

- **§9.3 CustomName overlay → holder stays raw.** `InstalledAppsStateRepository`
  holds the unmodified enumerated list (SIA-INV-3). `customName` is a per-app
  overlay applied at each consumer, never stored in the shared model. Nyx's
  former `LauncherApp` remains a Nyx-side projection of the shared `AppInfo`
  (`label = originalName`, `customName` applied as overlay at the consumer).

### Module placement — `:common-data`, not a new `:common-android`

The port (`AppEnumerator`, `AppInfo`, `AppLoad`, repositories) lives in `:core`
(pure-JVM, no `android.*`). The Android impl (`LauncherAppsEnumerator`,
`PackageUpdateReceiver`, the Hilt providers) goes in **`:common-data`**, because:

- `:common-data` already *is* the "shared data backed by Android system services"
  module — the wallpaper and time-info providers already have exactly this shape
  (system-service → shared reactive source); `LauncherApps` enumeration is the
  same pattern and belongs with its peers.
- Both apps already depend on `:common-data`, so there are no new dependency
  edges, namespace, or Gradle wiring mid-refactor. MRG-INV-1 / MRG-INV-6 hold.
- A `:common-android` split (framework glue vs repositories) is a legitimate
  *future* cleanup, but to be coherent it should also relocate the existing
  wallpaper/time-info glue — its own PR, not a rider here.

If overruled, the move is cheap: the five `common-data/…/installedapps/` files
relocate verbatim and change only their package prefix; `:core` is untouched and
each app swaps one `implementation(project(...))` line.

---

## 2. What is in this drop (review-ready)

Paths are the intended targets. Files under `nyx-adaptation/` use `__` in the
filename purely to encode their target path in a flat folder; the header comment
of each gives the real `TARGET:` path.

### `:core` (pure-Kotlin JVM port — no `android.*`)
- `AppInfo.kt` — neutral model, **`isFavorite` dropped**, keeps the precomputed
  `displayNameLower` / `normalizedClassName` / `key` / `componentName`.
- `AppLoad.kt` — `Loaded` / `Failed`, **no `Reason` enum** (§9.2).
- `AppEnumerator.kt` — the port: `suspend fun enumerate(): List<AppInfo>`; throws
  on wholesale failure, rethrows cancellation, empty is legitimate.
- `InstalledApps.kt` — `InstalledAppsRepository` (`Flow<AppLoad>` +
  `triggerAppsUpdate`, `Purgeable`), `InstalledAppsStateRepository`
  (`rawAppsFlow` / `updateApps` / `getCurrentApps`, `Purgeable`),
  `RefreshAppsUseCase`.
- `AppInfoSort.kt` — `sortedByDisplayName()`.
- testFixtures: `Fakes.kt`, `InstalledAppsStateRepositoryContract.kt`;
  test: `FakeInstalledAppsStateRepositoryContractTest.kt`.

### `:common-data` (Android impl)
- `LauncherAppsEnumerator.kt` — **the swap.** `@Singleton`, injects
  `LauncherApps` + `@IoDispatcher`; `getActivityList(null,
  Process.myUserHandle())`; returns **raw** `AppInfo` (no sort, `displayName =
  originalName`, `customName` not folded); per-item resilience; throws on
  wholesale failure; rethrows cancellation; blank label falls back to package
  name; `Trace("drawer_apps_enumerate")` retained.
- `InstalledAppsRepositoryImpl.kt` — shared motor, enumerator-driven (SIA-INV-4);
  `reloadTriggers` prime + debounce; `stateIn(WhileSubscribed(...))`; maps a
  thrown enumerate to `Failed`, success to `Loaded` (possibly empty).
- `InstalledAppsStateRepositoryImpl.kt` — holder, `@Volatile
  lastSuccessfulAppList`, `getCurrentApps()` empty-fallback (SIA-INV-5).
- `PackageUpdateReceiver.kt` — moved, neutral namespace, references
  `InstalledAppsEntryPoint`.
- `InstalledAppsModule.kt` — `InstalledAppsBindsModule` (binds
  `AppEnumerator → LauncherAppsEnumerator`, repository, state repository) +
  `InstalledAppsProvidesModule` (`provideAppsUpdateTrigger`,
  `provideLauncherApps`) + `InstalledAppsEntryPoint`.
- tests: `LauncherAppsEnumeratorTest.kt` (Robolectric + MockK),
  `InstalledAppsStateRepositoryImplContractTest.kt`.

### Nyx adaptation (`nyx-adaptation/`)
- `ReconcileResult.kt` — Nyx-local `SkipReason { LOAD_FAILED, LOAD_EMPTY }`
  replacing the deleted `AppLoadResult.Reason`.
- `GetDrawerAppsUseCase.kt` — reads the shared reactive repo via the prime
  pattern, projects `AppInfo → LauncherApp` (`customName = null`, flagged TODO),
  sorts at the consumer.
- `ReconcileHomeLayoutUseCase.kt` — reads the shared repo; reconciles only on a
  genuine non-empty `Loaded`; fail-closed skip otherwise (RHL-INV-1).
- `RepositoryModule.kt` — `bindInstalledAppsRepository` removed (shared impl now
  binds it).

---

## 3. Sequencing (mirrors SIA §5; each stage keeps both apps green, MRG-INV-2)

**Stage 0 — Phase-1 prerequisites (owned by MRG, NOT in this drop).** The shared
`AppInfo` + `AppLoad` must already live in `:core`. Two large mechanical sweeps
gate everything below and must land first, each as its own atomic commit:

1. **Drop `AppInfo.isFavorite`** — 17 non-test usages (`AppInfoParcelable`,
   `GetFavoriteAppsUseCase` doing `copy(isFavorite = true)`,
   `BuildAppContextMenuUseCase`, …). Favorite membership moves entirely into
   `GetFavoriteAppsUseCase` / `FavoritesRepository`.
2. **Namespace sweep** `AppInfo`/`AppLoad` → `com.github.reygnn.launcher.core`
   (~120 import sites, MRG-INV-6).

These are intentionally not authored here: they are find-and-replace across the
whole Kolibri tree, safest run mechanically in-repo with the compiler as the
oracle, not hand-transcribed file-by-file into an offline draft.

**Stage 1 — canonicalize `AppLoad` + neutral `AppInfo`** (SIA §5.1). Done in the
`:core` files above; §9 decided (this doc).

**Stage 2 — holder → `:common-data`** (SIA §5.2). The `:common-data` files above
move the impl + receiver + EntryPoint under the neutral namespace. Kolibri then
consumes the shared versions; its own copies are deleted (see §4).

**Stage 3 — Nyx docks on** (SIA §5.3). Nyx binds the shared `AppEnumerator`
(here: the single `LauncherApps` impl), consumes `rawAppsFlow` instead of
pull-on-open, and its `Get*/Reconcile` use-cases read the shared holder. See the
`nyx-adaptation/` files. The `LauncherApps.Callback` drawer refresh gives way to
the broadcast pipeline — see the freshness flag in §5.

**Stage 4 — unify enumeration** (SIA §5.4). Because §9.1 chose `LauncherApps`,
this is already done: one enumerator, no port double-path.

---

## 4. Mechanical remainder — deliberately NOT blind-authored

Each item is a call-site sweep, a platform-registration edit, or a per-app DI
graph edit whose correctness depends on the whole module compiling. Doing these
by hand in an offline draft would be guesswork; they are listed so the human
executes them in-repo.

### 4a. Deletions (Nyx)
- `nyx/data/.../home/InstalledAppsRepositoryImpl.kt` **+ its test**
  (`data/.../home/InstalledAppsRepositoryImplTest.kt`).
- `nyx/domain/.../home/repository/InstalledAppsRepository.kt` (Nyx-local port).
- `nyx/domain/.../home/model/AppLoadResult.kt`.
- `nyx/domain/.../home/repository/InstalledAppsRepositoryContract.kt`
  **+** `nyx/domain/.../home/repository/FakeInstalledAppsRepository.kt`
  (Contract-Triple moves/retires together — SIA/MRG §7).
- `nyx/data/.../di/SystemServiceModule.kt` — after removing its only member
  `provideLauncherApps` (now a **duplicate** of the shared
  `InstalledAppsProvidesModule.provideLauncherApps`, which would be a Hilt
  duplicate-binding error), the module is empty → **delete the whole file**.
  `LauncherApps` stays available to `LauncherAppsIconSource` / `MainActivity` /
  `PackageEventCoordinator` via the shared provider (aggregated into Nyx's
  `SingletonComponent`, same mechanism as the existing shared wallpaper/timeinfo
  binds).

### 4b. Deletions (Kolibri)
- Kolibri's `InstalledAppsRepositoryImpl` / `InstalledAppsStateRepositoryImpl` /
  `PackageUpdateReceiver` / its installed-apps EntryPoint — replaced by the
  `:common-data` versions.
- Kolibri's installed-apps testFixtures (`InstalledAppsRepositoryContract`,
  `InstalledAppsStateRepositoryContract`, the fakes) relocate to the shared
  `:core` testFixtures (already drafted there); the Kolibri copies are deleted.
- Any Kolibri `provideLauncherApps` / PackageManager provider that now duplicates
  the shared provider.

### 4c. Kolibri call-site sweep (behaviour change — read carefully)
The shared `LauncherAppsEnumerator` returns the list **raw and unsorted**
(SIA-INV-3). Kolibri's old `loadAppsFromPackageManager` path sorted centrally;
its consumers must now **sort at the consumer** (the APPLIST_SORT_SPLIT posture
Nyx already uses). Audit every reader of the installed-apps list in Kolibri and
add the sort where the old code relied on the repository returning sorted data.

Also redundant / to rewire in Kolibri:
- `app/.../di/AppUpdateModule.kt` (`appsUpdateTrigger` `MutableSharedFlow`) — the
  trigger is now provided by the shared `InstalledAppsProvidesModule`; remove the
  Kolibri provider to avoid a duplicate binding.
- `app/.../ui/main/delegate/AppManagementDelegate.kt` — the `appUpdateSignal →
  refreshAppsUseCase → triggerAppsUpdate` wiring now targets the shared
  `RefreshAppsUseCase`; confirm the injection points resolve to the `:core`
  types.
- `ObserveInstalledAppsUseCase` — see §6; it stays Kolibri-side, but its imports
  move to the neutral `AppLoad`/`AppInfo`.

### 4d. AndroidManifest — `PackageUpdateReceiver` registration (BOTH apps)
The receiver moved to `:common-data`; each app must register it in its own
manifest with the package-change intent filter
(`ACTION_PACKAGE_ADDED/REMOVED/CHANGED`, `data android:scheme="package"`).
Kolibri already had this filter (transcribe the filter, update the
`android:name` to the new neutral class). **Nyx never had a receiver** (it used
`LauncherApps.Callback`) — this is a *new* manifest entry for Nyx and is the
mechanism that makes the shared broadcast-driven freshness work there.

### 4e. Per-app DI graph edits
Confirm both apps' `SingletonComponent` aggregates the `:common-data`
`InstalledAppsBindsModule` / `InstalledAppsProvidesModule` (they do, via the
`@InstallIn(SingletonComponent::class)` aggregation Hilt already does for the
shared wallpaper/timeinfo modules). Remove every now-duplicate local binding
(§4a/§4b).

### 4f. Build-file deltas
- `:common-data/build.gradle.kts` — add
  `testImplementation(testFixtures(project(":core")))` so the moved tests can use
  `MainDispatcherRuleBase`. `LauncherApps` needs nothing extra (Android lib).
  Optionally add a tiny `:common-data` `MainDispatcherRule` subclass mirroring the
  test convention if the module prefers a local alias.
- `:core` — ensure the new `installedapps` testFixtures package is part of the
  `java-test-fixtures` source set (it already has the fixtures dir).
- **[F1] `checkConventions` `cancel_files` whitelist.** `InstalledAppsRepositoryImpl`
  keeps the hand-written `try { emit(…) } catch` arms inside its `Flow.catch`
  blocks; `emit` is a suspension point, so this is exactly the broad-catch-at-a-
  suspension-point case the `cancel_files` guard governs. The original Kolibri file
  was on that whitelist. After the move, the whitelist config that `checkConventions`
  reads MUST list the new path
  (`common-data/.../installedapps/InstalledAppsRepositoryImpl.kt`), or the build
  fails. Move/extend the entry as part of this migration.

---

## 5. Open risks / integration decisions (need a human call)

> Items tagged **[F1]–[F4]** were raised by a self-review pass over the drafted
> files (F1 lives in §4f). They are integration/build wiring and design notes, not
> logic bugs found in the ported motor/holder/receiver, which read as faithful ports.


- **[F2] Nyx freshness wiring (highest priority).** Nyx's `PackageEventCoordinator`
  drives reconcile off `LauncherApps.Callback` and calls `reconcile()` directly.
  With the shared repo now the source, a package event must force a shared
  **re-enumeration**, or reconcile may read a stale cached value inside the
  `WhileSubscribed` window. **Sharpened by review:** the shared `PackageUpdateReceiver`
  does NOT call `triggerAppsUpdate()` — it calls `AppUpdateSignal.send(PackageEvent)`.
  The jump from that bus to the motor's reload trigger is an app-side collector
  (Kolibri's `AppManagementDelegate`: `appUpdateSignal.events → refreshAppsUseCase
  → triggerAppsUpdate`). **Nyx has no such collector**, so registering the receiver
  in Nyx's manifest *alone is not enough* — the events land on a bus nothing in Nyx
  consumes and no re-enumeration happens. Two clean options for Nyx: **(a)** register
  the receiver (§4d) AND add a small `AppUpdateSignal.events → triggerAppsUpdate`
  collector (the Kolibri shape, minus the reconcile overlays), keeping the
  `Callback` only for immediate icon eviction; or **(b)** skip the receiver/bus
  entirely and have the coordinator call `RefreshAppsUseCase()` (`triggerAppsUpdate`)
  before the debounced reconcile. The signal-based design is correct — the bus
  carries the *specific* `PackageEvent` Kolibri's reconcile / icon-eviction needs —
  only Nyx's bridge is missing. Not baked in here because it is app-lifecycle glue
  with real timing subtleties.

- **[F3] The shared holder is Kolibri-only in practice.** The Nyx use-cases read the
  **loader** (`getInstalledApps()`) directly via the prime pattern; they never read
  the **holder** (`InstalledAppsStateRepository.rawAppsFlow`). So the shared holder
  is wired only by Kolibri (via `ObserveInstalledAppsUseCase.updateApps`). This is
  not a bug — SIA-INV-1 ("one holder") holds for Kolibri, and Nyx's pull+reconcile
  pattern legitimately needs no holder — but two consequences are worth stating:
  Nyx has **no keep-last-good** fallback (SIA-INV-5 does not protect it), and the
  bound-but-unused `InstalledAppsStateRepository` Singleton simply never gets
  injected in Nyx (harmless). If Nyx ever wants empty-flicker protection, adopt the
  holder there too.

- **[F4] Genuinely-empty device → up to a 10 s stall in Nyx.** The drawer prime
  (`first { it.apps.isNotEmpty() }`) waits for a non-empty list; a device with zero
  launchable apps never satisfies it and runs into
  `INSTALLED_APPS_PRIME_TIMEOUT_MS` (10 s) before returning empty. Old Nyx returned
  empty immediately (via `ENUMERATION_EMPTY`). "Zero apps" is impossible on a real
  device (there is always ≥ 1), so this is acceptable, but the latency edge should
  be noted in a code comment at the prime site so it is not mistaken for a hang.

- **`WhileSubscribed` timeout vs Nyx drawer lifecycle** (SIA §8). Nyx's old
  pull-on-open had no sharing semantics; validate
  `FLOW_SHARING_TIMEOUT_MS` (5 s) against the drawer's open/close cadence so a
  quick close+reopen replays the cached list rather than re-enumerating.

- **Failed-vs-empty distinction lost at Nyx's reconcile edge.** Nyx's old
  one-shot returned a crisp terminal `Loaded(empty)`; the shared `StateFlow` is
  seeded with a conflated initial `Loaded(emptyList())`, so a one-shot reconcile
  call cannot separate "settled empty" from "priming/stuck empty". `SkipReason`
  is redefined honestly: `LOAD_FAILED` = an explicit `Failed` was observed;
  `LOAD_EMPTY` = no non-empty list within the prime window (subsumes the old
  `ENUMERATION_EMPTY`). Both are observability-only; fail-closed (RHL-INV-1)
  holds regardless.

- **Kolibri central-sort removal** (§4c) is a genuine behaviour change; the most
  likely place for a regression. Sweep carefully.

---

## 6. Divergence from a literal SIA §3 reading (intentional)

SIA §3 could be read as "move the whole installed-apps pipeline to shared."
`ObserveInstalledAppsUseCase` (Kolibri) is **deliberately kept Kolibri-side**: it
carries all of Kolibri's product overlays — reconcile of favorites / swipe /
hidden / customNames via `runCleanup` + `PackagePresence`, plus
`installedAppsStateRepository.updateApps`. Those are **Klasse B** overlays
(SIA-INV-3, §8), owned by Kolibri's curation specs, not shared machinery. Moving
it wholesale would drag product policy into the neutral layer and violate
SIA-INV-4. Only the **raw** two-stage engine (loader + holder + enumerator port)
is shared; each app keeps its own `Observe`/`Get*`/`Reconcile` overlay layer on
top of `rawAppsFlow`.
