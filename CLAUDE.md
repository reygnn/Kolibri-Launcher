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

- Kotlin 2.3.21, Android Views + ViewBinding (**no Compose**), Material 3
- Min SDK 36 (Android 16); Target 37 / Compile 37 (Android 17) — lifted
  2026-07-18 for `core-ktx 1.19.0`, pinned in `gradle/libs.versions.toml`. No
  compatibility shims.
- JDK 21 (Robolectric requires it for SDK 36)
- MVVM + Clean Architecture, **DI via Hilt 2.60.1** (KSP2, kapt-free)
- Persistence: Jetpack DataStore Preferences (no SharedPreferences)
- Crash reporting: ACRA 5.13.1, self-hosted, opt-in
- Coroutines/Flows throughout; UI state as `StateFlow`, events as `SharedFlow`
- Tests: JUnit 4, **MockK** (Mockito has been fully migrated away), Turbine,
  kotlinx-coroutines-test, Robolectric (only for `android.net.Uri`-touching
  impls)

## Build & test

```bash
./gradlew assembleDebug          # debug APK
./gradlew test                   # unit tests (JVM, no emulator)
./gradlew jacocoTestReport       # coverage report
./gradlew checkConventions       # CLAUDE.md rule linter (Rule 9, 11, 12, naming)
./gradlew checkRule13            # diff-aware German-comment linter (Rule 13)
./gradlew assembleRelease        # finalized: triggers ProGuard mapping upload to ACRA
```

Gradle is bundled via wrapper. `androidTest/` was empty for ~6 months
after a flakiness-driven deletion event (~500 tests removed — see
Rule 10 below and `HISTORY.md`). Since late April 2026 it is being
reintroduced selectively, under a stricter bar: only genuinely
important paths that either deliver value JVM tests can't reach or
can't be covered reliably by Robolectric. Robolectric itself is
already in use in the JVM `test/` set for `android.net.Uri`-touching
code (e.g. `WallpaperRepositoryImplTest`).

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
  domain/repository/      interfaces (FavoritesRepository, …) — 20 of them
  domain/usecase/         ~50 fine-grained use cases (GetDrawerAppsUseCase, …)
  domain/model/           data classes (AppInfo, HomeSettings, UiState,
                          AppContextMenuAction + LauncherActionLabel,
                          WallpaperState, LauncherShortcut, …) — pure
                          Kotlin, no Parcelable / no Android imports.
  di/DispatcherModule     @Provides for Default/IO/Main + ApplicationScope

:data    (Android Library, depends on :domain)
  data/                   repository implementations (FavoritesRepositoryImpl,
                          BackupRepositoryImpl, …), CrashReportConsentStore,
                          PackageUpdateReceiver
  data/service/           ShortcutLauncherServiceImpl
  di/RepositoryModule     @Binds for every repository interface
  di/DataStoreModule      single Preferences DataStore + the internal
                          Context.settingsDataStore extension

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
   catches it. Exceptions (system-API impls like `BackupRepository`,
   `InstalledAppsRepository`, `TimeBasedEventsRepository`) are justified in
   the corresponding contract KDoc. Details: `app/src/test/CLAUDE.md`.

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

   *One deliberate exception, `SharedPreferences`-backed and intentional:*

   - **`CrashReportLimiter`** uses `SharedPreferences` for ACRA crash-
     report rate-limit timestamps (`acra_report_limiter`), because its
     `shouldSendReport()` is called sync from the ACRA crash handler
     (a non-coroutine thread). DataStore's API is suspend-only, so
     migrating would require either a sync-blocking `runBlocking` (the
     StrictMode bug we want to avoid) or an in-memory write-through
     cache (extra source-of-truth, race-condition surface). Neither is
     worth it for ephemeral telemetry timestamps that may be lost on
     any app update.

   This exception is explained in detail in the file's KDoc. Don't try
   to "fix" it.

   *Migration history note:* A `DataMigrationManager` class used to live
   in `data/` to bridge schema changes across app updates (most recently
   ACRA-consent V1→V2 in early May 2026). It was removed once every
   existing install had moved past V2 — since then, all new state lives
   only in DataStore, and any future schema change has to be designed to
   be additively backward-compatible. Git history under `data/` has the
   full implementation if a future migration ever needs the same shape
   back.

