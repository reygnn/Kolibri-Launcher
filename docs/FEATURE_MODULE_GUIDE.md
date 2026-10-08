# Feature module guide

Read this before creating a new shared or feature module, or extending one.
It collects, in one place, rules that otherwise live across `CLAUDE.md`, `SPEC_NYX_REWRITE.md`
(in the bundle; mirrored to `docs/specs/`) and the gates in `tools/` — several of them learned
the expensive way in Phase 3 (wallpaper).
Each rule has one sentence of reason and a pointer to where it is decided or enforced.

**Maintenance:** whoever changes a gate, a ratchet or a convention updates this guide in the
same patch.

---

## 0. Module map

| Module | Kind | Plugin | Owns | Key building blocks | May depend on |
|---|---|---|---|---|---|
| `:core` | JVM | `launcher.jvm.library` | Domain types and contracts both apps share | `WallpaperState`, `WallpaperRepository`, `FabPositionRepository`, `Purgeable` + `purgeAll`, qualifiers (`@IoDispatcher`, `@MainDispatcher`, `@DefaultDispatcher`, `@ApplicationScope`, `@SettingsStore`), `DispatcherModule`, `CompositeLuminanceSignal`; testFixtures: `MainDispatcherRule`, `TESTING_CONVENTIONS.kt`, `FakeWallpaperRepository` | nothing |
| `:common-data` | Android | `launcher.android.library` | Android data plumbing both apps share | `WallpaperFileManager`, `WallpaperRepositoryImpl`, `WallpaperBitmapLuminanceImpl`, `SafDocuments`, DataStore read helpers | `:core` (api) |
| `:common-ui` | Android | `launcher.android.library` | Shared views and UI infrastructure | `ZoomableImageView`, `WallpaperViewBinder`, `WallpaperFlattener`, `WallpaperCompositeCache`, `LaunchTrace`, `WallpaperPaintTrace`, `WallpaperFlickerTrace`, `BaseViewModel`, the flatten theme | `:core` (api) |
| `:common-testing-android` | Android | `launcher.android.library` | Android test support (its `src/main` IS test code) | instrumented-test helpers | — |
| `:feature-crashreporting` | Android | `launcher.android.library` | ACRA, crash ingestion | `AnrReporter`, health notifier | `:core` (api), `:common-ui` |
| `:feature-backup` | JVM | `launcher.jvm.library` | Backup container engine (Phase 2a) | E5a container, staged blobs, backup contracts in testFixtures | `:core` |
| `:feature-wallpaper` | Android | `launcher.android.library` | The wallpaper core (Phase 3) | `WallpaperImageStore` + contract, `WallpaperEditSession`, `WallpaperOperations`, `WallpaperDisplaySettingsStore`, `FabPositionStore`, `WallpaperImagePicker`, `WallpaperBackupBlobs`, `WallpaperComposite` (+ `CachedWallpaperComposite`, `None`) | `:core` (api), `:common-data`, `:common-ui` |
| `:kolibri:backup-legacy` | Android | `launcher.android.library` | Pre-E5a backup reader, until its sunset | legacy format reader | `:core`, `:kolibri:domain`, `:kolibri:data`, `:feature-backup` |
| `:kolibri:{app,domain,data}`, `:nyx:{app,domain,data}` | app / JVM / Android | `launcher.android.application` / `launcher.jvm.library` / `launcher.android.library` | The two apps | — | shared modules |
| `:kolibri:macrobenchmark`, `:kolibri:baselineprofile` | Android test | `launcher.android.test` | Benchmarks, baseline profile | `WallpaperPaintBenchmark`, `WallpaperCompositeBenchmark` | the app under test |

`build-logic/` holds the convention plugins: `launcher.jvm.library`, `launcher.android.library`,
`launcher.android.test`, `launcher.android.application`, plus `Coverage.kt` (JaCoCo) and
`ReleaseRules.kt` (release build rules).
Build-wide values live there, never in a module (A10).

```
apps (kolibri, nyx)  →  feature-*  →  common-ui / common-data  →  core
                         (no edges between feature modules)
```

---

## 1. Module type

