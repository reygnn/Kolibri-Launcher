# CLAUDE.md

Project conventions for **Kolibri Launcher** (a minimalist Android launcher,
GPLv3). Claude Code reads this file automatically at session start. Keep it
short and actionable — not a marketing description (that's `README.md`'s job).

For deep test conventions, see `app/src/test/CLAUDE.md` and
`app/src/test/java/com/github/reygnn/kolibri_launcher/TESTING_CONVENTIONS.kt`.
This file is the **project-wide** entry point; the other CLAUDE.md is the
testing reference.

---

## Stack

- Kotlin 2.2.21, Android Views + ViewBinding (**no Compose**), Material 3
- Min SDK 36 / Target 36 / Compile 36 — **Android 16 only**, no compatibility shims
- JDK 21 (Robolectric requires it for SDK 36)
- MVVM + Clean Architecture, **DI via Hilt 2.57.2**
- Persistence: Jetpack DataStore Preferences (no SharedPreferences)
- Crash reporting: ACRA 5.11.4, self-hosted, opt-in
- Coroutines/Flows throughout; UI state as `StateFlow`, events as `SharedFlow`
- Tests: JUnit 4, **MockK** (Mockito has been fully migrated away), Turbine,
  kotlinx-coroutines-test, Robolectric (only for `android.net.Uri`-touching
  managers)

## Build & test

```bash
./gradlew assembleDebug          # debug APK
./gradlew test                   # unit tests (JVM, no emulator)
./gradlew jacocoTestReport       # coverage report
./gradlew assembleRelease        # finalized: triggers ProGuard mapping upload to ACRA
```

Gradle is bundled via wrapper. `androidTest/` is currently empty —
instrumentation/UI tests are described in the README but no live tests are
in the tree.

---

## Architecture

```
core/                     AppConstants, TextColorCalculator, TimberWrapper, utils
data/                     manager implementations (FavoritesManager, …)
data/service/             ScreenLockManager pieces, service logic
domain/repository/        interfaces (FavoritesRepository, …) — 20 of them
domain/usecase/           ~50 fine-grained use cases (GetDrawerAppsUseCase, …)
domain/model/             data classes (AppInfo, HomeSettings, WallpaperState, …)
di/                       6 Hilt modules (App, DataStore, Dispatcher,
                          Repository, ViewModel, AppUpdate)
ui/                       features: home, appdrawer, settings, onboarding,
                          swipeactions, customnames, hiddenapps, backup,
                          colorcustomization, layoutcustomization, usageexport,
                          appcontextmenu, main (with delegate/ subpackage)
KolibriLauncherApp.kt     Application: Hilt entry, ACRA init, Timber trees,
                          ANRWatchDog, migration bootstrap
```

`MainActivity` is registered as both `LAUNCHER` and `HOME` intent (a real
default-launcher candidate). It hosts fragments via the Navigation Component.
Settings, Onboarding, HiddenApps, CustomNames, and SwipeActions are separate
activities.

---

## Hard architectural rules

1. **Repository interface, always.** Every data access goes through an
   interface in `domain/repository/`. Production implementations live in
   `data/` as `XyzManager`; test doubles live in
   `app/src/test/.../fakes/` as `FakeXyz...`. ViewModels and use cases depend
   only on the interface, never on the concrete manager.

2. **Contract-test triple per repository.** `XyzRepositoryContract` (abstract,
   `@Test` methods) + `FakeXyzRepositoryContractTest` +
   `XyzManagerContractTest`. If fake and manager drift, the contract catches
   it. Exceptions (system-API managers like `BackupRepository`,
   `InstalledAppsRepository`, `TimeBasedEventsRepository`) are justified in
   the corresponding contract KDoc. Details: `app/src/test/CLAUDE.md`.

3. **When a contract test goes red, the manager wins.** Align the fake to
   the manager, not the other way around — manager behavior is production
   truth. Intentional drifts get documented in the contract KDoc under
   "NOT IN CONTRACT — intentional drifts", **not** hidden.