6. **Respect the version pins in `app/build.gradle.kts`.** Many dependencies
   carry `DO NOT CHANGE` / `DO NOT UPGRADE` / `DO NOT DOWNGRADE` comments
   (Hilt 2.60.1, ACRA 5.13.1, fragment-ktx 1.8.9, JUnit 4.13.2,
   materialVersion 1.14.0, kotlinTestVersion 2.3.21, coreTestingVersion 2.2.0).
   These markers are not suggestions — violating them breaks builds or tests.
   `minSdk = 36`, `compileSdk = targetSdk = 37` are also fixed.

7. **`KolibriLauncherApp` is multi-layer crash-safe by design.** The style
   (catching `Throwable` instead of `Exception`, the coroutine
   `ExceptionHandler`, the global handler, OOM recovery, ACRA spam protection
   via `CrashReportLimiter`) is intentional — do not "clean it up" or
   simplify. New critical init paths follow the same pattern: wrap each
   block in `try { … } catch (e: Throwable) { TimberWrapper.silentError(e, ...) }`.
   Inside the global crash handler itself, plain `Timber.e` is used —
   see Rule 9 for why.

8. **ACRA is opt-in (privacy-by-default).** Crash reporting is disabled
   immediately after init (`ACRA.errorReporter.setEnabled(false)`) and is
   only enabled when `CrashReportConsent.hasConsent()` returns `true`. Do
   not change this order. Reports go to a private server, never to a
   third-party service.

9. **Error logging goes through `TimberWrapper`.** Plain `Timber.e(...)`
   only logs — silent in DEBUG too. Use `TimberWrapper.silentError(e, ...)`
   instead: same logging, plus throw in DEBUG so bugs are loud during
   development.

   **Crash-handling-infrastructure exception list.** A handful of files
   sit *inside* the crash-reporting pipeline: a `silentError` throw there
   would either recurse into the same path it's supposed to be the safety
   net for, or land in ACRA's own machinery. These files keep plain
   `Timber.e`:

   - `KolibriLauncherApp.kt` — the global `UncaughtExceptionHandler` and
     ACRA init (~7 spots). The original Rule 9 exception.
   - `TimberWrapper.kt` itself — `silentError` calling itself is a literal
     loop.
   - `BaseActivity.kt` and `BaseViewModel.kt` — the
     `CoroutineExceptionHandler` and the `ErrorEventBus` collector. A
     throw here escapes the safety-net coroutine and lands at the global
     uncaught handler, which is exactly what these wrappers exist to
     prevent.
   - `CrashReportLimiter.kt`, `CrashReportConsentStore.kt`,
     `CrashReportConsent.kt` — all are on the bootstrap path. The
     limiter is called synchronously from ACRA's crash-handler thread
     (per Rule 5). The store's reads happen via `runBlocking` in
     `attachBaseContext` before Hilt and ACRA are initialised; a DEBUG
     throw there crashes the app before the reporter is wired. The
     dialog file is the UI passthrough and stays grandfathered for
     consistency until its catches migrate to `silentError`.
   - `BackupFragment.kt` — its fragment-scoped
     `CoroutineExceptionHandler` (one site only — the rest of the file
     uses `silentError`). Same recursion shape as `BaseActivity`.

   **Transitive form.** The recursion ban applies to helpers these files
   *invoke* too: a UI fallback called from a `CoroutineExceptionHandler`
   (e.g. `BackupFragment.showError`) must not contain `silentError`
   either, or the DEBUG throw simply lands one frame deeper and surfaces
   as `RuntimeException: Exception while trying to handle coroutine
   exception`. Document the constraint on the helper itself, not in this
   rule.

   New code in any other file: use `silentError`. Adding a new file to
   this list requires the same recursion-into-the-error-pipeline
   justification.

   For paths that must not continue with a lying state (half-migrated
   DataStore, Activity without ViewModel, HOME-Activity restart loop),
   use `silentDeath` — it logs FATAL and `exitProcess(1)`s in every
   build, even DEBUG, because outer `catch(Throwable)` blocks would
   otherwise swallow a throw. Both: details and the test escape
   (`preventCrashForTesting`) live in `core/TimberWrapper.kt`.

   This rule is enforced by `./gradlew checkConventions` — the linter
   reports any bare `Timber.e(` outside the exception list, so a new
   crash-infra file added here also needs an entry in
   `tools/check-conventions.sh`. Same task also catches Rule 12, the
   data/ Manager-naming drift, and the Rule-11 annotation discipline
   in whitelisted files (see Rule 11 below); see TODO.md §7 for the
   full list of automated checks.

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
    `HISTORY.md`). Since late April 2026 it is being reintroduced under
    a stricter bar: a path earns a place in `androidTest/` only if it
    is genuinely important AND either (a) delivers value that JVM tests
    can't reach, or (b) cannot be covered reliably by Robolectric. The
    previous "anything worth testing on a device" mode is over —
    instrumented tests are now the exception, not a parallel default.
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
    convention. Today the whitelist is MainActivity only; HomeFragment
    will follow once its catches are reviewed under the same lens.
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
decision (e.g., the residual wallpaper pop-in on double-tap-to-lock that
falls out of the AccessibilityService model the launcher uses to stay
banking-app-compatible). Each entry carries the rationale and a
re-evaluation trigger. Before "fixing" a perceived UX bug that touches
locking, accessibility, or the home/keyguard boundary, cross-check
`ACCEPTED_LIMITATIONS.md`.