- **JVM (`launcher.jvm.library`)** unless the module needs Android types —
  `Uri`, `Bitmap`, `Context`, Activity results, DataStore on Android → **Android (`launcher.android.library`)**.
  Reason: JVM modules test without Robolectric and keep pure domains pure (an app's `:domain` is JVM and cannot depend on an Android library — the reason `FabPositionRepository` lives in `:core`, 3a-5).
- **No dependency between feature modules** (3a-7: `:feature-wallpaper` does not import `:feature-backup`).
  The apps compose features; modules talk through plain types and functions.
- `:core` as `api`; `:common-data` / `:common-ui` as `implementation`, only if needed.

## 2. Setting a module up

- `settings.gradle.kts`: `include(":your-module")`.
- Add it to `SHARED_MODULES` in `tools/check-conventions.sh`; the module-coverage guard fails for a module no gate covers.
- A10 build parity: compileSdk, minSdk, JVM target, Kotlin options come from the convention plugin, never from the module's `build.gradle.kts`.
- Coverage: an app's `jacocoTestReport` unions the app's own modules (`Coverage.kt`); a module whose exec files should be read applies `id("jacoco")`.
- testFixtures: Android modules need `android.experimental.enableTestFixturesKotlinSupport=true` (already in `gradle.properties`); keep JVM testFixtures pure (no Android types).
- Lint baselines are for app modules only; a shared module has no baseline to hide findings in.

## 3. Gates and ratchets

**Principle:** new entries in a ratchet (allowlist, baseline, whitelist) are forbidden; a patch that removes the last use of an entry removes the entry (the gate reports stale entries).
Every new detector comes with a counter-check (the bad case is flagged) in its self-test, and the counter-check belongs to the patch.

- **Shared lint lists** (`tools/shared-lint-files.sh`): a shared file goes into
  `SHARED_CANCEL_FILES` (launches/collects that must rethrow `CancellationException`),
  `SHARED_OOM_FILES` (allocation boundaries that may catch `OutOfMemoryError`),
  `SHARED_RULE11_FILES` (`catch` blocks that need the four-category note),
  `SHARED_INITORDER_FILES` (init-order launch whitelist) — when it does what the list is about.
- **A13** — no hard-coded dispatcher (`Dispatchers.IO` etc.) and no new `CoroutineScope(...)`: inject `@IoDispatcher` / `@MainDispatcher` / `@DefaultDispatcher`, use `@ApplicationScope` (`tools/dispatcher-allowlist.txt` only shrinks).
- **A8** — no mirror comments ("mirrors Kolibri's …", "port of …"): share the implementation instead (`tools/mirror-allowlist.txt` only shrinks; note the detector matches "port of" anywhere).
- **Rule 13** — comments in English, also in abbreviations (no umlauts); checked on the diff.
- **Rule 11** — a kept broad `catch` carries the four-category note; Exception-vs-Throwable breadth is checked.
- **Rule 9** — `silentError` logs and THROWS in DEBUG (a wrong old value crashes a debug build on purpose); `reportToAcra` only in crash infrastructure.
- **Apostrophe detector** — `res/values*/strings.xml` and `arrays.xml`: write `\'` or quote the value (AAPT2 rejects it, an XML parser and kotlinc do not).
- **Keep list** — a store owning settings keys implements `OwnsSettingsStoreKeys` and is bound with `@IntoSet` in the app's binding file; its source root is in `KEEPLIST_ROOTS`; key properties are UPPERCASE so the gate sees each one.
- **Purge gate** — sees only `*RepositoryImpl.kt`; any other `Purgeable` is covered by the app's `ResetCompletenessContract`.
- **Naming** — data-layer file names follow the app's naming gate (`NAMING_*` in the app's `.conf`).

## 4. DI (Hilt)

