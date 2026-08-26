# CLAUDE.md

Project conventions for **Kolibri Launcher** (a minimalist Android launcher,
GPLv3). Claude Code reads this file automatically at session start. Keep it
short and actionable — not a marketing description (that's `README.md`'s job).

> **Single-file by design.** Tooling auto-loads `CLAUDE.md` (root +
> sub-paths). A separate `RULES.md` would either need a "read this first"
> pointer (ignorable) or duplicate the rules (drift class). Stays
> single-file until this file outgrows practical readability; if it does,
> the natural next step is a deeper-path `CLAUDE.md` (e.g. `tools/CLAUDE.md`
> for linter-specific conventions), not a sibling `RULES.md`.

For deep test conventions, see `app/src/test/CLAUDE.md` and
`app/src/test/java/com/github/reygnn/kolibri_launcher/TESTING_CONVENTIONS.kt`.
This file is the **project-wide** entry point; the other CLAUDE.md is the
testing reference.

---

## Stack

- Kotlin, Android Views + ViewBinding (**no Compose**), Material 3
- Min SDK 36 (Android 16); Target 37 / Compile 37 (Android 17) — lifted
  2026-07-18 for `core-ktx 1.19.0`, pinned in `gradle/libs.versions.toml`. No
  compatibility shims.
- JDK 21 (Robolectric requires it for SDK 36)
- MVVM + Clean Architecture, **DI via Hilt** (KSP2, kapt-free)
- Persistence: Jetpack DataStore Preferences (no SharedPreferences)
- Crash reporting: ACRA, self-hosted, opt-in
- Coroutines/Flows throughout; UI state as `StateFlow`, events as `SharedFlow`
- Tests: JUnit 4, **MockK** (Mockito has been fully migrated away), Turbine,
  kotlinx-coroutines-test, Robolectric (only for `android.net.Uri`-touching
  impls)

Exact dependency versions are pinned in `gradle/libs.versions.toml` (with the
`DO NOT CHANGE` rationale on each) — read them there, not here; this summary
carries names only so it cannot drift against the catalog.

## Build & test

```bash
./gradlew assembleDebug          # debug APK
./gradlew test                   # unit tests (JVM, no emulator)
./gradlew jacocoTestReport       # coverage report
./gradlew checkConventions       # CLAUDE.md rule linter (Rule 9, 11, 12, naming, Toast routing, cancellation rethrow, Flow.catch rethrow, unbuffered SharedFlow, purge completeness, settings-store keep-list registration + straggler guard + @IntoSet binding parity, ActivityResult placement, adapter null-out, Exception breadth, localization parity, Rule 2 contract triple, stale-replay hot-flow point-read [AUDIT-13, wired as a dependsOn task])
./gradlew checkRule13            # diff-aware German-comment linter (Rule 13)
./gradlew assembleRelease        # finalized: triggers ProGuard mapping upload to ACRA
```

Gradle is bundled via wrapper. `androidTest/` was empty for ~6 months
after a flakiness-driven deletion event (~500 tests removed — see
Rule 10 below and `HISTORY.md`). Since late April 2026 it is being
reintroduced under a **value bar, not a cost bar** (Rule 10): a path
earns a place in `androidTest/` when it exercises real-device behaviour
JVM/Robolectric genuinely can't reach. The authoring/maintenance cost
that drove the original purge has since collapsed, so the deciding
question is signal, not effort — reach for it when a device is needed to
make the behaviour true, skip it when it would only duplicate JVM/
Robolectric coverage. Robolectric itself is already in use in the JVM
`test/` set for `android.net.Uri`-touching code (e.g.
`WallpaperRepositoryImplTest`).

---

## Architecture

Three Gradle modules since the §9.2 split (2026-05-03). Production code is
split across `:domain`, `:data`, and `:app`. Tests are split too: repository
contracts + fakes in `domain/src/testFixtures/`, domain unit + fake-contract
tests in `domain/src/test/`, impl-contract tests in `data/src/test/`, and
ViewModel/UI tests (plus Robolectric fragment tests in `app/src/testDebug/`)
in `:app`.

```
:domain  (Pure-Kotlin JVM module — `kotlin("jvm")`, no Android SDK)
  core/                   AppConstants, ColorMath, KolibriLog, TimberWrapper,
                          AppUpdateSignal, Qualifiers, CoerceExtensions
  domain/repository/      interfaces (FavoritesRepository,
                          CrashReportConsentRepository, …) — 18 of them
  domain/usecase/         ~55 fine-grained use cases (GetDrawerAppsUseCase,
                          Get/SetCrashReportConsent +
                          GetCrashReportConsentState, …)
  domain/model/           data classes (AppInfo, HomeSettings, UiState,
                          AppContextMenuAction + LauncherActionLabel,
                          WallpaperState, LauncherShortcut,
                          CrashReportConsentState, ConsentRead/WriteResult,
                          …) — pure Kotlin, no Parcelable / no Android
                          imports.
  di/DispatcherModule     @Provides for Default/IO/Main + ApplicationScope

:data    (Android Library, depends on :domain)
  data/                   repository implementations (FavoritesRepositoryImpl,
                          BackupRepositoryImpl, CrashReportConsentRepositoryImpl,
                          …), ConsentBootstrap, PackageUpdateReceiver
  data/service/           ShortcutLauncherServiceImpl
  di/RepositoryModule     @Binds for every repository interface
  di/DataStoreModule      two Preferences DataStores + their internal
                          Context.settingsDataStore / .consentDataStore
                          extensions. Both are Hilt-provided (the consent
                          one qualified, @ConsentDataStore, injected by
                          CrashReportConsentRepositoryImpl); consentDataStore
                          is a separate store (own backing file) so ACRA
                          consent stays privacy-by-default and out of Auto
                          Backup, and it is additionally read via its
                          extension on the pre-Hilt bootstrap path.

:app     (Android Application, depends on :domain + :data)
  ui/                     features: home, appdrawer, settings, onboarding,
                          swipeactions, customnames, hiddenapps, backup,
                          colorcustomization, layoutcustomization,
                          usageexport, appcontextmenu, main (with delegate/
                          subpackage)
  di/                     AppModule (PackageManager, WallpaperManager,
                          @Named("appVersionName") from BuildConfig),
                          AppUpdateModule
  KolibriLauncherApp.kt   @HiltAndroidApp entry, ACRA init, Timber trees,
                          AnrReporter (post-mortem ApplicationExitInfo)
```

Key points to remember when adding code:

- **Modules form a one-way dependency chain.** `:app → :data → :domain`.
  `:data` cannot import from `:app`/`ui/`; `:domain` cannot import from
  `:data` or `:app`. The two cycles that existed before the split
  (`data → ui` via `SwipeSlot`/`AppUpdateSignal`/`CrashReportConsent`,
  `domain → di` via `@DefaultDispatcher`) are gone — don't reintroduce.
- **`:domain` is a pure-Kotlin JVM module.** No Android SDK on its
  compile classpath, no `BuildConfig`, no `R` class. The §11/§12 sweeps
  removed every Android dependency: Parcelable models became plain data
  classes (UI wraps for Bundle transport), DataStore-key references in
  `AppConstants` were dropped, `:domain/res/` strings moved to `:app/`,
  and `core/KolibriLog` replaces direct `Timber.*` calls — its lambda
  handlers are wired by `:app/KolibriLauncherApp.onCreate`. Same logic
  for `TimberWrapper.isDebugBuild` (set from `BuildConfig.DEBUG` in
  `:app`). The pure-Kotlin status is enforced by the build itself: the
  module declares `kotlin("jvm")` and depends on `hilt-core` (JAR), not
  `hilt-android` (AAR).