4. **Hilt is the DI framework.** No second DI framework, no manual-DI
   refactor. Modules are split by responsibility in `di/`. `Repository ↔
   Manager` bindings always go in `RepositoryModule.kt`.

5. **DataStore Preferences is the only app storage for user state.** No
   `SharedPreferences`, no app-managed SQLite. Migrations run through
   `DataMigrationManager` on app start. Backup/restore goes through
   `BackupManager` + `DataStoreBackup`.

   *Two deliberate exceptions, both `SharedPreferences`-backed and both
   intentional:*

   - **`DataMigrationManager`** uses a small `SharedPreferences` file
     (`kolibri_data_version`) for the schema-version flag, because the
     migration mechanism cannot bootstrap itself through the very thing
     it might need to migrate. Chicken-and-egg by nature.

   - **`CrashReportLimiter`** uses `SharedPreferences` for ACRA crash-
     report rate-limit timestamps (`acra_report_limiter`), because its
     `shouldSendReport()` is called sync from the ACRA crash handler
     (a non-coroutine thread). DataStore's API is suspend-only, so
     migrating would require either a sync-blocking `runBlocking` (the
     StrictMode bug we want to avoid) or an in-memory write-through
     cache (extra source-of-truth, race-condition surface). Neither is
     worth it for ephemeral telemetry timestamps that may be lost on
     any app update.

   Both exceptions are explained in detail in the respective files'
   KDocs. Don't try to "fix" either.

6. **Respect the version pins in `app/build.gradle.kts`.** Many dependencies
   carry `DO NOT CHANGE` / `DO NOT UPGRADE` / `DO NOT DOWNGRADE` comments
   (Hilt 2.57.2, ACRA 5.11.4, Timber 5.0.1, fragment-ktx 1.8.9, JUnit 4.13.2,
   materialVersion 1.13.0, kotlinTestVersion 2.2.21, coreTestingVersion 2.2.0).
   These markers are not suggestions — violating them breaks builds or tests.
   `minSdk = compileSdk = targetSdk = 36` is also fixed.

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
   development. The exception is the global crash handler in
   `KolibriLauncherApp` itself (~7 spots), where `silentError` would
   recurse into its own crash path. For paths that must not continue with
   a lying state (half-migrated DataStore, Activity without ViewModel,
   HOME-Activity restart loop), use `silentDeath` — it logs FATAL and
   `exitProcess(1)`s in every build, even DEBUG, because outer
   `catch(Throwable)` blocks would otherwise swallow a throw. Both:
   details and the test escape (`preventCrashForTesting`) live in
   `core/TimberWrapper.kt`.

10. **Testable logic lives outside Android-runtime classes.** Activities,
    Fragments, BroadcastReceivers, and Services are awkward to unit-test
    on the JVM, and `androidTest/` is intentionally empty in this project
    — JVM is the only test target. Anything worth pinning (state
    transitions, decisions, parsing, side-effect orchestration) belongs in
    a ViewModel, use case, helper, or `ui/main/delegate/` sibling. The
    Android-runtime class becomes thin glue: collect StateFlow, render,
    forward events. UI-only behavior (view inflation, animation callbacks)
    stays put — no test value to extract. Applies to both new code and
    existing classes; if you spot a violation, lift the logic out.

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

---

## StrictMode violations: known unfixables

`KNOWN_ISSUES.md` documents StrictMode violations caused by the Android
framework or Samsung modifications (OneUI `IdsController`, Knox
`resolveActivity`). They cannot be fixed in app code; one is mitigated via a
`Dispatchers.IO` wrap. Before chasing a StrictMode warning, cross-check
`KNOWN_ISSUES.md`.

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
  set `externalScope = null`. See `FavoritesManagerShareInTest.kt`.
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
release tags mirror `versionName` exactly. Currently 0.99.61 / code 79.
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
- Not a holding pen for transient refactor notes — those belong in commit
  messages on short-lived feature branches.

Update this file when an architectural rule changes or a hard-won lesson
deserves to be future-proofed. Do not bloat it with details that are
obvious from reading the code.