- Library modules use Hilt (`alias(libs.plugins.hilt.android)` + `ksp`).
- Qualifiers live in `:core`: `@SettingsStore` (the app's settings DataStore), `@IoDispatcher`, `@MainDispatcher`, `@DefaultDispatcher`, `@ApplicationScope` — **runs on DEFAULT, not Main**; anything touching main-confined state launches with `@MainDispatcher` explicitly.
- `@Singleton` for shared state (one cache, one session per process); `@Provides` for classes without `@Inject`; `@Binds` for interfaces.
- **When an app newly binds a shared object** (3b-6b): the announcement lists every qualified dependency of its graph, transitively, and where each is provided in THAT app — Hilt's graph error appears only in the real build.
- **No app-specific seam where one shared value suffices** (3b-6c: one flatten theme in `:common-ui` instead of a theme per app).

## 5. API design and unification

- An interface goes to `:core` when a pure JVM domain needs it (3a-5).
- Contract triple in testFixtures: the interface, a fake, and contract runs against both the fake and every implementation.
- Before unifying: a difference table "app A ↔ app B ↔ shared" with a column **"changes behaviour?"**.
- Moves are behaviour-neutral; a behaviour change is its own patch, with a line under "user-visible changes" when users can see it.
- Document deliberate seams (what stays per app, and why) — they are the yardstick for "drift or intended".
- **Kolibri is the reference** (the user's decision); still ask **"does Kolibri's pattern fit this host?"** (3b-6d: a composite pays off only for a host that rebuilds its surface).

## 6. Data

- DataStore reads: `readFlowFailOpen` is the standard; the only named exception is "values on Home's start path" (`kolibri/docs/specs/DATASTORE_READ_SPEC.md` §13: typed safe cast, foreign type → default).
- No migration (Rule 5): key names stay as they are on disk; a per-app name goes into a keys object (`WallpaperDisplayKeys`).
- Honest purge (F1): a purge that fails throws; exactly one purge owner per store.
- Backup: no format change; blobs extracted once per hash; "one file per layer" (O2) enforced in ONE place (`assignOwnFiles`).
- User files are deleted only through the store (`WallpaperImageStore`): against what is PERSISTED, fail closed when that cannot be read.

## 7. Concurrency

- State that must stay on the main thread says so in its KDoc; callers launch onto `@MainDispatcher`.
- Document the lock order (e.g. the composite lock BEFORE the persist lock, never the reverse); `Mutex` is not reentrant.
- The deciding check sits INSIDE the lock, right before the action (08b) — a check before waiting for the lock is only a fast path.
- Suspend cleanup in `finally` runs in `withContext(NonCancellable)`.
- Always rethrow `CancellationException`.

## 8. Tests

- `TESTING_CONVENTIONS.kt` (`:core` testFixtures): one dispatcher via `MainDispatcherRule`, no own `TestScope` / `StandardTestDispatcher` (except `StandardTestDispatcher(testScheduler)` where a second distinguishable dispatcher is the point).
- Truth for assertions (A12).
- **Trap:** work in `backgroundScope` never runs under `advanceUntilIdle()` — drive it with `runCurrent()` / `advanceTimeBy()` (14c).
- Run a shared contract FIRST against today's code; known gaps are skipped visibly with `assumeTrue(..., flag)` and switched on by their fix.
- Shared fakes (`:core` testFixtures) instead of private doubles with the same name.
- A stub-based type check proves nothing unless the stubs carry the real signatures.
- A test with gates (a held save, a blocked lock) gets a counter-check: without the guarded code it must turn red.

## 9. Process

- Patches only through the bundle; no hand edits in the repo.
- `LC_ALL=C` only for the apply loop; Gradle runs under UTF-8.
- Every bundle and patch is announced with its SHA-256.
- What a syntax check with kotlinc but without dependencies does **not** find: type errors (a suspend call in a non-suspend lambda), Hilt graph gaps, AAPT2 resource errors — the repo session's gates do.
- Device checks use `run-as` to inspect `files/`; check for FATAL, SILENT_ERROR and ACRA entries.
- Measurements: before and after alternating (A/B, B/A, A/B), cool-down pauses, criteria fixed BEFORE measuring, the same build path for both.

## 10. Checklist

- [ ] Module kind chosen by the Android-types criterion; no edge to another feature module.
- [ ] `settings.gradle.kts` + `SHARED_MODULES`; convention plugin only, nothing build-wide in the module.
- [ ] Shared lint lists checked (cancel, OOM, Rule 11, init order); no new ratchet entry; stale entries removed.
- [ ] No `Dispatchers.*`, no new `CoroutineScope(...)`, no mirror comment; comments in English.
- [ ] Settings keys: `OwnsSettingsStoreKeys` + `@IntoSet`, `KEEPLIST_ROOTS`, UPPERCASE key properties.
- [ ] `Purgeable` with exactly one purge owner; reset contract covers it.
- [ ] DI: qualifiers from `:core`; for a newly bound shared object, the transitive dependency list in the announcement.
- [ ] Difference table with "changes behaviour?"; behaviour changes in their own patch; user-visible lines.
- [ ] Contract triple in testFixtures; contract run against today's code first.
- [ ] Main-thread and lock-order rules in the KDoc; deciding check inside the lock; `NonCancellable` cleanup.
- [ ] Tests: one dispatcher, Truth, no `advanceUntilIdle()` for `backgroundScope` work, counter-checks.
- [ ] Bundle patch with SHA-256; gates named for the repo session; device check if behaviour changed.
- [ ] This guide updated if a gate, ratchet or convention changed.