- **`:data` is an Android Library** (it depends on Android SDK for
  DataStore, ContentResolver, LauncherApps, etc.). The app's
  `versionName` flows from `:app/AppModule` via
  `@Named("appVersionName") String` to `BackupDataAssembler` and
  `UsageExportRepositoryImpl` — not duplicated as a `buildConfigField`.
  Single source of truth.
- **Use-case messages are sealed identifiers, not `@StringRes Int`.**
  `ToggleFavoriteUseCase.Result.Success.Added` etc. expose a sealed-
  class identifier; UI maps to `R.string.*` via
  `:app/ui/util/DomainMessageMappers.kt`. Same for `AppLoadResult.Failure`
  and `AppContextMenuAction.LauncherAction.label`. Keeps the domain
  free of `androidx.annotation` and Android resource ids.
- **`internal` does not cross module boundaries.** When tests in `:app`
  need access to a test-only entry point in `:data`, the entry point
  drops `internal` and keeps `@VisibleForTesting` (so out-of-test
  usage still triggers the lint warning).

`MainActivity` is registered as both `LAUNCHER` and `HOME` intent (a real
default-launcher candidate). It hosts fragments via the Navigation Component.
Settings, Onboarding, HiddenApps, CustomNames, and SwipeActions are separate
activities.

---

## Hard architectural rules

1. **Repository interface, always.** Every data access goes through an
   interface in `domain/repository/`. Production implementations live in
   `data/` as `XyzRepositoryImpl`; test doubles live in
   `app/src/test/.../fakes/` as `FakeXyz...`. ViewModels and use cases depend
   only on the interface, never on the concrete impl.

2. **Contract-test triple per repository.** `XyzRepositoryContract` (abstract,
   `@Test` methods) + `FakeXyzRepositoryContractTest` +
   `XyzRepositoryImplContractTest`. If fake and impl drift, the contract
   catches it. Exceptions (system-API impls, composition repos, and
   repos whose fake would be tautological — currently `BackupRepository`,
   `InstalledAppsRepository`, `TimeBasedEventsRepository`, `ResetRepository`,
   `ShortcutRepository`, `UsageExportRepository`; not an exhaustive list)
   are justified per-repo in the corresponding contract KDoc under a
   "NO CONTRACT TEST (ADR)" note, and each still carries impl-level test
   coverage. Details: `app/src/test/CLAUDE.md`.

   **Enforced** by `./gradlew checkConventions`
   (`tools/check-contract-triple.sh`): every `*Repository` interface in
   `:domain` needs the full triple, or a marker in its contract file.
   Two markers, because the exemptions are not all the same size:
   `NO CONTRACT TEST (ADR)` exempts the whole triple, `NO IMPL CONTRACT
   TEST (ADR)` exempts only the impl half and keeps the fake contract
   test required. Non-repository interfaces (`Purgeable`,
   `WallpaperBitmapLuminance`) are out of scope by the name filter.

   The rollout found no missing *reasoning* — all six exemptions were
   argued at length in their contract KDoc. What was missing was one
   spelling: `TimeBasedEvents` wrote "KEIN CONTRACT TEST", `Backup`
   "CONTRACT TEST (BEWUSST DÜNN, FAKE-ONLY)", `InstalledApps` "CONTRACT
   TEST (BEWUSST DÜNN)" — three self-invented headings, none greppable,
   so the decision was invisible to any check. Each gained a canonical
   marker line next to (not instead of) its existing German rationale.
   Same lesson as `Exception sufficient`: a linter cannot judge prose
   quality, only the presence of a stable token — so the token has to be
   decided once and reused. Regression-tested via
   `tools/check-contract-triple-test.sh` (manual rerun, not a CI gate);
   because the check is filesystem-shaped rather than text-shaped, that
   test builds a synthetic repo skeleton instead of feeding a fixture to
   an awk core.

3. **When a contract test goes red, the impl wins.** Align the fake to
   the impl, not the other way around — impl behavior is production
   truth. Intentional drifts get documented in the contract KDoc under
   "NOT IN CONTRACT — intentional drifts", **not** hidden.

4. **Hilt is the DI framework.** No second DI framework, no manual-DI
   refactor. Modules are split by responsibility in `di/`. `Repository ↔
   RepositoryImpl` bindings always go in `RepositoryModule.kt`.

