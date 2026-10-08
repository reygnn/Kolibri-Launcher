# CLAUDE.md — Kolibri Launcher

Kolibri specifics (a minimalist Android launcher, GPLv3). The **product-neutral rules**
— stack, the 13 hard rules and their enforced siblings, test conventions (short),
localization, git workflow, enforcement — live in the monorepo root `../CLAUDE.md`,
which Claude Code loads automatically alongside this file. Keep them there: a rule
written twice drifts (SPEC_NYX_REWRITE A4).

For deep test conventions, see `app/src/test/CLAUDE.md` and
`../core/src/testFixtures/java/com/github/reygnn/launcher/core/testing/TESTING_CONVENTIONS.kt`
(shared by all modules since SPEC_NYX_REWRITE 1a).

> **New or extended module?** Before creating or extending a shared/feature module, read
> `../docs/FEATURE_MODULE_GUIDE.md` (module map, gates, DI, data, concurrency, tests, process, checklist).

---

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
Rule 10 in `../CLAUDE.md` and `HISTORY.md`). Since late April 2026 it is being
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
                          SettingsRepository, …) — 17 here (18 *Repository
                          interfaces across :domain; the 18th,
                          CrashReportConsentRepository, lives under
                          crashreporting/consent/, not repository/)
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
  di/DataStoreModule      three Preferences DataStores + their internal
                          Context.settingsDataStore / .consentDataStore /
                          .usageDataStore extensions. All are Hilt-provided (the consent
                          one qualified, @ConsentDataStore, injected by
                          CrashReportConsentRepositoryImpl); consentDataStore
                          is a separate store (own backing file) so ACRA
                          consent stays privacy-by-default and out of Auto
                          Backup, and it is additionally read via its
                          extension on the pre-Hilt bootstrap path.
                          usageDataStore is likewise a separate store (own
                          backing file) holding the time-weighted app-usage
                          data — the AUDIT-19 F1 usage-store split (qualified
                          @UsageDataStore, provideUsageDataStore).

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
                          ANR drain (AnrReporter lives in :feature-crashreporting)
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

**The `cacheToasts` flag.** The wallpaper cache-diagnostic toast in
`WallpaperDelegate` (composite fill, F10 — the single-layer hit/fill toasts no longer
exist) follows the exact same public-safe model via `BuildConfig.SHOW_CACHE_TOASTS`: a
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

## What this file is NOT

- Not the place for rules that hold for both apps (see `../CLAUDE.md`).
- Not a description of the project (see `README.md`).
- Not a TODO list (see `TODO.md`).
- Not the full testing reference (see `app/src/test/CLAUDE.md` and
  `TESTING_CONVENTIONS.kt`).
- Not the place for known StrictMode issues (see `KNOWN_ISSUES.md`),
  intentional UX limitations (see `ACCEPTED_LIMITATIONS.md`),
  OEM/framework quirks worked around in code (see `KNOWN_QUIRKS.md`), or
  rare platform glitches with no workaround (see `KNOWN_OS_GLITCHES.md`).
- Not a holding pen for transient refactor notes — those belong in commit
  messages on short-lived feature branches.

Update this file when an architectural rule changes or a hard-won lesson
deserves to be future-proofed. Do not bloat it with details that are
obvious from reading the code.