---

## Test conventions (short version)

The full reference lives in `app/src/test/CLAUDE.md` and `TESTING_CONVENTIONS.kt`.
The non-negotiable points only:

### The dispatcher rule (non-negotiable)

```kotlin
@get:Rule val mainDispatcherRule = MainDispatcherRule()

@Test fun whatever() = runTest(mainDispatcherRule.dispatcher) {
    // ...
}
```

Plain `runTest { }` creates its own `TestCoroutineScheduler`, so
`advanceUntilIdle()` / `advanceTimeBy()` will not drive code that runs on
`Dispatchers.Main`. Tests become flaky.

### Other mandatory points

- **New tests are MockK only.** Mockito has been migrated away; do not
  bring it back.
- **Never use `advanceUntilIdle()` against a `WhileSubscribed` flow** — it
  runs past the timeout and drops the upstream. Use
  `testScheduler.runCurrent()` instead.
- **A `MutableSharedFlow()` with no subscriber in test setup → buffer
  overflow.** The first emission suspends. Fix: `BufferOverflow.DROP_OLDEST`
  or Turbine.
- **Repository takes a `shareIn` external-scope parameter?** In tests,
  set `externalScope = null`. See `FavoritesRepositoryImplShareInTest.kt`.
- **Contract-test pattern for every new repository interface.** No
  shortcuts unless there is a justified exception in the contract KDoc.

---

## Localization

`res/values/strings.xml` (default, English) and `res/values-de/strings.xml`
(German). When changing UI, update both locales — no hardcoded strings in
Kotlin or XML.

---

## Versioning

`versionName` and `versionCode` live in `app/build.gradle.kts`. The GitHub
release tags mirror `versionName` exactly. Currently 0.99.97 / code 117.
The `uploadProguardMapping` task fires automatically after `assembleRelease`
or `bundleRelease` and pushes the mapping to ACRA.

---

## Git workflow: branch before non-trivial work

Larger changes — **bigger bugfixes, refactorings, new features, anything that
touches multiple files or could plausibly be reverted as a unit** — must
happen on a dedicated branch, never directly on `main`. Trivial edits (typo
fix, single-line tweak, doc nit) can stay on the current branch.

When in doubt, **stop and ask the user before starting**. It is always
cheaper to confirm than to realize mid-implementation that the work is on
the wrong branch.

**Workflow:**

1. Before writing code, propose a branch name that fits the topic and ask
   for confirmation. Suggested prefixes:
   - `fix/<slug>` — bugfix
   - `refactor/<slug>` — refactoring
   - `feature/<slug>` — new feature
   - `chore/<slug>` — tooling, build, dependencies
   - `test/<slug>` — test-only changes
2. Create the branch from an up-to-date `main` (or the appropriate base)
   and switch to it before the first edit.
3. If you notice mid-task that you are still on `main`, stop and remind
   the user — do not silently keep working.

The branch-name proposal is a suggestion; the user has the final say.

**After a fast-forward merge into `main`:** switch back to `main` and ask
the user whether the merged branch should be deleted both locally and on
the remote. Do not delete branches silently with `git branch -d` or
`git push origin --delete` — even after a merge, an open PR, ongoing review,
or historical reference may still hang on the branch. Always confirm first.

---

## What this file is NOT

- Not a description of the project (see `README.md`).
- Not a TODO list (see `TODO.md`).
- Not the full testing reference (see `app/src/test/CLAUDE.md` and
  `TESTING_CONVENTIONS.kt`).
- Not the place for known StrictMode issues (see `KNOWN_ISSUES.md`)
  or intentional UX limitations (see `ACCEPTED_LIMITATIONS.md`).
- Not a holding pen for transient refactor notes — those belong in commit
  messages on short-lived feature branches.

Update this file when an architectural rule changes or a hard-won lesson
deserves to be future-proofed. Do not bloat it with details that are
obvious from reading the code.