5. **DataStore Preferences is the only app storage for user state.** No
   `SharedPreferences`, no app-managed SQLite. User-facing backup /
   restore goes through `BackupRepositoryImpl` + `BackupDataAssembler`
   (JSON export/import).

   *No `SharedPreferences` exception remains.* `CrashReportLimiter`
   (`acra_report_limiter`) — historically the one deliberate
   `SharedPreferences`-backed store, holding ACRA crash-report rate-limit
   timestamps read sync from the crash-handler thread — was removed in the
   ACRA rewrite (ACRA_SPEC.md Belang B): client-side throttling is gone and
   flood control (fingerprint dedup + ingestion rate-limit) is now entirely
   server-side (§S). DataStore Preferences is the only app storage.

   *Migration policy — NO in-code migrations.* A `DataMigrationManager`
   class used to live in `data/` to bridge schema changes across app
   updates (most recently ACRA-consent V1→V2 in early May 2026). It was
   removed once every existing install had moved past V2, and the policy
   since is deliberate and stronger: **Kolibri ships no in-code
   data-migration logic** — no `produceMigrations`, no copy-on-init, no
   revived `DataMigrationManager`. When a schema or DataStore split would
   drop existing data, the sanctioned path is **user-driven: export in the
   old version → factory reset → update → restore.** That loses nothing
   because both exports are JSON (store-agnostic, so they survive a store
   split): launcher settings via the backup (`BackupRepository`), usage
   timestamps via the separate re-importable `UsageExportRepository`. The
   user must export both. Design new state to be additively
   backward-compatible where that is trivial, but do **not** add migration
   code to bridge a breaking change — steer users to export/reset/restore
   instead (the AUDIT-19 F1 usage-store split is the reference: no
   migration, by design). Git history under `data/` has the old
   `DataMigrationManager` shape if it is ever genuinely needed.

   *Purge completeness (enforced).* A "reset all settings"
   (`purgeRepository()`) must wipe **every** statically-declared preference
   key of its repo — a key left behind is a reset that silently lies
   (AUDIT-4 #1: `SettingsRepositoryImpl` declared `APP_DRAWER_MODE` but never
   removed it). `./gradlew checkConventions` enforces this per file
   (`tools/check-purge-completeness.awk`): every constant-style
   `val UPPER_SNAKE = <…>PreferencesKey(…)` must be referenced in a
   non-comment line of that file's `purgeRepository()` (a comment merely
   *naming* the key does not count — that adjacency is how the AUDIT-4 gap
   hid), or carry a `purge-exempt NAME` marker for state intentionally kept
   across a reset (only `ONBOARDING_COMPLETED` today). Dynamic per-entity
   keys (local `camelCase` vals built from a prefix, purged by iteration)
   are excluded; a wholesale `.clear()` exempts the file. Regression-tested
   via `tools/check-purge-completeness-test.sh` (manual rerun, not a CI gate).

   *Storage-cleanup keep-list (enforced).* The user-facing "clean up storage"
   feature (`DataStoreMaintenanceRepository.removeOrphanKeys`) is a
   **blacklist-of-unknown**: it deletes every **settings-store** key that no
   live owner claims, rather than a hand-maintained list of retired keys (the
   old `RetiredDataStoreKeys` whitelist-of-dead, removed). Consent and usage
   stores are out of scope (ACRA privacy / usage self-heals). Each settings-store
   key writer declares the keys it owns by implementing
   `core/OwnsSettingsStoreKeys` (`ownedExactKeys()` / `ownedKeyPrefixes()`,
   sourced straight from its live `Preferences.Key` objects), and all owners are
   aggregated into `Set<OwnsSettingsStoreKeys>` via Hilt `@IntoSet` multibinding
   — the only multibinding in the project, justified because its **failure mode
   is inverted vs. purge**: a whitelist forgetting a key left a harmless orphan,
   but here a forgotten *owner* makes a live key look orphaned and get **deleted**
   (data loss). That risk is contained by, and only by: (1) owners returning the
   live key objects (nothing to hand-sync), (2) the impl's empty-keep-list floor (a
   *total* DI failure reports `Failed`, never wipes the store), and (3) three
   `./gradlew checkConventions` gates: **per-owner completeness** — every
   settings-store key an owner declares must appear in its
   `ownedExactKeys()`/`ownedKeyPrefixes()` — a **straggler guard** — any file that
   declares a `PreferencesKey` but is neither an owner nor a known usage/consent
   writer flags (the cross-module `AnrReporter` case: a non-repo in `:app` that owns
   one settings key) — and **binding parity** — every structural owner must have
   exactly one `@IntoSet` binding. The parity gate exists because `@IntoSet` is
   **not** auto-registration: implementing `OwnsSettingsStoreKeys` has no Dagger
   effect, the binding is a manual per-owner line, and a forgotten one would drop
   the owner from the runtime `Set` (only *total* loss trips the empty-keep-list
   floor, not one missing owner) so its live keys get deleted. A new settings-store
   key writer MUST implement `OwnsSettingsStoreKeys`, register its keys, AND add its
   `@IntoSet` binding, or the cleanup will delete them. Two details are load-bearing and were hardened after a
   multi-agent review found them as data-loss false-negatives: registration is a
   **whole-token** match, not an `index()` substring test (else a forgotten
   `COLOR` matches inside a registered `TEXT_COLOR` and slips through — real
   collisions exist: `SCALE⊂LAYOUT_SCALE`, `ALARM⊂SHOW_ALARM`,
   `FAB_X⊂FAB_X_FRACTION`); and **owners are discovered structurally** (a file
   that overrides an `OwnsSettingsStoreKeys` method), NOT from a hand-maintained
   list — a second list drifts, and a new owner could otherwise be added only to
   a straggler-allowlist to green the build while escaping completeness. Deriving
   owners from the override means "join the keep-list" (what silences the
   straggler) *is* implementing the interface, which subjects the file to the
   completeness gate. Regression-tested via
   `tools/check-settings-keys-registered-test.sh` (manual rerun, not a CI gate).

6. **Respect the version pins in `gradle/libs.versions.toml`.** Many
   dependencies carry `DO NOT CHANGE` / `DO NOT UPGRADE` / `DO NOT DOWNGRADE`
   comments (Hilt, ACRA, fragment-ktx, JUnit, Material, kotlin-test,
   core-testing, …). These markers are not suggestions — violating them breaks
   builds or tests. Read the exact versions and the reason each is frozen in
   the catalog, not here — a hardcoded list in this file drifts, the catalog is
   the single source of truth. `minSdk = 36`, `compileSdk = targetSdk = 37` are
   fixed in `app/build.gradle.kts`.

7. **`KolibriLauncherApp` is multi-layer crash-safe by design.** The style
   (catching `Throwable` instead of `Exception`, the coroutine
   `ExceptionHandler`, the global handler, OOM recovery) is intentional — do
   not "clean it up" or simplify. New critical init paths follow the same pattern: wrap each
   block in `try { … } catch (e: Throwable) { … }`. The **safety-net** catches in this
   crash-infra file (the coroutine `ExceptionHandler`, the lifecycle callbacks) report via
   `TimberWrapper.reportToAcra(e, ...)` (ACRA_REPORT tag, no DEBUG throw), because a
   `silentError` DEBUG throw there would recurse into the very path it guards. A plain init
   step whose DEBUG throw *cannot* recurse may still use `silentError` (e.g.
   `registerPackageUpdateReceiver` — a failed receiver registration is a real bug worth the
   DEBUG throw). Inside the global crash handler itself (`UncaughtCrashHandler`),
   `android.util.Log.e` is used — not Timber, to avoid re-entering the planted `AcraTree`
   and double-sending — see Rule 9 for why.

8. **ACRA is opt-in (privacy-by-default).** Crash reporting is disabled
   immediately after init (`ACRA.errorReporter.setEnabled(false)`) and is
   only enabled when the bootstrap read (`ConsentBootstrap.readDecision`)
   returns `ConsentDecision.Granted`. Do not change this order. Reports go to
   a private server, never to a third-party service.

9. **Error logging goes through `TimberWrapper` — report by intent (§23).**
   Whether an entry reaches ACRA is decided by INTENT (an explicit tag), not
   by log level. A bare `Timber.e(...)` neither reports (untagged → dropped by
   `AcraTree`'s gate) nor throws in DEBUG — it silently under-reports, so it is
   forbidden (see enforcement). The report-worthy APIs on `TimberWrapper`:

   - `silentError(throwable, message)` — throw in DEBUG **+** report in RELEASE
     (`SILENT_ERROR` tag). The default for a caught programmer error.
   - `silentError(message)` (no throwable) — throw in DEBUG, **NOT** reported
     (a null throwable is dropped by `AcraTree`). The deliberate channel for
     "loud in DEV, not worth a RELEASE report": self-healing artefacts,
     expected-degradation guards. If a RELEASE report is wanted, pass a cause.
   - `reportToAcra(throwable, message)` — report in RELEASE (`ACRA_REPORT` tag),
     **no** DEBUG throw. For crash-infra that must not re-enter the safety net
     it guards.
   - `silentDeath(...)` — `exitProcess(1)` in every build (even DEBUG) **+**
     report; the message-only overload synthesizes a carrier so the FATAL death
     actually reaches ACRA (a null throwable would be dropped, then the process
     would exit invisibly). For lying-state paths (half-migrated DataStore,
     Activity without ViewModel, HOME-Activity restart loop).

   Local diagnostics use `Timber.d` / `Timber.w` (never reported).

   **Two intent tags** (a single tag is overloaded — it also gates
   `BaseActivity`'s DEBUG dev-toast suppression): `SILENT_ERROR`
   (silentError/silentDeath: routes to ACRA AND suppresses the dev-toast, since
   it already throws in DEBUG) and `ACRA_REPORT` (reportToAcra: routes, does
   NOT suppress — those paths don't throw, so the dev-toast is wanted).
   `AcraTree` delivers iff an entry carries either tag AND a throwable.

   **Crash-infra uses `reportToAcra`, not `silentError`.** A handful of files
   sit *inside* the crash-reporting pipeline: a `silentError` DEBUG throw there
   would recurse into the path they are the safety net for. They report via
   `reportToAcra` (report, no throw):

   - `KolibriLauncherApp.kt` — the app-scope `CoroutineExceptionHandler` and the
     `onCreate` / lifecycle init catches.
   - `BaseActivity.kt` and `BaseViewModel.kt` — the `CoroutineExceptionHandler`
     and the `ErrorEventBus` / event collectors. A throw here escapes the
     safety-net coroutine and lands at the global uncaught handler, which is
     exactly what these wrappers exist to prevent.
   - `BackupFragment.kt` — its fragment-scoped `CoroutineExceptionHandler`.
   - `CrashReportingBootstrap.kt` — ANR drain + watchdog delivery + their
     self-failure catches, on the bootstrap path.
   - `TimberWrapper.kt` itself — the definition site of these APIs.
   - `UncaughtCrashHandler.kt` and `AcraTree.kt` use `android.util.Log.e`, NOT
     Timber — a `Timber.e` would re-enter the process-wide planted `AcraTree`
     and double-send the crash (one silent carrier + one fatal auto-report).

   **The one bare-`Timber.e` exception: the pre-wiring bootstrap path.**
   `ConsentBootstrap.kt` runs before KolibriLog is wired and before `AcraTree`
   is planted — `reportToAcra` would be a no-op and `silentError`'s DEBUG throw
   would crash the app before the reporter exists. It keeps a bare `Timber.e`,
   flagged with a `pre-wiring bare` marker. (`ConsentDialog.kt`, the dialog UI
   helper, sits off the bootstrap path and uses `silentError` — the
   throw-in-DEBUG is wanted there, since a dialog that fails to show must not
   silently masquerade as a decline. AUDIT-10 #6/#8.)

   **Transitive form.** The recursion ban applies to helpers these files
   *invoke* too: a UI fallback called from a `CoroutineExceptionHandler`
   (e.g. `BackupFragment.showError`) must not contain `silentError` either, or
   the DEBUG throw simply lands one frame deeper and surfaces as
   `RuntimeException: Exception while trying to handle coroutine exception` —
   use `reportToAcra` or a non-throwing log. Document the constraint on the
   helper itself, not in this rule.

   New code in any other file: use `silentError` (or `reportToAcra` on a
   crash-infra path). Details and the test escape (`preventCrashForTesting`)
   live in `core/TimberWrapper.kt`.

   This rule is enforced by `./gradlew checkConventions`
   (`tools/check-intent-gate.awk`): a bare `Timber.e(` is flagged everywhere
   unless it carries a `pre-wiring bare` marker within ±5 lines. The former
   crash-infra file whitelist is gone — those files use `reportToAcra`, which is
   not a bare `Timber.e`, so no whitelist entry is needed for new crash-infra.
   Same task also catches Rule 12, the data/ Manager-naming drift, the Rule-11
   annotation discipline in whitelisted files (see Rule 11 below), and Toast
   routing (bare `Toast.makeText(` outside `ui/util/ToastSafe.kt` — every
   user-facing toast must go through `showToastSafe`, which owns the Samsung
   StrictMode relax + the Throwable catch); see TODO.md §7 for the full list of
   automated checks.

10. **Testable logic lives outside Android-runtime classes.** Activities,
    Fragments, BroadcastReceivers, and Services are awkward to unit-test
    on the JVM. Anything worth pinning (state transitions, decisions,
    parsing, side-effect orchestration) belongs in a ViewModel, use case,
    helper, or `ui/main/delegate/` sibling. The Android-runtime class
    becomes thin glue: collect StateFlow, render, forward events. UI-only
    behavior (view inflation, animation callbacks) stays put — no test
    value to extract.

    JVM is the default test target — fast, deterministic, and covers
    everything the rule above puts there. `androidTest/` was abandoned
    for ~6 months after a deletion event of ~500 instrumented tests
    (constant flakiness, debug sessions that ate days; full account in
    `HISTORY.md`). That purge was driven by AUTHORING and MAINTENANCE
    cost under an older toolchain and assistant generation — and that
    cost has since collapsed (AGP orchestrator + `clearPackageData`
    isolation + `numFlakyTestAttempts` + `awaitUntil`, plus AI-assisted
    authoring/diagnosis; the AUDIT-14 F3 hit-test pair was written and
    root-caused in minutes, not days). So the bar is now a **value bar,
    not a cost bar**: reach for `androidTest/` whenever a path needs a
    real device to be true — touch dispatch / gesture priority
    (`HomeGestureLayout` hit-test), real `Canvas`/`Bitmap` semantics,
    genuine framework/OEM callbacks — and don't hold back on effort
    grounds; the maintenance tax that made the old bar strict is gone.

    The one thing that has NOT changed is the **redundancy guardrail**:
    an instrumented test that only re-checks pure in-memory or
    framework-data logic already pinned on the JVM/Robolectric adds no
    signal, however cheap it is to write — so it still doesn't belong.
    AUDIT-14 F3 is the worked example of the line: the long-press
    hit-test (favorite vs. wrapper customize dialog) earned two
    instrumented tests because touch priority only exists on a device;
    the `ColorStateList` memo did NOT, because Robolectric pins its
    identity + colours identically. The deciding question is "does this
    need a device to be true?", never "is a device test affordable?".
    Robolectric (already used in `test/` for `android.net.Uri`-touching
    code) remains the first stop when leaving the JVM.

    Applies to both new code and existing classes; if you spot a
    violation, lift the logic out.

11. **No `try/catch` around can't-throw operations.** Catches are for
    real failure modes — I/O, system APIs, user-supplied callbacks,
    layout inflation, fragment-state races. They are NOT a "just in
    case" wrapper. Catches around pure operations (`.filter`, `.map`,
    `.sortedBy`, `String.lowercase()`, `View.setText()`,
    `Bundle.putInt()`, property writes) fire only on programmer error
    — and silently swallowing programmer errors is exactly the failure
    mode `silentError` (Rule 9) is built to surface in DEBUG. Let them
    propagate to the layer's safety net (`Flow.catch`, `launchSafe`,
    `coroutineExceptionHandler`) and `silentError` will make them loud.

    The four-category decision frame from `HomeFragment.kt:157-208`
    is the standard reference: expected error → catch the most specific
    exception type, teardown race → restructure with `_binding?.let { }`
    and `viewLifecycleOwner.lifecycleScope` instead of catching after
    the fact, programmer error → don't catch, unrecoverable
    (lying-state path) → `silentDeath` not `silentError`. Exception
    to all of the above: `KolibriLauncherApp` keeps its multi-layer
    paranoia per Rule 7. The throwable-audit (TODO §2) removed 79
    such catches across nine files — don't re-add the pattern in
    new code.

    **Annotation-discipline linter (positive list).** `./gradlew
    checkConventions` enforces the marker phrase on broad catches
    (`Throwable` / `Exception`) in files that opted in to the
    convention. Today the whitelist is MainActivity,
    WallpaperEditController, and HomeFragment — the last added in the
    AUDIT-12 rollout once its catches were reviewed under the same lens
    (fitting, since HomeFragment is the four-category reference file
    itself).
    Accepted markers within ±5 lines of the catch (symmetric window):
    `[Cc]atch[a-z]* kept` (covers "Catch kept", "Inner catch kept",
    "Triple-catch kept", "Outer Catchall kept") or
    `[Rr]ethrow per canonical` (CancellationException rethrow). To add
    a file to the whitelist: confirm every broad catch has an inline
    annotation, then append the path to the `rule11_files` array in
    `tools/check-conventions.sh`. Narrowed exception types are not
    flagged — narrowing IS following Rule 11. The detection logic
    itself lives in `tools/check-rule11-annotation.awk`; regression-
    test any regex change via `tools/check-conventions-test.sh` (not
    wired into CI, manual rerun).

    **Cancellation-rethrow linter (positive list).** The coroutine form
    of the above, and the one defect in this rule that is *invisible in
    a diff*. A `catch (e: Throwable)` is harmless while its try-block
    has no suspension point — no `CancellationException` can reach it.
    The day a call inside that block becomes `suspend`, the untouched
    catch silently turns into a cancellation swallower: normal coroutine
    control flow gets logged as a crash, and the already-cancelled job
    keeps working. The catch line never changes, so review sees nothing.
    This has bitten the project three times (`e1ef671d`, `fb62b88d`,
    and `4c09c30b` → `WallpaperViewBinder.applyFullRebuild`, where every
    latest-wins wallpaper switch filed a bogus ACRA report per layer).

    So in a whitelisted file, **every** broad catch must declare which
    case it is: either it sits behind a `catch (e: CancellationException)
    { throw e }` arm on the same try (satisfied structurally, no comment
    needed), or it carries the marker `no suspension point` within ±5
    lines. Deliberately aggressive rather than detecting suspend calls:
    a suspend call has no syntactic tell — `bitmapLoader.load(uri)`
    reads exactly like a blocking call — so any detector keyed on
    `withContext` / `.await()` / `delay` would have missed the very bug
    that motivated the check. Whitelist today (AUDIT-12 rollout):
    `WallpaperViewBinder.kt`, `MainActivity.kt`, `AppDrawerFragment.kt`,
    `InstalledAppsRepositoryImpl.kt`, `BackupRepositoryImpl.kt`,
    `HomeFragment.kt`, `TimeBasedEventsRepositoryImpl.kt`,
    `FlowCollection.kt` (the shared `collectOnStarted` helper — every
    view-lifecycle collector in the app funnels through it, so its
    two-layer CancellationException rethrow is the highest-blast-radius
    arm to keep intact), and the suspend-I/O repos
    `WallpaperRepositoryImpl.kt`, `UsageExportRepositoryImpl.kt`,
    `PackageUpdateReceiver.kt` (locked for the broad catches that sit in a
    suspend frame yet guard only a non-suspend call today — the shape that
    would flip invisibly if that call became `suspend`), and the
    coroutine safety-net base classes `BaseActivity.kt` and
    `BaseViewModel.kt` (the highest-blast-radius arm of all — every
    activity/ViewModel coroutine funnels through their `launchSafe` /
    `executeSafe` / collector wrappers; the broad `Throwable` catches
    already sit behind `CancellationException`-first arms, and the two
    non-suspend-guarding catches — `finish()` in `BaseActivity`, the
    nested `onError`/`handleError` catch in `BaseViewModel.executeSafe` —
    carry the `no suspension point` marker), two `LauncherViewModel`
    delegates: `ClockDelegate.kt` (two non-suspend battery catches around
    `registerReceiver` / Intent-extra reads, `no suspension point`
    markers) and `WallpaperDelegate.kt` (whose `getDisplayName`
    `withContext(ioDispatcher)` block was an actual live swallower — a
    broad `catch (Throwable) { null }` sitting in a suspend frame; fixed
    by a `CancellationException`-first rethrow arm so cancellation
    propagates instead of collapsing to `null`), and the backup /
    custom-names surfaces added in the codebase-wide scan:
    `BackupFragment.kt` (its `showImportOptionsDialog`
    `viewLifecycleOwner.lifecycleScope.launch` block was a **live**
    swallower — the `catch (Throwable)` closed a try whose body suspends
    on `withTimeoutOrNull { backupPreview.first { … } }`, so a
    view-teardown cancellation was `silentError`-reported as a crash;
    fixed with a `CancellationException`-first arm), `BackupViewModel.kt`
    (already rethrew, but a glued/mis-indented `}catch` was defeating the
    detector — re-formatted, not a behaviour change), `CustomNamesActivity.kt`
    (all non-suspend / `no suspension point` markers — the inner
    `showSoftInput` catch guards a blocking IPC while its `delay` sits
    outside the try), and `SettingsFragment.kt` (25 non-suspend view /
    listener / lifecycle catches carry `no suspension point` markers; the
    one suspend-frame catch, the `showSortFavoritesFragment` fragment
    transaction, gained a `CancellationException`-first arm to match its
    three siblings); append to the
    `cancel_files` array in
    `tools/check-conventions.sh` to add a file (adding one with
    unreviewed catches just turns the build red — that IS the review
    prompt). Logic in `tools/check-cancellation-rethrow.awk`, whose
    case-(a) walk is indentation-based so a cancellation-first arm still
    satisfies an umbrella `catch (Throwable)` sitting behind typed arms
    (the stacked-catch shape in `BackupRepositoryImpl.saveBackupToFile`);
    regression-tested via `tools/check-cancellation-rethrow-test.sh`
    (manual rerun, not a CI gate).

    **The `Flow.catch { }` operator is the other cancellation swallower,
    and it is enforced GLOBALLY (not a positive list).** The `catch (Type)`
    walker above cannot see it — `Flow.catch` is a method call, not a
    catch-clause. Yet it delivers a `CancellationException` to its lambda
    whenever the cancellation originates UPSTREAM rather than from the
    downstream `emit` (the `stateIn(SharingStarted.Eagerly)` teardown case:
    the flow sits idle in the upstream, so the cancel surfaces there). An
    arm that then LOGS the exception (`silentError`, `KolibriLog.w`/`.e`,
    `Timber.w`/`.e`) without first rethrowing the cancellation SWALLOWS normal
    coroutine control flow: a teardown cancellation is turned into a logged
    fallback emission instead of propagating — a structured-concurrency
    correctness bug, one per arm. (Since §23, report-by-intent: only the
    `silentError` arm additionally reaches ACRA — the `Timber.w`/`KolibriLog.w`
    arms are untagged and no longer reported — but the correctness bug is
    identical for every logging arm, so the guard is required regardless of
    level; ACRA flooding is no longer the reason.) So every logging `Flow.catch`
    arm must guard first: `if (e is CancellationException) throw e` (the
    `SettingsRepositoryImpl` form), or a narrowing rethrow that propagates
    everything non-recoverable (`if (e is IOException) { … } else throw e`, as in
    `DataStoreReadFlow` / `AppUsageRepositoryImpl`). Enforced by
    `./gradlew checkConventions` via `tools/check-flow-catch-rethrow.awk` — a
    GLOBAL scan, because the shape is precise: only an arm that BOTH logs AND
    lacks a guard flags; a `.catch { }` that merely emits a fallback without
    logging cannot mask a cancellation as an error and is left alone. (The AUDIT-12 follow-on
    swept 17 such arms across `LayoutDelegate`, the `Get*AppsUseCase`
    family, `ObserveInstalledAppsUseCase`, and `InstalledAppsRepositoryImpl`
    — the last already guarded the `emit()` cancellation but still logged
    the incoming `e` unguarded.) Regression-tested via
    `tools/check-flow-catch-rethrow-test.sh` (manual rerun, not a CI gate).

    **Discovery — when a refactor may need a NEW `cancel_files` entry.**
    Because the whitelist is a positive list, the linter is blind to a
    broad catch in a file it does not list: a rewrite that gives a
    previously-synchronous file coroutine work — a new
    `launch`/`async`/`withContext`/`suspend fun`, or making an existing
    call inside a broad catch `suspend` — introduces exactly the
    invisible-flip risk with nothing to flag it. There is no automatic
    detector (a suspend call has no syntactic tell — that is the whole
    reason the check is a positive list, not a global sweep). So the
    trigger is manual and yours to run: **after such a refactor, run
    `./gradlew scanCancelCandidates`** (script
    `tools/scan-cancel-candidates.sh`). It sweeps every non-whitelisted
    main source for the broad-catch shape the linter would flag and ranks
    files by coroutine density so likely suspend-frame swallowers surface
    first; it is report-only and never fails the build (deliberately not
    wired into `checkConventions`/CI — a 36-file wall of non-suspend
    catches would be noise, not a gate). Triage its output with the
    AUDIT-12 lens: a broad catch physically inside a suspend frame with
    no `CancellationException` arm → fix it (rethrow arm) and/or add the
    file here; a non-suspend catch → leave it. Crash-infra files (Rule 9)
    are excluded from the scan as known-intentional.

    **A caught failure must stay a failure — return it, don't erase
    it.** The sanctioned alternative to propagating an exception is
    reporting the outcome as a value, not collapsing it into a
    plausible-looking default. `CrashReportConsentRepository` is the
    reference: `readState()` returns `ConsentReadResult`
    (`Loaded`/`Unavailable`), `setConsent()` returns
    `ConsentWriteResult` (`Saved`/`Failed`), neither throws for I/O,
    and `CancellationException` still propagates on every path. The
    reason is not tidiness — a default that is indistinguishable from
    a real answer *acts*: an unreadable consent store collapsed to
    "not asked yet" would make the startup gate show a dialog whose
    answer overwrites the decision the user already made (AUDIT-10
    #2), and a write that returns `Unit` on failure lets the caller
    assume a save that never happened (AUDIT-10 #11). Ask what the
    fallback *causes* downstream, not just whether it looks safe. The
    `catch (Exception)` blocks in `CrashReportConsentRepositoryImpl` are
    this contract, not an accidental swallow. (The tri-state is confined to
    `readState`; there are no boolean convenience getters — the live
    consumers derive what they need from the `ConsentDecision`.) Note where each half is pinned: the contract
    test only covers the SUCCESS shapes (`Loaded`, `Saved`) across fake
    and impl — the failure branches are impl-only I/O and live in
    `CrashReportConsentRepositoryImplTest` alone, which is therefore
    the file that must go red if someone re-collapses a failure into a
    default.

12. **Use the short Timber call form.** Write `Timber.d(...)`,
    `Timber.w(...)`, `Timber.tag(...).e(...)` etc. — not
    `Timber.Forest.d(...)`. `Forest` is the static inner class that
    Timber's Java API exposes; the Kotlin compiler resolves the short
    form to it, so both compile to identical bytecode. The short form
    is what Timber's own README and Jake Wharton's examples use, and
    it's what the rest of this codebase uses after the §6(e) sweep.
    `Timber.Forest.*` typically sneaks in via Android Studio's
    "Convert Java to Kotlin" or auto-complete after typing
    `Timber.For…` — reject it in review.

13. **New comments and KDoc are English; existing German comments
    stay.** Source-code comments and KDoc that you add or rewrite
    must be in English (the same goes for new log messages and
    any other in-code text). Pre-existing German comments are
    intentionally **not** swept — both maintainers read German
    fluently, the diff would be enormous, and a half-finished sweep
    would just create new mixed-language files. So: don't translate
    German comments you happen to pass by; only the lines you
    genuinely add or rewrite need to be English. UI strings always
    live in `res/values/strings.xml` + `res/values-de/strings.xml`
    (per the Localization section below) and are unaffected by
    this rule.

    Enforced by `./gradlew checkRule13` — a git-diff-aware linter
    (`tools/check-rule13-german-comments.{sh,awk}`) that flags `+`
    lines containing umlauts or ≥2 high-precision German function
    words. Default base is `origin/main`, override with
    `CHECK_BASE=<ref>`. The historical post-Rule-13 sweep ran
    against `a65a6b2` (2026-05-01, the rule's introduction commit)
    and left the codebase clean from that cutoff forward; anything
    earlier is grandfathered legacy.

### Event-bus SharedFlows must be buffered (enforced)

A `MutableSharedFlow(...)` built with the default zero buffer
(`replay = 0, extraBufferCapacity = 0`) is a **rendezvous** flow: `emit`
suspends until a collector is present, so an event fired while nobody is
subscribed is silently **dropped** (or, under a `withTimeout`, cancelled and
lost). For a lifecycle-independent producer — a `BroadcastReceiver`, a process
just started by the very broadcast it must react to — that is a latent
lost-update bug. AUDIT-9 #5 was exactly this: `AppUpdateSignal._events` was an
unbuffered `MutableSharedFlow<PackageEvent>()` while its siblings
(`AppUpdateModule` `extraBufferCapacity = 1`, `ErrorEventBus` `replay = 5`)
were correctly buffered; fixed to `extraBufferCapacity = 1`.

`./gradlew checkConventions` enforces this globally over main sources
(`tools/check-unbuffered-sharedflow.awk`): any `MutableSharedFlow(...)`
**construction** with a provably-zero buffer flags — type annotations
(`: MutableSharedFlow<Unit>`), imports and comment mentions never do. Give the
flow a real buffer (`extraBufferCapacity`/`replay`), or, if a collector is
provably always active before any emit so a drop cannot happen, record that
with a `rendezvous intended` marker within ±2 lines. Test buffering stays out
of scope (unbuffered-with-immediate-collector is legitimate and fails fast —
the test hangs; see `TESTING_CONVENTIONS.kt` §2). Regression-tested via
`tools/check-unbuffered-sharedflow-test.sh` (manual rerun, not a CI gate).

### Fragment teardown discipline (enforced)

Two mechanical view-lifecycle gates, both currently green, both in
`checkConventions`:

- **`registerForActivityResult()` placement**
  (`tools/check-activity-result-placement.awk`). The call must be a field
  initializer or run in `onCreate`; from `onViewCreated` / `onResume` / etc.
  it throws *"LifecycleOwners must call register before they are STARTED"* on
  the next config change (AUDIT-5 #2). The check resolves each call's
  innermost enclosing function by brace depth and flags only the forbidden
  lifecycle methods — a field or a plain helper is fine.

- **RecyclerView adapter null-out** (`tools/check-adapter-nulling.awk`). A
  Fragment that assigns a non-null `recyclerView.adapter` must clear it
  (`adapter = null`) in `onDestroyView`, or the view tree leaks across the
  view-recreation cycle (AUDIT-3 #13, AUDIT-4 #3); `AppDrawerFragment` is the
  reference. Fragments only — Activities clear in `onDestroy` and are out of
  scope. Escape hatch for an adapter provably owned elsewhere: an
  `adapter-nulling n/a` marker on the assignment line.

Both are regression-tested via `tools/check-fragment-teardown-test.sh` (manual
rerun, not a CI gate).

### Exception-vs-Throwable breadth (enforced — Rule 11 sibling)

`OutOfMemoryError` (and every `Error`) is a `Throwable`, **not** an `Exception`.
So `catch (e: Exception)` wrapped around an ALLOCATION or system-callback
boundary — bitmap decode/compose, layout inflation, large JSON/ZIP encoding —
silently misses the exact failure it was written to survive. AUDIT.md category
A was this shape ~14 times (`ZoomableImageView` bitmap paths,
`BackupRepositoryImpl`'s JSON/ZIP umbrella, `AppContextMenuDialogFragment`
inflate/bind); the fix widened those umbrellas to `catch (e: Throwable)`. This
is the exact complement of the cancellation checks: those guard a catch that is
too BROAD (Throwable swallowing `CancellationException`); this guards one too
NARROW (Exception missing `OutOfMemoryError`) — widen to Throwable and, in a
suspend frame, the resulting broad catch is handed to the cancellation check.

`./gradlew checkConventions` enforces it as a **positive list**
(`tools/check-exception-breadth.awk`), same growth model as the Rule 11 /
cancellation whitelists: a file joins only once every broad catch on its
allocation boundaries is `Throwable`, a narrowed type, or — where `Exception`
is genuinely right (a pure-I/O boundary, e.g. a `contentResolver.openInputStream`
probe, where no `Error` can arise) — carries an `Exception sufficient` marker
within ±5 lines. A bare `catch (e: Exception)` with no marker flags. It is
deliberately NOT a global scan: most `catch (Exception)` in the tree are correct
— narrow race guards (RecyclerView `notifyItem*` / `submitList` throwing
`IllegalStateException`, where `Throwable` would WRONGLY swallow OOM) or pure
I/O — so only files that ARE allocation boundaries belong on the list. Today:
`ZoomableImageView`, `AppContextMenuDialogFragment` (both already Throwable-only;
the entry locks the AUDIT category-A remediation against regression), and
`BackupRepositoryImpl` — the first file to join via the MARKER route rather than
Throwable-only, and the reason the two markers are separate tokens. Its real
allocation catches (the JSON/ZIP umbrella, the per-layer bitmap copy) were
already widened to `Throwable`; what remained were five pure-I/O probes
(`openInputStream` / `openFileDescriptor` / `toUri`) whose comments read
"synchronous I/O only" — a CANCELLATION statement, because the file is also on
the `cancel_files` list. Reusing that phrase as a breadth marker would have made
every catch commented for cancellation reasons count as OOM-reviewed
(false negatives), so each of the five gained its own `Exception sufficient`
line alongside the existing one. Same file, two whitelists, two independently
argued axes — that is the intended shape, not a duplication. Alongside it the
allocation core joined as pure regression locks, all already `Throwable`-only:
`WallpaperFileManager`, `WallpaperRepositoryImpl`, `WallpaperBitmapLuminanceImpl`
(bitmap copy / decode / luminance) and `AnrReporter` (trace read). The inflate-only
view classes (`MainActivity`, `SettingsFragment`, `AppDrawerFragment`, …) were
deliberately NOT added despite being clean today — half of `ui/` on the list would
make "is listed" stop meaning anything. Append to
the `oom_files` array in `tools/check-conventions.sh` to add a file. Regression-
tested via `tools/check-exception-breadth-test.sh` (manual rerun, not a CI gate).

**Discovery — `./gradlew scanOomCandidates`.** Sibling of `scanCancelCandidates`
for this axis (`tools/scan-oom-candidates.sh`, report-only, never fails the
build). Sweeps every non-whitelisted main source for a bare `catch (e: Exception)`
and ranks by ALLOCATION density (bitmap / inflate / JSON / ZIP / bulk-read lines)
rather than hit count — hit count is dominated by adapters full of legitimate race
guards, while density is the proxy for "this file allocates", which is the only
reason the breadth question exists. Run it after adding code that decodes a
bitmap, inflates a layout, or parses a large JSON/ZIP payload. Triage: an
`Exception` catch around an allocation → widen to `Throwable`; a narrow race guard
(`notifyItem*` / `submitList` / `getItem` under a list swap) → **leave it**,
`Throwable` there would swallow the OOM this check exists to surface; a pure-I/O
probe → keep `Exception` plus the marker if the file joins. `AppDrawerAdapter` and
`FavoritesAdapter` rank high in that scan and are the standing leave-it case —
they inflate in `onCreateViewHolder`, but the flagged catches are the race guards,
not the inflate. Density is a file-level proxy, not a per-catch verdict.

*The AUDIT category-A find this discovery scan would have caught:*
`UsageExportRepositoryImpl.parseUsageData` built a `JSONObject` graph plus boxed
`Long` lists from a string capped only at `MAX_BACKUP_SIZE_BYTES` — the live
object graph runs well above the raw string size — under a `catch (e: Exception)`.
Identical shape to the `BackupRepositoryImpl` JSON/ZIP umbrella, missed because
the file was not on the list. Widened to `Throwable`; its existing `no suspension
point` marker had to move ABOVE the new rationale comment to stay inside the
cancellation check's ±5-line window (that file is on `cancel_files` too).

*Not turned into gates (deliberately):* "raw `lifecycleScope.launch` outside a
`launchSafe`" and "flow `.collect` outside `collectOnStarted`" were evaluated
and rejected — the view layer has no `launchSafe` extension, so raw
`lifecycleScope.launch` guarded by a `CoroutineExceptionHandler` **is** the
standing idiom (~24 legitimate sites), and every raw-collect site is a
legitimate multi-sub-launch / nested-coroutine case the helper explicitly
excludes. A gate there would be all-noise, against the "precise shape" rule
the other checks hold to.

---

## StrictMode violations: known unfixables

`KNOWN_ISSUES.md` documents StrictMode violations caused by the Android
framework or Samsung modifications (OneUI `IdsController`, Knox
`resolveActivity`). They cannot be fixed in app code; one is mitigated via a
`Dispatchers.IO` wrap. Before chasing a StrictMode warning, cross-check
`KNOWN_ISSUES.md`.

## Accepted UX limitations

`ACCEPTED_LIMITATIONS.md` is the sister doc for *intentional* UX or
behavioural limitations that are direct consequences of an architectural
decision (e.g., the AppDrawer AUTO-mode classifier not compositing
multi-layer wallpapers before choosing a light/dark surface). Each entry
carries the rationale and a re-evaluation trigger. Before "fixing" a
perceived UX bug that touches accessibility, wallpaper classification, or
the home/keyguard boundary, cross-check `ACCEPTED_LIMITATIONS.md`.

## OEM / framework quirks worked around

`KNOWN_QUIRKS.md` is the third sibling of the two docs above (StrictMode /
accepted-limitations): it documents OEM- or framework-specific behaviours
that Kolibri *actively works around in code* — the distinction is the verb,
a quirk here has a live workaround whose logic must not be reverted by
someone who only sees odd-looking filter code. First entry: Samsung
Calendar's midnight-rollover phantom "alarm" at 00:00 (it misuses
`setAlarmClock()`, so `getNextAlarmClock()` returns it on Samsung devices),
filtered in `TimeBasedEventsRepositoryImpl` by the `showIntent` creator
package. Before "simplifying" a package-blocklist or a source-filter that
looks arbitrary, cross-check `KNOWN_QUIRKS.md`.

---

## Test conventions (short version)

The full reference lives in `app/src/test/CLAUDE.md` and `TESTING_CONVENTIONS.kt`.
The non-negotiable points only:

### The dispatcher rule (non-negotiable)

Not restated here — the authoritative version lives in the test docs, and
duplicating it only invites drift (AUDIT-13). See the top-of-file
`COROUTINE TEST DISPATCHER` block in `TESTING_CONVENTIONS.kt` (rules 1–6) and
`app/src/test/CLAUDE.md`. TL;DR from there: **use ONE dispatcher source
(`mainDispatcherRule.testDispatcher`) everywhere — never a second instance.**

### Other mandatory points

Named here; the authoritative statement **and the fix for each** live once in
the test docs — same delegation as the dispatcher rule above, so a change is
edited in one place, not three:

- **MockK only, never Mockito** — `TESTING_CONVENTIONS.kt` (MOCKK section).
- **The two coroutine-test traps** — `advanceUntilIdle()` against a
  `WhileSubscribed` flow, and an unbuffered `MutableSharedFlow()` with no
  subscriber in test setup — with their fixes in `TESTING_CONVENTIONS.kt` and
  `app/src/test/CLAUDE.md`.
- **Contract-test triple for every new repository interface** (Rule 2) —
  pattern and justified exemptions in `app/src/test/CLAUDE.md`.

> The old *"repository takes a `shareIn` external-scope parameter → set
> `externalScope = null` in tests"* note was removed: the DATASTORE_READ_SPEC
> Belang A refactor flipped every DataStore-backed repo to a cold flow, so no
> repo takes an `externalScope`/`sharingStrategy` constructor param anymore and
> the `*ShareInTest` files are gone.

---

## Localization

`res/values/strings.xml` (default, English) and `res/values-de/strings.xml`
(German). When changing UI, update both locales — no hardcoded strings in
Kotlin or XML.

**Parity is enforced** by `./gradlew checkConventions`
(`tools/check-strings-parity.awk`, covering `strings.xml` and `arrays.xml`).
Both drift directions flag, because they are different defects: a key only in
`values/` ships English text to a German device without failing anything at
compile time (`stringResource` falls back to the default locale), and a key only
in `values-de/` is dead weight, usually the fossil of a renamed English twin.
`<string>`, `<string-array>` and `<plurals>` are all covered at name level;
per-quantity coverage inside a `<plurals>` is not checked, since German and
English share the same one/other split.

`translatable="false"` in the DEFAULT locale is the exemption — the platform's
own way of marking something as not-UI-prose. 2 keys carry it today
(`time_placeholder`, `battery_placeholder`) and are correctly absent from
`values-de/`. (The 12 `blend_mode_*` names were removed with the per-layer
blend-mode machinery on 2026-08-20.) An orphan in `values-de/` is never exempt: a locale
override of a non-translatable key is itself the bug.

Unlike the catch-breadth axes this is a GLOBAL check, not a positive list —
a key is either in both files or it is not, and the `translatable` attribute
already encodes every legitimate exception, so there is no judgement call for a
whitelist to record. The tree was parity-clean when the check landed, so it
locks that state rather than reporting debt. Regression-tested via
`tools/check-strings-parity-test.sh` (manual rerun, not a CI gate).

---

## Versioning

`versionName` and `versionCode` live in `app/build.gradle.kts` — that file is
the single source of truth, so read the current version there rather than from
any doc. The GitHub release tags mirror `versionName` exactly. The
`uploadProguardMapping` task fires automatically after `assembleRelease`
or `bundleRelease` and pushes the mapping to ACRA.

**Regenerate the baseline profile before a release build.** The release baseline
profile is a local, **gitignored** build artifact: nothing auto-generates it
during the build (no `automaticGenerationDuringBuild`) and it is not committed.
So `bundleRelease` on a fresh checkout (or after a `build/` clean) bakes only the
library-default ART rules (~2.7k lines vs the ~15k captured profile) — a
silently degraded cold-start in the shipped AAB. Run
`./gradlew :app:generateBaselineProfile` (connected device) first, then
`bundleRelease`. Same trap surfaces the cold-start perf gate as a false
"REGRESS" — see `docs/specs/PERF-BENCHMARK-SETUP.md`.

**Public vs. personal release — the `devCommands` flag.** Settings carries three
ACRA test-trigger dev commands (throw / silent-error / warn). They are gated by
`BuildConfig.SHOW_DEV_COMMANDS`, whose release value is **public-safe by
default**: a plain `./gradlew bundleRelease` compiles them out, so the AAB
uploaded to GitHub never exposes them. To build your **personal** AAB with the
dev commands present, pass `-PdevCommands` (bare, or `=true`):
`./gradlew bundleRelease -PdevCommands` — or set `devCommands=true` in your
**global** `~/.gradle/gradle.properties` (outside the repo) so every local
release keeps them without typing. Absence of the flag = off, so forgetting it
can only ever produce a public-safe build, never leak. The read-only
`pipeline_status` probe is **not** gated (harmless in public). Debug builds
always show all four. Details in `app/build.gradle.kts` (the `devCommandsInRelease`
val) and `SettingsFragment` (the `SHOW_DEV_COMMANDS` branch).

**The `cacheToasts` flag.** The three wallpaper cache-diagnostic toasts in
`WallpaperDelegate` (single-layer hit / single-layer fill / composite fill, F10)
follow the exact same public-safe model via `BuildConfig.SHOW_CACHE_TOASTS`: a
plain release compiles them out (so the public AAB never toasts on every cache
op), a personal build re-enables them with `-PcacheToasts` (bare, or `=true`),
and debug always shows them. In a public release the field is a compile-time
`false` constant, so R8 strips the toast blocks entirely.

**The `dailyDriver` über-flag.** `-PdailyDriver` is the personal-build master
switch: it turns on **every** personal-only release toggle at once (currently
`SHOW_DEV_COMMANDS` + `SHOW_CACHE_TOASTS`) — each toggle is the OR of its own
property and `dailyDriver`, so the individual flags still work standalone. Build
the daily-driver AAB with `./gradlew bundleRelease -PdailyDriver`; a plain
`bundleRelease` stays public-safe. When adding a new personal-only release
surface, define it via the `personalProperty(...)` helper and OR in
`dailyDriverRelease` so `-PdailyDriver` keeps covering everything.

---

## Git workflow

The full rules — branch before non-trivial work, the `fix/refactor/feature/
chore/test` prefixes, "when in doubt ask before starting", and the
confirm-before-deleting-a-merged-branch step — live in the global
`~/.claude/CLAUDE.md` and are **not restated here** (they are identical across
every project in this family; duplicating them only invites drift).

Project delta: this is a **solo workflow** — no PRs; a branch merges directly
into `main` once the maintainer signs off (memory `feedback-git-workflow`).

---

## What this file is NOT

- Not a description of the project (see `README.md`).
- Not a TODO list (see `TODO.md`).
- Not the full testing reference (see `app/src/test/CLAUDE.md` and
  `TESTING_CONVENTIONS.kt`).
- Not the place for known StrictMode issues (see `KNOWN_ISSUES.md`),
  intentional UX limitations (see `ACCEPTED_LIMITATIONS.md`), or
  OEM/framework quirks worked around in code (see `KNOWN_QUIRKS.md`).
- Not a holding pen for transient refactor notes — those belong in commit
  messages on short-lived feature branches.

Update this file when an architectural rule changes or a hard-won lesson
deserves to be future-proofed. Do not bloat it with details that are
obvious from reading the code.
