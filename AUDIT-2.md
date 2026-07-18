# AUDIT-2 — Whole-Codebase Quality Audit (Runde 1)

> **Erzeugt** 2026-07-18 durch einen Claude-Opus-4.8-Multi-Agent-Audit-Workflow
> (Run `wf_0699fac7-967`, 69 Agenten, 0 Fehler).
> **Methode:** 15 Slices → Finder pro Slice → **adversariale Verify pro Finding**
> gegen `CLAUDE.md`, `app/src/test/CLAUDE.md`, `KNOWN_ISSUES.md`,
> `ACCEPTED_LIMITATIONS.md`, `AUDIT.md`, `REVIEWS.md`, `TODO.md`.
> Gelistet ist **nur, was die Verify überstanden hat** — dokumentiert-bewusste
> Muster (Rule-5/7/9-Ausnahmen, ADR-only-Contracts, akzeptierte Limitationen)
> wurden verworfen.
> **Reine Diagnose — es wurde kein Code geändert.**
>
> *Provenienz:* Der erste Durchlauf lief mitten in der Verify ins Nutzungslimit;
> er wurde resumt, danach liefen alle 69 Agenten durch. Die geplante **Runde 2**
> (loop-until-dry, 3-Stimmen-Verify) wurde bewusst **nicht** gestartet.
> Alle 38 verifizierten Findings sind severity `low` — kein
> High/Medium, kein Crash-Bug.

## Coverage / Statistik

| Slice | Roh-Findings | Verifiziert überlebt |
|---|---:|---:|
| `domain-usecases-A` | 4 | 2 |
| `domain-usecases-B` | 3 | 2 |
| `domain-api-models` | 4 | 4 |
| `domain-core` | 2 | 1 |
| `data-backup` | 4 | 4 |
| `data-repos-datastore` | 4 | 3 |
| `data-system-di` | 4 | 3 |
| `app-home` | 3 | 3 |
| `app-zoomable` | 4 | 4 |
| `app-main-delegates` | 3 | 2 |
| `app-drawer` | 3 | 0 |
| `app-settings-custom` | 4 | 2 |
| `app-crash-pipeline` | 3 | 2 |
| `app-features-rest` | 5 | 4 |
| `test-honesty` | 3 | 2 |
| **Summe** | **53** | **38** |

---

## Executive summary

The codebase is in strong health. Across 15 slices and ~57 raw findings, 41 survived adversarial verification — and **every single one is severity `low`**. There is no high- or medium-severity defect, no crash bug, no user-visible regression. The surviving set is dominated by post-refactor residue (dead code left behind by the §9.2 three-module split and the SpeedDial/toolbar migration) and stale comments/docs. The four "latent-correctness" items are real but effectively unreachable in a single-user, tap-driven launcher today. This is a diagnosis of accumulated tidiness debt, not of fragility — a meticulous maintainer can clear most of it in an afternoon of deletions.

Two systemic patterns worth naming up front:
- **Module-split residue**: unused imports, dead injected `Context` dependencies, and orphaned constants stranded when consumers moved between `:app`/`:data`/`:domain`.
- **CancellationException discipline is not uniform**: three `Get*Setting` use cases plus one retry predicate swallow cancellation, contradicting the file-local pattern the rest of the domain follows and `AUDIT.md:339`.

---

## Quick wins (trivial effort, high confidence — do these first)

Pure deletions / one-line fixes, no behavior risk:

- `domain/.../usecase/GetFavoriteAppsUseCase.kt:161` — delete empty no-op `purgeRepository()`.
- `domain/.../usecase/SaveWallpaperStateUseCase.kt:46,63` — delete unused `updateLayerTransform` / `updateAllLayerTransforms`.
- `domain/.../model/WallpaperState.kt:57,97` — delete dead `toMap()` / `fromMap()` + their SharedPreferences KDoc.
- `domain/.../core/AppConstants.kt:260,264,265,268` — delete four orphaned constants.
- `data/.../BackupSerializer.kt:393` — drop `split_mode_threshold` from `intFields`.
- `data/.../BackupRepositoryImpl.kt:8,19-27` — delete 10 unused imports.
- `data/.../UsageExportRepositoryImpl.kt:60,70,73` — delete `json`, `isoFormatter`, `localFormatter` + dead imports.
- `data/.../InstalledAppsRepositoryEntryPoint.kt:15` — remove unused `getInstalledAppsRepository()`.
- `app/.../ui/home/WallpaperLayer.kt:139,143,165` — delete `scaledWidth`/`scaledHeight`/`toTransformMap`.
- `app/.../ui/home/wallpaperfab/SpeedDialFabCluster.kt:191` — delete `setMiniFabEnabled` + its KDoc bullet.
- `app/.../ui/main/LauncherViewModel.kt:324,389` — delete two dead pass-through methods.
- `app/.../ui/settings/SettingsFragment.kt:91,103` — delete two unused injected repositories.
- `app/.../ui/util/CrashReportLimiter.kt:234` — delete `getStatistics()`.
- `app/.../ui/customnames/CustomNamesActivity.kt:94` (and note the twin at `SettingsActivity.kt:47`) — delete empty `initialize()`.
- `app/.../di/ViewModelModule.kt:13` — delete `OnboardingViewModelInterface` + `@Binds` + module.
- `app/.../ui/home/WallpaperEditController.kt:51` — narrow `applyEditState` to `private`.
- Doc one-liners: `ResetRepositoryContract.kt:28` (11→12), `app/src/test/CLAUDE.md:52` (add FabPositionRepository, 16/12 → 17/13), `CrashReportLimiter.kt:69` (merge stacked KDoc), `WallpaperSurfaceMode.kt:8`, `BuildAppContextMenuUseCase.kt:30`, `Purgeable.kt:3`, `UsageExportRepositoryImpl.kt:180`, `ZoomableImageView.kt:113`.

---

## Findings by theme

### Latent correctness (real, but low-probability trigger)

1. **`domain/.../usecase/GetAutoLaunchSettingUseCase.kt:16`** (+ `GetAutoShowKeyboardSettingUseCase`, `GetTextShadowEnabledUseCase`) — `catch (Exception)` around `flow.first()` swallows `CancellationException`, converting a cancelled scope into a stale fallback. Inconsistent with ~10 sibling use cases and contradicts `AUDIT.md:339`. The upstream `safeData` already guarantees a value, so the catch is dead for real I/O and only intercepts cancellation. **Direction:** drop the try/catch, or add `catch (CancellationException) { throw e }` first. *Effort: trivial. Confidence: high.*

2. **`data/.../FavoritesRepositoryImpl.kt:211`** — `addFavoriteComponent`/`removeFavoriteComponent` (and `HiddenAppsRepositoryImpl.hideComponent`/`showComponent`) do read-modify-write from an outside `flow.first()` snapshot instead of reading `preferences[KEY]` inside the `edit` lambda. Non-atomic lost-update; the package-limit check also runs on a stale snapshot. Sibling batch methods already use the atomic form. Concrete race: broadcast-driven `cleanupFavoriteComponents` concurrent with a user add. **Direction:** compute inside the `edit` lambda (move the limit check in too). *Effort: small. Confidence: medium.*

3. **`app/.../ui/appcontextmenu/AppContextMenuDialogFragment.kt:370`** — context-menu rename inlines the set-vs-remove decision and calls the repository directly, bypassing `RenameDecision` and its 50-char cap (enforced *only* there). Same name is accepted here but rejected on the CustomNames screen. **Direction:** route through `RenameDecision.decide` / a shared use case; surface the `TooLong` branch. *Effort: small. Confidence: medium.*

4. **`domain/.../usecase/ObserveInstalledAppsUseCase.kt:53`** — retry-predicate `catch(Throwable)` swallows the `CancellationException` from `delay()` on collector cancellation; in DEBUG `silentError` rethrows it as a spurious crash, in release it drops cooperative cancellation. Inconsistent with the same file's other two catch sites. **Direction:** rethrow `CancellationException` before the broad catch, or narrow to `is IOException` without the wrapper. *Effort: trivial. Confidence: medium.*

### Dead code (post-refactor residue — all deletions)

All high-confidence, trivial except where noted. See Quick wins for the full one-line list. Grouped highlights:

- **`data/.../FavoritesRepositoryImpl.kt:109`** (+ `FavoritesOrderRepositoryImpl:134`, `HiddenAppsRepositoryImpl:99`, `SettingsRepositoryImpl:30`, `CustomNamesRepositoryImpl:232`) — injected `@ApplicationContext context` stored but never dereferenced across five DataStore repos; Favorites/FavoritesOrder even thread it through `createForTesting`. Misleadingly implies Android-Context coupling the module is designed to avoid. *Effort: small (touches test factories). Confidence: high.*
- **`app/.../ui/home/LayerButtonsState.kt:32`** — `upAlpha`/`downAlpha`/`indicatorVisible` computed and unit-tested but never consumed in production; carries a latent `0.3f` vs live `0.38f` alpha drift. Delete fields + constants + their assertions. *Effort: small. Confidence: high.*
- Remaining dead-code items (`GetFavoriteAppsUseCase.kt:161`, `SaveWallpaperStateUseCase.kt:46`, `WallpaperState.kt:57`, `AppConstants.kt:260`, `BackupSerializer.kt:393`, `BackupRepositoryImpl.kt:19`, `UsageExportRepositoryImpl.kt:60`, `InstalledAppsRepositoryEntryPoint.kt:15`, `SpeedDialFabCluster.kt:191`, `WallpaperLayer.kt:139`, `LauncherViewModel.kt:324`, `SettingsFragment.kt:91`, `CrashReportLimiter.kt:234`, `CustomNamesActivity.kt:94`) — see Quick wins. Note `BackupSerializer.kt:393`'s `split_mode_threshold` is slightly worse than inert: a non-numeric value in a foreign/hand-edited backup fails whole-file type validation over a field the app ignores.

### Over-engineering / altitude

- **`app/.../di/ViewModelModule.kt:13`** — `OnboardingViewModelInterface` + `@Binds` + the whole module have zero consumers (`OnboardingActivity` uses the concrete VM via `by viewModels()`, tests instantiate concrete). Dead indirection implying a non-existent seam. Delete all three. *Effort: trivial. Confidence: high.*
- **`app/.../ui/home/WallpaperEditController.kt:51`** — `applyEditState` public but only called by `applyEditMode` in the same class; over-exposure invites bypassing the listener/logging wiring. Make private. *Effort: trivial. Confidence: medium.*

### Duplication

- **`data/.../BackupSerializer.kt:205`** — `parseStrictly` and `mergeWithStrictValues` restate the same ~21-field JSON→`LauncherSettings` mapping; `TODO.md:1902` already records `favoritesAlignment` having to be edited in lockstep across sites, and a missed site is a silent compat drop (not a compile error). Extract one `extractStrictSettings(json, base)` helper. Real consolidation is 2 sites, not the 3–4 the raw finding implied. *Effort: medium. Confidence: medium.*
- **`app/.../ui/main/delegate/WallpaperDelegate.kt:589`** — the `withUpdatedLayer{copy}` + assign + `saveWallpaperStateUseCase` body repeats verbatim across `onSetLayerAlpha`/`BlendMode`/`Visibility` (and `onSaveLayerTransform`). Extract `mutateLayerAndPersist(layerIndex, transform)` (keep `launchSafe`). Note: this is where `SaveWallpaperStateUseCase`'s dead convenience methods "should" live but can't (delegate needs `newState` for its local mirror) — deleting those and adding this helper resolves both. *Effort: small. Confidence: medium.*
- **`data/.../CustomNamesRepositoryImpl.kt:19`** — the event-vs-Flow rationale is written three times over ~200 lines (two free-standing NOTE blocks + class KDoc), longer than the code it documents. Collapse to one canonical block. *Effort: small. Confidence: medium.*
- **`app/.../ui/appcontextmenu/AppContextMenuDialogFragment.kt:204`** — the `LuminanceClassification → SolidColor + foreground` mapping is duplicated verbatim at lines 204-213 and 424-433. Extract a private `resolveSurface(classification)`. *Effort: trivial. Confidence: medium.*
- **`app/.../ui/colorcustomization/ColorCustomizationDialogFragment.kt:302`** — `getThemeColor` duplicated with `SettingsFragment:615`, and the two copies have *already* drifted (black vs magenta fallback, wrapped vs unwrapped catch). Extract `Context.resolveThemeColor(attr, fallback)`. *Effort: small. Confidence: low.*

### Efficiency

- **`app/.../ui/home/ZoomableImageView.kt:767`** — `onDraw` allocates a fresh `Matrix` per layer per frame via `buildMatrix()`, plus a `Matrix`+`RectF` for the selection highlight, on a path invalidated every `ACTION_MOVE` and snap-back frame. Add `buildMatrixInto(Matrix)` / `getTransformedBoundsInto(...)` writing into the existing reusable `drawMatrix`; drop the redundant `reset()` before `set()` (also at 688-689). Edit-mode-only, so impact is modest. *Effort: small. Confidence: high.*
- **`data/.../BackupRepositoryImpl.kt:635`** — `previewBackup` calls `isZipFile(uri)` twice (lines 635, 646), each opening a fresh content-provider stream to read 2 magic bytes for one unchanging boolean. Hoist to `val isZip = isZipFile(uri)`. *Effort: trivial. Confidence: high.*

### Doc / comment drift (all trivial, deletions/edits — no runtime effect)

- **`app/src/test/CLAUDE.md:52`** — status table omits `FabPositionRepository` (has a full honest contract triple) and falsely claims completeness at 16/12; bump to 17/13. *Confidence: high.*
- **`domain/.../repository/ResetRepositoryContract.kt:28`** — ADR says "11 child repositories"; impl now orchestrates 12 (its own comment already says "a twelfth Purgeable"). *Confidence: high.*
- **`domain/.../model/WallpaperSurfaceMode.kt:8`** — AUTO KDoc claims the classifier is unshipped and AUTO resolves to DARK; the classifier shipped, AUTO now delegates to `ResolveWallpaperSurfaceUseCase`. *Confidence: high.*
- **`data/.../UsageExportRepositoryImpl.kt:180`** — `version != CONST && version != "1.0.0"` is a duplicated literal (CONST *is* "1.0.0"); adjacent comment claims "akzeptiere 1.0.0 und 1.1.0" but 1.1.0 is rejected. *Confidence: high.*
- **`app/.../ui/home/ZoomableImageView.kt:113`** — `ZOOM_OUT_MULTIPLIER = 0.05f` but comment says "Min 25%" and worked examples at 155-156 assume 0.25 (5× discrepancy). Decide the intended floor and align all three. *Confidence: medium.*
- **`app/.../ui/home/ZoomableImageView.kt:490`** — `addLayer`'s `centerCrop` param + KDoc promise cover/center-crop but the body calls `applyFitWidth`; real `applyCenterCrop` sits commented-out above. Rename param + delete dead block. *Confidence: high.*
- **`domain/.../usecase/BuildAppContextMenuUseCase.kt:30`** — KDoc says labels are "@StringRes ids"; they are `LauncherActionLabel` sealed identifiers (the domain's own documented discipline). *Confidence: medium.*
- **`domain/.../repository/Purgeable.kt:3`** — KDoc frames it as an androidTest-only reset hook; it now drives production factory reset. *Confidence: medium.*
- **`app/.../ui/util/CrashReportLimiter.kt:69`** — two stacked KDoc blocks on `init()`; only the second binds, the startup-contract sentence is orphaned. Merge. *Confidence: high.*

---

## Genuinely clean

- **`app-drawer`** — came back with zero surviving findings (3 raw, all rejected on verification). The auto-launch/search-gate logic and drawer paths are solid.
- **`domain-api-models`** and **`data-backup`** had *no findings rejected* (all raw survived), but note: every survivor there is low-severity dead-code or doc drift — the *logic* in those slices is sound; only naming/comments/unused surface needs pruning.
- No slice produced a correctness bug that fires in normal single-user operation. The crash-safety pipeline (Rule 7/9 infrastructure), ACRA opt-in ordering, contract-test triples, and dispatcher discipline were probed and held — the only crash-pipeline survivors are a dead debug helper and a stacked KDoc, both cosmetic.

Overall: the launcher's architecture and behavior are in good shape. This report is a cleanup backlog, not a defect list.

---

## Anhang — alle verifizierten Findings im Detail

### A1. CrashReportLimiter.getStatistics() has zero callers

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/util/CrashReportLimiter.kt:234`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `app-crash-pipeline`

**Ist-Zustand.** getStatistics() builds a 'Total tracked / Active blocks' summary string and is documented '(for debugging)'. A repo-wide grep (app + data + domain + tests) finds no invocation anywhere; the sibling resetAllLimits() is genuinely wired into SettingsFragment:575, but getStatistics() is not referenced by Settings, any dev-command, or any test.

**Warum suboptimal.** It is unreachable production code carrying its own try/catch and synchronized block. Unlike the SharedPreferences exception itself (Rule 5, intentional) or the crash-safety catches (Rule 7/9), nothing documents this method as an intentional keep — it is a leftover debugging helper. Rule 9 is about the error pipeline, not about preserving unused reporting surface.

**Richtung.** Delete getStatistics(), or if it is meant to back a dev-command, wire it into the Settings developer-commands section next to resetAllLimits(). Do not leave it half-connected.

> **Verify-Evidenz.** Verified against current code: CrashReportLimiter.getStatistics() (line 234) is a public function on the CrashReportLimiter object with zero callers — a repo-wide grep across .kt, .xml and .md files returns only its own definition. By contrast the sibling resetAllLimits() is legitimately wired into SettingsFragment.kt:575, proving getStatistics() is not part of any dev-command path. No documentation (CLAUDE.md rules 5/7/9, AUDIT.md, REVIEWS.md, TODO.md, KNOWN_ISSUES.md, ACCEPTED_LIMITATIONS.md) preserves it as intentional: Rule 5 covers only the SharedPreferences backing, Rule 9 covers only the bare Timber.e usage, neither the method's existence. It is a leftover '(for debugging)' helper — genuinely dead code whose removal is a small but defensible improvement. Could not refute the finding.

---

### A2. init() carries two stacked KDoc blocks

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/util/CrashReportLimiter.kt:69`  ·  **drift**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `app-crash-pipeline`

**Ist-Zustand.** The init(context) function is preceded by two separate consecutive /** */ KDoc blocks: lines 69-72 ('Initialize the limiter with application context. Should be called once during app startup.') immediately followed by lines 73-75 ('Async initialization to avoid StrictMode violations on startup.'). Only the second is the effective KDoc; the first is orphaned documentation.

**Warum suboptimal.** Two doc comments on one declaration is a leftover from an edit that added the async note without removing the old block. It is cosmetic but it is the kind of drift the project otherwise keeps tight.

**Richtung.** Merge into a single KDoc block covering both the 'call once on startup' contract and the async/StrictMode rationale.

> **Verify-Evidenz.** Confirmed at CrashReportLimiter.kt:69-75: two stacked /** */ KDoc blocks precede the single init(context) declaration. The first block (69-72, 'Initialize the limiter... call once during app startup') is orphaned — Kotlin/Dokka only binds the immediately-preceding block (73-75, 'Async initialization to avoid StrictMode violations') as the effective KDoc, so the startup-contract sentence is lost from generated docs. This is a genuine editing artifact / drift, not subjective taste: the two contracts should be one block. No project doc covers it (not a Rule 5/7/9 crash-pipeline exception, not in AUDIT/REVIEWS/TODO/KNOWN_ISSUES). Merge into a single KDoc covering both the call-once-on-startup contract and the async/StrictMode rationale. Severity is low (cosmetic doc drift, no runtime effect), but it is verifiable, actionable, and defensible.

---

### A3. OnboardingViewModelInterface + ViewModelModule are dead indirection with zero consumers

`app/src/main/java/com/github/reygnn/kolibri_launcher/di/ViewModelModule.kt:13`  ·  **over-engineering**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `app-features-rest`

**Ist-Zustand.** OnboardingViewModelInterface is declared, implemented by OnboardingViewModel, and bound via @Binds into ViewModelComponent in ViewModelModule. But nothing ever requests the interface: no @Inject parameter of that type, no constructor dependency, no test uses it. OnboardingActivity obtains the concrete OnboardingViewModel via `by viewModels()`. The @Binds therefore produces a Hilt binding that is never resolved (and binding a ViewModel into ViewModelComponent isn't a normal injection path anyway, since ViewModels come from the SavedState factory). ViewModelModule exists solely for this one unused binding.

**Warum suboptimal.** An interface, its implementation clause, and an entire Hilt module add three maintenance surfaces and imply a seam (multiple impls / test double) that does not exist. It is pure ceremony that a reader has to chase to discover it does nothing.

**Richtung.** Delete OnboardingViewModelInterface, its `: OnboardingViewModelInterface` implementation clause on OnboardingViewModel, and the ViewModelModule @Binds (drop the whole module if this is its only binding). If an abstraction seam is ever wanted for tests, add it when a consumer actually needs it.

> **Verify-Evidenz.** Confirmed factually: OnboardingViewModelInterface is implemented only by OnboardingViewModel and bound only via ViewModelModule's @Binds into ViewModelComponent. grep across the whole codebase shows zero consumers of the interface type — OnboardingActivity uses the concrete OnboardingViewModel via `by viewModels()`, and both OnboardingViewModelTest and OnboardingActivityRobolectricTest instantiate the concrete class directly (`OnboardingViewModel(...)`), never the interface. No @Inject parameter of the interface type exists. The @Binds thus produces a Hilt binding that is never resolved, and ViewModelModule exists solely for it. Removing the interface, its implementation clause, and the module is a genuine cleanup of dead indirection, not taste/style churn. Only doc reference (CLAUDE.md:91) is an inventory listing of ViewModelModule, not a justification — not documented-intentional. survives=true.

---

### A4. Empty internal initialize() with no callers

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/customnames/CustomNamesActivity.kt:94`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `app-features-rest`

**Ist-Zustand.** `internal fun initialize()` has an empty body and a comment stating it is currently empty but kept for consistency and future loading logic. A repo-wide search finds no caller in main or test sources (sibling activities like SwipeActions/HiddenApps call `viewModel.initialize()`, not the activity's own). It is unreferenced scaffolding.

**Warum suboptimal.** Dead method with a speculative 'for the future' rationale; it invites a reader to wonder where it is wired and adds noise. Speculative extensibility with no current use is exactly the kind of scaffolding the codebase otherwise prunes.

**Richtung.** Delete the method; reintroduce it if and when actual load logic is needed.

> **Verify-Evidenz.** Confirmed at CustomNamesActivity.kt:94: internal fun initialize() has an empty body with a German comment saying it is kept for future load logic. A repo-wide search (main, test, androidTest) finds zero callers — the CustomNamesActivity tests launch the activity but never call initialize(), and the sibling initialize() calls the finder mentions are on ViewModels doing real work. The TODO.md initialize references (lines 124-125) concern HiddenAppsViewModel/SwipeActionsViewModel cold-path fixes, not this empty Activity method, so it is not tracked. No doc (CLAUDE.md, AUDIT.md, REVIEWS.md, KNOWN_ISSUES.md, ACCEPTED_LIMITATIONS.md) justifies keeping it. It is genuinely unreachable speculative scaffolding, and the codebase demonstrably prunes such dead code. Deleting it is a real, defensible (if low-severity) cleanup. Survives. (Aside: an identical empty initialize() also exists in SettingsActivity.kt:47, reinforcing that this is an untracked duplicated dead pattern.)

---

### A5. Surface-color derivation duplicated between onViewCreated and tintRenameDialog

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/appcontextmenu/AppContextMenuDialogFragment.kt:204`  ·  **duplication**  ·  severity: low  ·  confidence: medium  ·  effort: trivial  ·  slice: `app-features-rest`

**Ist-Zustand.** The block that maps a LuminanceClassification to a ResolvedBackground.SolidColor via ContextCompat.getColor(when { LIGHT -> R.color.app_drawer_surface_light; DARK -> R.color.app_drawer_surface_dark }) and then derives `fg = surface.foregroundColor()` appears twice verbatim: in the collectOnStarted surface observer (lines 204-213) and in tintRenameDialog (lines 424-433).

**Warum suboptimal.** The classification->surface+foreground mapping is a small piece of shared logic copied in two spots; if the color-resource mapping changes, both must be updated in lockstep.

**Richtung.** Extract a private helper (e.g. `fun resolveSurface(classification): Pair<ResolvedBackground.SolidColor, Int>` or return the surface and let callers derive fg) and call it from both sites.

> **Verify-Evidenz.** Confirmed in current code: the surface-color derivation block is duplicated verbatim in the collectOnStarted observer (lines 204-213) and in tintRenameDialog (lines 424-433). Both build a ResolvedBackground.SolidColor via ContextCompat.getColor(when { LIGHT -> R.color.app_drawer_surface_light; DARK -> R.color.app_drawer_surface_dark }) and then compute fg = surface.foregroundColor(). Both call sites operate on a non-null LuminanceClassification, so a private helper returning (SolidColor, fg) cleanly serves both and removes a two-point maintenance hazard on the color-resource mapping. This is genuine, defensible DRY duplication, not style churn, and no CLAUDE.md rule, AUDIT.md, REVIEWS.md, or TODO.md entry covers or intentionally sanctions it. Effort is trivial and the extraction does not conflict with the codebase's crash-safety conventions.

---

### A6. Context-menu rename bypasses RenameDecision and its 50-char length cap

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/appcontextmenu/AppContextMenuDialogFragment.kt:370`  ·  **latent-correctness**  ·  severity: low  ·  confidence: medium  ·  effort: small  ·  slice: `app-features-rest`

**Ist-Zustand.** The long-press context-menu rename (showRenameDialog positive button, lines 370-377) inlines the set-vs-remove decision directly in the Fragment: `if (newName.isNotBlank() && newName != appInfo.originalName) setCustomNameForPackage(...) else removeCustomNameForPackage(...)`, calling customNamesRepository directly. It never consults RenameDecision.decide, which is the tested pure helper the CustomNames screen routes through. MAX_APP_NAME_LENGTH (50) is defined and enforced ONLY in RenameDecision (verified: no length check exists in SetCustomNameUseCase, CustomNamesViewModel, or CustomNamesRepositoryImpl, which merely `.trim()`s and stores). So a name entered via the context menu is persisted at arbitrary length, while the same name entered on the CustomNames screen is rejected with error_name_too_long.

**Warum suboptimal.** Two entry points to the identical feature diverge in validation: one caps length, the other does not. The decision logic is also duplicated in Fragment code (Rule 10 altitude) instead of reusing the existing pure, tested RenameDecision.

**Richtung.** Route the context-menu rename through RenameDecision.decide (or a shared use case that owns the length/blank/equals-original rules) so both entry points enforce the same 50-char cap and share one tested decision path; surface the TooLong branch to the user as the CustomNames screen does.

> **Verify-Evidenz.** Confirmed in current code: AppContextMenuDialogFragment.kt:370-377 inlines the set-vs-remove decision and calls customNamesRepository directly, bypassing RenameDecision.decide. MAX_APP_NAME_LENGTH (50) is enforced ONLY in RenameDecision.kt (line 59); SetCustomNameUseCase merely delegates, and CustomNamesRepositoryImpl.setCustomNameForPackage (line 239) only checks isBlank and trims — no length cap. The context-menu EditText sets no maxLength filter. Result: a name entered via the context menu persists at arbitrary length while the CustomNames screen (CustomNamesActivity.kt:271-272) rejects it as TooLong. Genuine validation divergence between two entry points to the same feature, plus decision logic duplicated in Fragment code where the tested pure RenameDecision helper already exists (Rule 10 altitude). Not covered by CLAUDE.md rules/exceptions, KNOWN_ISSUES, ACCEPTED_LIMITATIONS, AUDIT.md, REVIEWS.md, or TODO.md. Low severity (edge case, unlikely to fire), but real and actionable.

---

### A7. LayerButtonsState.upAlpha / downAlpha / indicatorVisible are computed and tested but never consumed in production

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/LayerButtonsState.kt:32`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: small  ·  slice: `app-home`

**Ist-Zustand.** LayerButtonsState.from(...) computes upAlpha, downAlpha and indicatorVisible. Grep shows the only readers are LayerButtonsStateTest. The sole production consumer, WallpaperEditController.applyLayerButtonsState (lines 347-355), reads only addVisible/deleteVisible/deleteEnabled/upVisible/upEnabled/downVisible/downEnabled. The disabled-alpha for the reorder buttons is instead computed inside CommandsPanel.setLayerButtonsState using WallpaperFabConstants.DISABLED_ALPHA (0.38f), and the indicator's visibility is driven independently by WallpaperEditController.updateLayerIndicator via layerCount>0. So these three fields are vestigial from the pre-SpeedDial legacy toolbar. There is also a latent value drift: LayerButtonsState.DISABLED_ALPHA is 0.3f while the value actually applied in production is 0.38f.

**Warum suboptimal.** Three data-class fields plus the ENABLED_ALPHA/DISABLED_ALPHA constants are pure dead weight whose unit tests pin computation that no production path exercises; the 0.3f vs 0.38f mismatch is a trap for anyone who later wires them up assuming they are the source of truth.

**Richtung.** Drop upAlpha, downAlpha, indicatorVisible (and the now-unused alpha constants) from LayerButtonsState and their assertions in LayerButtonsStateTest, since the panel owns the alpha and the indicator visibility is computed elsewhere. If indicatorVisible is wanted as the single source, have updateLayerIndicator read it instead of recomputing count>0.

> **Verify-Evidenz.** Verified in current code: LayerButtonsState.from (LayerButtonsState.kt:49-60) computes upAlpha, downAlpha, and indicatorVisible, but the only production consumer applyLayerButtonsState (WallpaperEditController.kt:348-355) reads none of them — it forwards only the visible/enabled booleans. Disabled alpha for the reorder buttons is applied inside CommandsPanel.setLayerButtonsState using WallpaperFabConstants.DISABLED_ALPHA (0.38f), and indicator visibility is driven independently by updateLayerIndicator via count>0 (WallpaperEditController.kt:327-337). The three fields plus the ENABLED_ALPHA/DISABLED_ALPHA constants are read only by LayerButtonsStateTest. The value drift is real: LayerButtonsState.DISABLED_ALPHA=0.3f vs the actually-applied WallpaperFabConstants.DISABLED_ALPHA=0.38f. Not covered by CLAUDE.md, test CLAUDE.md, KNOWN_ISSUES, ACCEPTED_LIMITATIONS, AUDIT, REVIEWS, or TODO. Genuine dead-code cleanup with a latent-trap constant, not mere style; survives. Severity low — no runtime impact, purely a maintainability/dead-weight concern.

---

### A8. SpeedDialFabCluster.setMiniFabEnabled is never called; the per-mini-FAB enum/fabFor breadth is over-built for one visibility toggle

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/wallpaperfab/SpeedDialFabCluster.kt:191`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: small  ·  slice: `app-home`

**Ist-Zustand.** setMiniFabEnabled(id, enabled) is referenced only in the class KDoc (line 32), never from any caller. The one live call into the per-mini-FAB machinery is setMiniFabVisible(MiniFab.AddLayer, ...) from WallpaperEditController.applyLayerButtonsState. Consequently fabFor() is only ever reached with MiniFab.AddLayer, so the Cancel/OneToOne/FitWidth/OpenCommands branches of fabFor and those enum members exist purely to satisfy the dead setMiniFabEnabled path.

**Warum suboptimal.** A whole enable/greyed-out API surface (setMiniFabEnabled + DISABLED_ALPHA-style handling) plus four unreachable fabFor branches are maintained for a caller that does not exist, inflating review surface and inviting the impression the FABs support a disabled state they never use.

**Richtung.** Remove setMiniFabEnabled and its KDoc bullet. Then either collapse setMiniFabVisible to operate directly on fabAddLayer (the only FAB toggled), or trim the MiniFab enum + fabFor to the members actually reachable.

> **Verify-Evidenz.** Verified in current code: setMiniFabEnabled (SpeedDialFabCluster.kt:191) is referenced only by its own KDoc bullet (line 32); grep across app/src/main, app/src/test, data, and domain finds zero callers. The only live use of the per-mini-FAB machinery is setMiniFabVisible(MiniFab.AddLayer, ...) at WallpaperEditController.kt:347, always AddLayer — so the enable/greyed-out API surface (isEnabled + DISABLED_ALPHA) is genuinely dead public API. No doc (CLAUDE.md, AUDIT.md, REVIEWS.md, TODO.md, KNOWN_ISSUES.md, ACCEPTED_LIMITATIONS.md) covers it. Removing the dead method + KDoc line is a small, low-risk, defensible cleanup. The secondary suggestion (trim the MiniFab enum / fabFor branches) is weaker — the enum is a reasonable stable identifier and setMiniFabVisible could plausibly toggle other FABs — but the primary dead-code claim is accurate and actionable, so the finding survives.

---

### A9. WallpaperEditController.applyEditState is public but only called internally

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/WallpaperEditController.kt:51`  ·  **altitude**  ·  severity: low  ·  confidence: medium  ·  effort: trivial  ·  slice: `app-home`

**Ist-Zustand.** applyEditState(state) is declared public on the internal WallpaperEditController, but grep shows its only caller is applyEditMode within the same class (line 78); no Fragment or test references it. applyFabPosition/applyLayerButtonsState/updateLayerIndicator are genuinely called from HomeFragment, but applyEditState is not.

**Warum suboptimal.** Over-exposed visibility widens the class's apparent contract and suggests external callers set the raw edit state directly, bypassing the applyEditMode listener wiring/logging that must accompany a real transition.

**Richtung.** Make applyEditState private (it is a pure sub-step of applyEditMode).

> **Verify-Evidenz.** Verified: grep confirms applyEditState (line 51) is public but has exactly one caller — applyEditMode at line 78 in the same class; no Fragment or test references it. The other public apply*/update* methods (applyFabPosition, applyLayerButtonsState, updateLayerIndicator, applyEditMode) all have real HomeFragment call sites (HomeFragment.kt:550,564,1439-1440), making applyEditState the lone over-exposed member. Narrowing it to private is a genuine (if trivial, low-severity) encapsulation improvement: it matches actual usage, removes drift versus the sibling methods, and prevents future callers from setting raw edit state and bypassing the applyEditMode listener-wiring / logging / catch(Throwable)+silentError orchestration. Not covered by any rule doc, AUDIT, REVIEWS, or TODO (TODO §9.3 only mentions the controller's extraction, not this method's visibility).

---

### A10. Two dead ViewModel pass-through methods with no callers anywhere

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/LauncherViewModel.kt:324`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `app-main-delegates`

**Ist-Zustand.** `getInitialBatteryState()` (line 324, delegating to `clockDelegate.refreshAll()`) and `onEnterWallpaperEditMode()` (line 389, delegating to `wallpaperDelegate.onEnterWallpaperEditMode()`) have zero callers across production and tests. Verified by grep over app/src: the ClockDelegate/WallpaperDelegate internals are exercised directly (e.g. WallpaperDelegateTest calls `delegate.onEnterWallpaperEditMode()`, not the VM wrapper) and no production site calls either VM method. `getInitialBatteryState()` is additionally misnamed — despite the battery-only name it triggers a full `refreshAll()` (time + battery + time-based-events refresh).

**Warum suboptimal.** Dead public API on the facade ViewModel adds surface that readers must reason about, and the misleading name invites a future caller to trigger a full refresh thinking it only re-reads the battery. The §9.7 delegate audit noted the delegate-level double-enter snapshot edge case but did not flag these VM wrappers as unreachable.

**Richtung.** Delete both VM wrappers. If the facade-contract-test convention wants them retained for API stability, at minimum rename `getInitialBatteryState` to reflect that it does a full refresh, or drop it in favour of the existing `refreshDynamicUiData`/`refreshAllData` which already alias `refreshAll()`.

> **Verify-Evidenz.** Grep across app/src confirms both VM wrappers are unreachable. LauncherViewModel.kt:324 getInitialBatteryState()=clockDelegate.refreshAll() has no callers (ClockDelegate's own getInitialBatteryState is a separate private method; MainActivity:536 is a comment). LauncherViewModel.kt:389 onEnterWallpaperEditMode() has no callers either — all delegate tests and internal routing call the delegate directly, never the VM wrapper. The LauncherViewModelContractTest, which explicitly pins the Fragment-facing public API, references neither method. Misnaming is real: refreshAll() (ClockDelegate:90-94) does time+date+battery+time-based-events, and getInitialBatteryState is an exact duplicate of the VM's refreshDynamicUiData() (line 409). AUDIT.md mentions these symbols only in crash-safety/catch context, not as intentionally-kept dead API; no facade-completeness rule exists. Deleting two dead, one-line pass-throughs (one a misleadingly-named duplicate) is a genuine low-risk surface reduction, not style churn.

---

### A11. Layer mutate-then-persist boilerplate repeated across ~8 delegate methods

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/delegate/WallpaperDelegate.kt:589`  ·  **duplication**  ·  severity: low  ·  confidence: medium  ·  effort: small  ·  slice: `app-main-delegates`

**Ist-Zustand.** The pattern `val newState = _wallpaperState.value.withUpdatedLayer(layerIndex){ it.copy(...) }; _wallpaperState.value = newState; saveWallpaperStateUseCase(newState)` is repeated verbatim in `onSaveLayerTransform`, `onSetLayerAlpha`, `onSetLayerBlendMode`, `onSetLayerVisibility` (and the set-then-persist tail also appears in `onSwapWallpaperLayers`, `onAddWallpaperLayer`, `onRemoveWallpaperLayer`, `onSaveAllLayerTransforms`). The three property setters (alpha/blend/visibility, lines 589-614) are structurally identical apart from the single `copy(...)` field.

**Warum suboptimal.** Eight near-identical set-in-memory-plus-persist bodies mean any change to the persist contract (e.g. adding an in-flight guard, a diff check, or a coalescing write) must be edited in eight places, a drift risk. The three property setters in particular collapse to one call each.

**Richtung.** Introduce a private helper e.g. `private fun mutateLayerAndPersist(layerIndex: Int, transform: (WallpaperLayerState) -> WallpaperLayerState)` (wrapped in `scope.launchSafe`) that does withUpdatedLayer + assign + save, and have the property setters call it with just the `copy` lambda. Keep the add/remove/swap methods as-is where they carry extra bookkeeping.

> **Verify-Evidenz.** Verified in the current file: onSaveLayerTransform (565-570), onSetLayerAlpha (589-596), onSetLayerBlendMode (598-605), and onSetLayerVisibility (607-614) share a verbatim body — `val newState = _wallpaperState.value.withUpdatedLayer(layerIndex) { it.copy(...) }; _wallpaperState.value = newState; saveWallpaperStateUseCase(newState)` — differing only in the single copy field and the launchSafe error string; the set-then-persist tail also recurs in add/remove/swap/saveAll. The claim is factually accurate. These four are semantically identical single-layer mutate-and-persist operations (not superficial look-alikes), so a private `mutateLayerAndPersist(errorMessage, layerIndex, transform)` helper is a legitimate DRY reduction, not mere taste, and it leaves crash safety intact (launchSafe stays), so the conservative/crash-safe guardrail does not shield it. Checked CLAUDE.md, test CLAUDE.md, KNOWN_ISSUES.md, ACCEPTED_LIMITATIONS.md, AUDIT.md, REVIEWS.md, TODO.md: AUDIT §8.1 consolidated a DIFFERENT delegate duplication (the try/catch→launchSafe wrapper) and did not address or reject this body pattern, so it is not documented-intentional. Real but low severity: effort small, drift risk concrete (a future in-flight guard/diff-check would otherwise need editing in multiple places).

---

### A12. Two injected repositories are never used

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/settings/SettingsFragment.kt:91`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `app-settings-custom`

**Ist-Zustand.** SettingsFragment @Inject-injects `hiddenAppsRepository` (line 91) and `screenLockRepository` (line 103). A full-file grep shows neither field is referenced anywhere except its own `lateinit` declaration. The class actually consumes only `favoritesRepository`, `favoritesOrderRepository`, and `settingsRepository`.

**Warum suboptimal.** Dead field injection: Hilt still constructs/binds these dependencies for every SettingsFragment instance, and readers assume the fragment touches hidden-apps and screen-lock state when it does not. Pure noise that misleads about the fragment's real data surface.

**Richtung.** Delete both `@Inject lateinit var hiddenAppsRepository` and `screenLockRepository` declarations. Verify no reflection/test relies on them first.

> **Verify-Evidenz.** Confirmed in current code: SettingsFragment.kt lines 91 and 103 declare `@Inject lateinit var hiddenAppsRepository: HiddenAppsRepository` and `screenLockRepository: ScreenLockRepository`, and a full-file grep shows each appears ONLY at its own declaration line — never used in any method. The fragment only consumes favoritesRepository, favoritesOrderRepository, and settingsRepository. No external/reflective/test reference to these fragment fields exists (the two test hits for `screenLockRepository` are unrelated use-case constructor args in RequestLockUseCaseTest/RequestNotificationsUseCaseTest). Hilt still binds/injects both for every SettingsFragment instance and they falsely imply the fragment touches hidden-apps and screen-lock state. No doc (CLAUDE.md rules, AUDIT.md — which discusses SettingsFragment size but not these injections — REVIEWS.md's 'false dead-code claim' is about BaseViewModelTest, TODO.md, KNOWN_ISSUES.md, ACCEPTED_LIMITATIONS.md) justifies or tracks this. Deleting both declarations is a genuine, low-risk dead-code cleanup, not style churn. Survives.

---

### A13. getThemeColor duplicated between SettingsFragment and ColorCustomizationDialogFragment

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/colorcustomization/ColorCustomizationDialogFragment.kt:302`  ·  **duplication**  ·  severity: low  ·  confidence: low  ·  effort: small  ·  slice: `app-settings-custom`

**Ist-Zustand.** `getThemeColor(context, @AttrRes attrRes)` resolving a theme attribute to a color via `TypedValue` + `resolveAttribute` is implemented twice: SettingsFragment:615 (public, try/catch, fallback `android.R.color.black`) and ColorCustomizationDialogFragment:302 (private, no try/catch, fallback `Color.MAGENTA`).

**Warum suboptimal.** Same helper, two copies with divergent fallbacks and divergent error handling — a drift already exists (black vs magenta, wrapped vs unwrapped). A shared extension would give one consistent behavior.

**Richtung.** Move to a single `Context.resolveThemeColor(@AttrRes attr, fallback)` extension in a ui/util file and have both fragments call it, picking one fallback convention.

> **Verify-Evidenz.** Confirmed at both sites: SettingsFragment.kt:615 (public, try/catch(Throwable)+silentError, black fallback) and ColorCustomizationDialogFragment.kt:302 (private, no catch, magenta fallback) implement the identical getThemeColor(context, @AttrRes) TypedValue/resolveAttribute logic. The claim is factually accurate, the fallback/error-handling drift is real and undocumented (no mention in CLAUDE.md, AUDIT.md, REVIEWS.md, TODO.md, or test CLAUDE.md), and no shared util exists. The finder's proposed parameterized Context.resolveThemeColor(attr, fallback) extension preserves both call sites' distinct fallbacks, so consolidation is behavior-preserving rather than a forced merge — a genuine DRY cleanup, not mere style churn. It is a minor/low-severity finding (a short body duplicated twice) but survives all three gates.

---

### A14. WallpaperLayer.scaledWidth / scaledHeight / toTransformMap are unused across the whole repo

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/WallpaperLayer.kt:139`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `app-zoomable`

**Ist-Zustand.** `scaledWidth` (139), `scaledHeight` (143) and `toTransformMap()` (165) have zero references anywhere in main or test sources (grep-confirmed). Notably the edge-resistance and snap-back code in ZoomableImageView recomputes `bmp.width * layer.scale` / `bmp.height * layer.scale` inline (e.g. lines 1197-1198, 1259-1260, 1274-1291) rather than using scaledWidth/scaledHeight. toTransformMap looks like a leftover persistence path superseded by the domain WallpaperLayerState.

**Warum suboptimal.** Dead members on a data class carry maintenance weight and imply a persistence/geometry API that nothing uses; unlike the ZoomableImageView public-API stubs these are not @Suppress("unused")-annotated or documented as an intentional external surface.

**Richtung.** Remove the three unused members (or, if kept as a geometry helper, route the inline scaledW/scaledH recomputations through scaledWidth/scaledHeight to justify their existence).

> **Verify-Evidenz.** Grep confirms scaledWidth (139), scaledHeight (143) and toTransformMap() (165) have zero references anywhere in main or test, and are not used inside WallpaperLayer.kt itself. Unlike ZoomableImageView's intentionally-kept public-API stubs (each marked @Suppress(\"unused\") with a KDoc explaining it is part of the View interface), these three members carry no such annotation or documentation — genuine unannotated dead code. toTransformMap's own KDoc claims a SharedPreferences/JSON persistence role, but the project persists wallpaper state via DataStore + domain WallpaperLayerState, so it is a superseded path. No audit/known-issues/limitations doc covers these members. The finding is slightly incomplete (applyCover() is also unused but not flagged), but the named claim is factually correct and removing dead, unannotated members is a defensible, low-severity consistency improvement.

---

### A15. addLayer's `centerCrop` parameter is misnamed and sits above a commented-out centerCrop block

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/ZoomableImageView.kt:490`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `app-zoomable`

**Ist-Zustand.** addLayer documents `@param centerCrop Wenn true, wird das Bild automatisch auf Cover skaliert` (line 459), but when true it calls `layer.applyFitWidth(width, height)` (lines 494-496), not applyCenterCrop. The real applyCenterCrop call is left as a commented-out block directly above (lines 490-492). So the parameter name and KDoc promise center-crop/cover while the behaviour is fit-to-width.

**Warum suboptimal.** A public View API parameter whose name lies about its effect is a trap for callers, and the commented-out alternative is dead code documenting an abandoned decision that belongs in git history, not the source.

**Richtung.** Rename the parameter to reflect the actual behaviour (e.g. autoFit / fitOnAdd) and update the KDoc, or restore genuine center-crop; delete the commented-out block either way.

> **Verify-Evidenz.** Verified in current code: addLayer's KDoc (line 459) promises "auf Cover skaliert" (center-crop/fill) and the parameter is named centerCrop (line 468), but when true it calls layer.applyFitWidth(width, height) (lines 494-496), not applyCenterCrop. applyFitWidth and applyCenterCrop are genuinely distinct operations (the real center-crop path exists at WallpaperLayer.applyCenterCrop and is invoked by centerCropLayer/centerCropAll and the single-layer centerCrop() at line 363 via max()-based fill), so the name/KDoc factually contradict the behaviour. Directly above sits a commented-out applyCenterCrop block (lines 490-492) = abandoned-decision dead code that belongs in git history. The whole chain (RebuildPlan.centerCrop -> addLayer centerCrop -> applyFitWidth) propagates the misleading name. Not mere style: a public View-API param whose name and doc lie about its effect is a maintainer trap. The §9.8 ZoomableImageView audit (AUDIT.md:1106-1191) inspected addLayer only for crash-safety and never mentioned this; no other doc (CLAUDE.md, KNOWN_ISSUES, ACCEPTED_LIMITATIONS, TODO, REVIEWS) covers it. Rule 13 only grandfathers German comments against translation; it does not excuse a name/KDoc that misrepresents behaviour. Actionable, trivial, defensible; low severity.

---

### A16. onDraw allocates a fresh Matrix (and RectF) per layer per frame in the draw/gesture hot path

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/ZoomableImageView.kt:767`  ·  **efficiency**  ·  severity: low  ·  confidence: high  ·  effort: small  ·  slice: `app-zoomable`

**Ist-Zustand.** In the multi-layer onDraw loop, each visible layer does `drawMatrix.reset(); drawMatrix.set(layer.buildMatrix())`. WallpaperLayer.buildMatrix() allocates a brand-new `Matrix()` on every call (WallpaperLayer.kt:120-125). Additionally, for the active layer in edit mode, drawSelectionHighlight -> layer.getTransformedBounds() (WallpaperLayer.kt:131-136) allocates another Matrix plus a RectF every frame. onDraw is invalidated on every ACTION_MOVE (line 937) and on every snap-back animation frame (line 1323), so during a pan/pinch/snap this allocates N Matrix objects (+ Matrix+RectF for selection) per frame. Also `drawMatrix.reset()` before `drawMatrix.set(...)` is redundant since set() fully overwrites the matrix (same redundant reset+set in composeToBitmap at lines 688-689).

**Warum suboptimal.** Per-frame heap allocation in a View.onDraw called continuously during gestures/animation creates avoidable GC pressure and jank on a touch-driven custom view. buildMatrix()/getTransformedBounds() force allocation even though the view already owns a reusable scratch matrix (drawMatrix).

**Richtung.** Write scale/translate directly into the reusable drawMatrix (drawMatrix.reset(); postScale; postTranslate) instead of allocating via buildMatrix(); add a buildMatrixInto(Matrix)/getTransformedBoundsInto(RectF, Matrix) variant on WallpaperLayer for the draw path, keeping the allocating versions only for cold callers. Drop the redundant reset() before set().

> **Verify-Evidenz.** All claims verified in current code. ZoomableImageView.kt:767-768 calls layer.buildMatrix() per visible layer per frame inside onDraw; WallpaperLayer.buildMatrix() (WallpaperLayer.kt:120-125) allocates a fresh Matrix() each call, and getTransformedBounds() (131-136) allocates RectF+Matrix each call for the active layer's selection highlight. onDraw is continuously re-invalidated during ACTION_MOVE (line 937) and snap-back animation frames (line 1323), so this is a genuine per-frame allocation hot path. The reset()+set() before a full set() overwrite is genuinely redundant (also at 688-689). The prior §9.8 audit (AUDIT.md:1106-1202) reviewed onDraw for crash-safety only (recycled-bitmap guards, no-catch rationale) and explicitly did not touch efficiency/allocation; no other doc covers it. Avoiding onDraw allocation is a recognized best practice and the view already holds a reusable drawMatrix scratch, making the fix small and low-risk — a defensible efficiency improvement, not taste/style. Impact is modest (edit-mode-only, few layers), hence low severity, but it survives.

---

### A17. ZOOM_OUT_MULTIPLIER value (0.05) contradicts its own comment and the worked examples

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/ZoomableImageView.kt:113`  ·  **drift**  ·  severity: low  ·  confidence: medium  ·  effort: trivial  ·  slice: `app-zoomable`

**Ist-Zustand.** `ZOOM_OUT_MULTIPLIER = 0.05f` is commented `// Min 25% des Cover-Scales` (25% would be 0.25). The worked-example comments in effectiveMinScale (lines 155-156) also assume a 0.25 multiplier: "Referenz=1.0, Min=0.25" and "Referenz=40.0, Min=10.0" — with the actual 0.05 value those results are 0.05 and 2.0. The comments describe an earlier 0.25 constant that was later changed to 0.05 without updating them.

**Warum suboptimal.** Stale numeric comments on tuning constants mislead the next maintainer about the intended zoom-out floor; the 5x discrepancy is exactly the kind of thing that gets 'fixed' in the wrong direction.

**Richtung.** Decide whether 0.05 or 0.25 is intended and align the constant, the line-113 comment, and the lines 155-156 worked examples to match.

> **Verify-Evidenz.** Confirmed in current code: ZoomableImageView.kt:113 sets ZOOM_OUT_MULTIPLIER=0.05f but its comment says "Min 25% des Cover-Scales" (0.25). The worked-example comments at lines 155-156 ("Referenz=1.0, Min=0.25" / "Referenz=40.0, Min=10.0") are exactly referenceScale*0.25, proving they were authored for a 0.25 value and left stale after the constant was changed to 0.05 (which would yield 0.05 and 2.0). This is genuine, actionable comment/constant drift on a tuning knob — the kind that gets "fixed" in the wrong direction. The prior systematic audit of this file (AUDIT.md §9.8) covered only crash-safety Exception→Throwable bugs and did not note this; no doc (AUDIT/REVIEWS/TODO/KNOWN_ISSUES/ACCEPTED_LIMITATIONS) covers it. Rule 13 (grandfathered German comments) does not shield a numeric-accuracy correction. verified_true, real (if low-severity) improvement, not documented intentional → survives.

---

### A18. validateJsonTypes rejects on a field (split_mode_threshold) nothing else reads or writes

`data/src/main/java/com/github/reygnn/kolibri_launcher/data/BackupSerializer.kt:393`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `data-backup`

**Ist-Zustand.** `intFields` in validateJsonTypes lists "split_mode_threshold" alongside text_color/chip_bg_color. A grep across :domain, :data and :app shows `split_mode_threshold` (and any splitMode variant) exists nowhere else: buildBackupData never writes it, parseStrictly/mergeWithStrictValues never read it into LauncherSettings, and it is not a field on the model.

**Warum suboptimal.** It is a stale key from a removed/never-shipped feature. Worse than merely dead: because it participates in validateJsonTypes, a backup whose defunct split_mode_threshold happens to be non-numeric (e.g. a hand-edited or foreign JSON) makes the WHOLE backup fail type validation and return null — rejecting an otherwise valid restore over a field the app ignores.

**Richtung.** Drop "split_mode_threshold" from the intFields list (and confirm no LauncherSettings field was intended). If a split-mode setting is planned, wire it through parse/merge/build first.

> **Verify-Evidenz.** Confirmed against current code: `split_mode_threshold` occurs exactly once in the whole repo — the intFields list at BackupSerializer.kt:393 — and nowhere else. buildBackupData never writes it, parseStrictly/mergeWithStrictValues (lines 216-217, 343-344) only read the real keys text_color/chip_bg_color, and no LauncherSettings/model field exists for it. It is genuinely dead: a stale key for a removed/never-shipped feature. Because validateJsonTypes returns false (rejecting the entire restore, see call at line 78) when any listed field is non-numeric, a foreign or hand-edited backup carrying a non-numeric split_mode_threshold would be rejected over a field the app ignores — a small latent over-rejection on top of dead code. No CLAUDE.md rule, KNOWN_ISSUES, ACCEPTED_LIMITATIONS, AUDIT, REVIEWS, or TODO entry covers this. Cleanup is trivial and defensible, not mere style churn.

---

### A19. Ten unused imports left over from the monolith split

`data/src/main/java/com/github/reygnn/kolibri_launcher/data/BackupRepositoryImpl.kt:19`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `data-backup`

**Ist-Zustand.** BackupRepositoryImpl only injects assembler, serializer, wallpaperFileManager and Context, yet still imports the 8 repository interfaces (FavoritesRepository, FavoritesOrderRepository, HiddenAppsRepository, CustomNamesRepository, InstalledAppsRepository, SettingsRepository, SwipeActionsRepository, WallpaperRepository), plus SwipeSlot (line 27) and core.coerceInSafe (line 8). Grep confirms none of these symbols appear anywhere in the file outside their import lines — they moved to BackupDataAssembler during the 2026-05-03 split.

**Warum suboptimal.** Dead imports misrepresent the class's real dependency surface (the file header claims 'Android-runtime only' dependencies, but the imports still advertise every repository), and invite a reader to think these repos are used here.

**Richtung.** Delete the 8 repository-interface imports plus SwipeSlot and coerceInSafe.

> **Verify-Evidenz.** Grep confirms each of the 10 named symbols (CustomNamesRepository, FavoritesOrderRepository, FavoritesRepository, HiddenAppsRepository, InstalledAppsRepository, SettingsRepository, SwipeActionsRepository, WallpaperRepository at lines 19-26; SwipeSlot at 27; coerceInSafe at 8) appears only on its own import line and nowhere in the class body. The constructor injects only assembler, serializer, wallpaperFileManager, and Context, matching the file header's own claim that this layer has 'Android-runtime only' dependencies. These are genuine dead imports left over from the 2026-05-03 monolith split, where the repository dependencies moved to BackupDataAssembler. This is defensible dead-code cleanup, not taste/style, and no doc (CLAUDE.md, AUDIT.md, REVIEWS.md, TODO.md) marks it intentional — the docs only discuss the file's OOM catch pattern and the split itself. Severity is low (unused imports are harmless, non-executing), but the finding is factually correct and actionable.

---

### A20. previewBackup opens the file and reads magic bytes twice via isZipFile

`data/src/main/java/com/github/reygnn/kolibri_launcher/data/BackupRepositoryImpl.kt:635`  ·  **efficiency**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `data-backup`

**Ist-Zustand.** In previewBackup, isZipFile(uri) is called at line 635 (to pick the size limit) and again at line 646 (to pick the read path). Each call opens a fresh contentResolver.openInputStream and reads the 2-byte PK magic. loadBackupFromFile has the same pattern to a lesser degree.

**Warum suboptimal.** Two full content-provider stream opens for one boolean that cannot change between the two calls; on SAF/content URIs an open is non-trivial. Not a hot render path, but a gratuitous double I/O on every preview.

**Richtung.** Compute `val isZip = isZipFile(uri)` once and reuse it for both the size-limit and format-detection branches.

> **Verify-Evidenz.** Claim is factually accurate: previewBackup calls isZipFile(uri) at line 635 (size-limit branch) and again at line 646 (format-detection branch). isZipFile (line 183-193) opens a fresh contentResolver.openInputStream and reads 2 magic bytes on every call. Same URI, deterministic result that cannot change between the two calls, so the second open is pure redundant content-provider I/O. Hoisting to `val isZip = isZipFile(uri)` once is a safe, zero-risk DRY/efficiency improvement with no behavioral change — not mere style churn. Not covered by any doc: AUDIT.md line 1559 mentions isZipFile only regarding its Throwable catch, not the double-open. Severity is low (preview is a user-triggered, non-hot path), but the improvement is genuine and defensible.

---

### A21. parseStrictly and mergeWithStrictValues restate the same ~25-field JSON→LauncherSettings mapping

`data/src/main/java/com/github/reygnn/kolibri_launcher/data/BackupSerializer.kt:205`  ·  **duplication**  ·  severity: low  ·  confidence: medium  ·  effort: medium  ·  slice: `data-backup`

**Ist-Zustand.** parseStrictly (307-377) builds a LauncherSettings from scratch out of getStrict* calls for ~25 scalar fields; mergeWithStrictValues (205-256) overlays the exact same ~25 fields with `strict ?: kotlinx` fallback. The camelCase/snake_case key strings and getStrict* choices are duplicated line-for-line between the two, and a third copy of the field-name lists lives in validateJsonTypes (393-451). buildBackupData is a fourth touch-point.

**Warum suboptimal.** Adding or renaming one backup setting requires editing 3-4 places in lockstep; TODO §Phase-2 already records the favoritesAlignment change having to be applied to validateJsonTypes + mergeWithStrictValues + parseStrictly together. Any missed site is a silent forward/back-compat drop, not a compile error. This is the biggest maintenance-drift surface in the slice.

**Richtung.** Extract one `extractStrictSettings(json, base: LauncherSettings): LauncherSettings` helper that both paths call — mergeWithStrictValues passes the kotlinx result as `base`, parseStrictly passes a default `LauncherSettings()`. The overlay/build logic collapses to a single field list.

> **Verify-Evidenz.** Verified against the current file. parseStrictly (307-377) and mergeWithStrictValues (205-256) genuinely restate the same scalar JSON->LauncherSettings mapping: ~21 fields with identical snake_case/camelCase key strings and identical getStrict* choices, including the favoritesAlignment/wallpaperSurfaceMode/sortOrder dual-key ?: chains. The only per-line delta is the merge path's `?: backup.settings.field` fallback tail, which the suggested extractStrictSettings(json, base) helper cleanly absorbs (merge passes backup.settings, parseStrictly passes a null-default LauncherSettings()). Behavior-preserving unification is achievable, including wallpaperLayers (`?: base.wallpaperLayers` collapses to `?: emptyList()` for the fresh base). The maintenance-drift hazard is not hypothetical: TODO.md:1902-1905 records that adding favoritesAlignment had to touch validateJsonTypes + mergeWithStrictValues + parseStrictly in lockstep, and a missed site is a silent forward/back-compat drop, not a compile error. That makes this a demonstrated DRY improvement, not mere taste. No doc blesses the duplication as intentional (AUDIT.md:1532 only covers the file's narrow-catch discipline; TODO Phase-2 is a changelog proving the hazard, not accepting it). Caveat, not fatal: the finding slightly overstates scope — merge omits the 4 collection fields and validateJsonTypes is type-grouped for a different purpose (not cleanly foldable), so the real consolidation is 2 sites, not the claimed 3-4. Survives.

---

### A22. Injected `context` dependency is dead across five DataStore repositories

`data/src/main/java/com/github/reygnn/kolibri_launcher/data/FavoritesRepositoryImpl.kt:109`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: small  ·  slice: `data-repos-datastore`

**Ist-Zustand.** FavoritesRepositoryImpl (line 109), FavoritesOrderRepositoryImpl (line 134), HiddenAppsRepositoryImpl (line 99), SettingsRepositoryImpl (line 30) and CustomNamesRepositoryImpl (line 232) all take an `@ApplicationContext context: Context` and store it, but the body never dereferences it (grep for `context.` returns nothing — it appears only in assignments, KDoc `@property` lines, and constructor plumbing). In FavoritesRepositoryImpl and FavoritesOrderRepositoryImpl the unused value is additionally threaded through the dual-constructor / `createForTesting` factory machinery (FavoritesOrderRepositoryImpl.kt:168 passes `context` into the private ctor purely to store it unused), forcing every test to supply a Context nothing reads.

**Warum suboptimal.** A Hilt-injected dependency and a stored property that is never used is pure noise: it widens the constructors (and their test factories), obscures each repo's real dependency surface, and misleads readers into thinking these repos touch the Android Context — the exact dependency the :data/:domain separation tries to minimise.

**Richtung.** Drop the `context` parameter, property, `@ApplicationContext` binding and the KDoc `@property context` from all five repos; for Favorites/FavoritesOrder also remove it from the secondary/`createForTesting` signatures and their test call sites.

> **Verify-Evidenz.** Confirmed in current code: `context` is stored but never dereferenced in all five DataStore repos. `grep 'context\.'` across FavoritesRepositoryImpl, FavoritesOrderRepositoryImpl, HiddenAppsRepositoryImpl, SettingsRepositoryImpl, CustomNamesRepositoryImpl returns zero hits — the identifier appears only in @Inject/@ApplicationContext constructor plumbing, private-property assignment, and copy-pasted KDoc `@property context Application context for system access`. FavoritesOrderRepositoryImpl:168 verifiably threads the unused context through createForTesting into the private ctor solely to store it. None of the classes has a base class that could consume context (interface-only impls; FavoritesOrder's field is even private). No doc (CLAUDE.md, test CLAUDE.md, AUDIT.md, REVIEWS.md, TODO.md, KNOWN_ISSUES.md, ACCEPTED_LIMITATIONS.md) marks this as intentional. Removing a dead injected dependency is a real, actionable improvement that reduces the constructor/binding/test-plumbing surface and eliminates a misleading Android-Context coupling in a module whose architecture deliberately minimizes it. Not mere style churn. Survives.

---

### A23. Favorites/HiddenApps add/remove do read-modify-write outside the DataStore transaction

`data/src/main/java/com/github/reygnn/kolibri_launcher/data/FavoritesRepositoryImpl.kt:211`  ·  **latent-correctness**  ·  severity: low  ·  confidence: medium  ·  effort: small  ·  slice: `data-repos-datastore`

**Ist-Zustand.** addFavoriteComponent reads `favoriteComponentsFlow.first()` (line 195) then edits (211) computing `currentFavorites + componentName`; removeFavoriteComponent (229/235) and HiddenAppsRepositoryImpl.hideComponent (137/144) and showComponent (164/171) do the same read-then-edit. The sibling batch methods in the same files read inside the edit lambda: cleanupFavoriteComponents (FavoritesRepositoryImpl.kt:277-278) and updateComponentVisibilities (HiddenAppsRepositoryImpl.kt:192-193) use `preferences[KEY]`.

**Warum suboptimal.** DataStore serializes the `edit` block but not the earlier `flow.first()` read, so two concurrent add/hide ops both read the same stale set and the second edit overwrites the first — a lost update; the package-limit check in addFavoriteComponent also runs on a stale snapshot. Impact is near-zero today (single-user tap-driven UI), which is why it hasn't fired, but it is a real race and inconsistent with the atomic pattern the batch methods already use.

**Richtung.** Compute the new set from `preferences[KEY]` inside the `edit` lambda (matching cleanupFavoriteComponents / updateComponentVisibilities); keep the early-return short-circuit as an optimistic pre-check.

> **Verify-Evidenz.** Confirmed in current code: addFavoriteComponent (195→211-213) and removeFavoriteComponent (229→236) compute the new set from the outside `favoriteComponentsFlow.first()` snapshot instead of from `preferences[KEY]` inside the edit lambda, whereas the sibling batch methods cleanupFavoriteComponents (277-278) and saveFavoriteComponents read/write inside the transaction. This is a genuine non-atomic read-modify-write, i.e. a lost-update / stale-snapshot pattern, and is drift relative to the codebase's own atomic batch pattern. Not documented anywhere (AUDIT/REVIEWS/TODO/CLAUDE/KNOWN_ISSUES/ACCEPTED_LIMITATIONS) — prior audits touched this file for the purge-helper and buildConfig only. Concurrent user-driven adds are practically unreachable (single context-menu taps on Main), but a concrete cross-method race exists: broadcast-driven cleanupFavoriteComponents (PackageUpdateReceiver) can run concurrently with a user add, resurrecting a just-removed orphan favorite (wrong state, not a crash). Atomicity in the persistence layer is a correctness property, not taste. Severity is low and the finder's suggested direction is slightly incomplete — the package-limit enforcement must also move inside the edit lambda to be fully correct — but the finding is factually true, undocumented, and a real (latent) improvement, so it survives.

---

### A24. The event-vs-Flow rationale is spelled out three times in ~200 lines of prose

`data/src/main/java/com/github/reygnn/kolibri_launcher/data/CustomNamesRepositoryImpl.kt:19`  ·  **duplication**  ·  severity: low  ·  confidence: medium  ·  effort: small  ·  slice: `data-repos-datastore`

**Ist-Zustand.** The same argument (Zebra→Apple sorting problem, Single-Source-of-Truth, DiffUtil-does-the-optimization) is written three times: 'ARCHITECTURAL NOTE #2' (lines 19-90), 'ARCHITECTURAL NOTE' (lines 93-150), and again in the class KDoc (lines 152-227).

**Warum suboptimal.** ~200 lines of triplicated justification for one design decision is a drift hazard (three copies to keep in sync) and buries the small amount of class-specific documentation; it is far longer than the code it documents.

**Richtung.** Collapse to one canonical note (keep the class KDoc, delete or one-line-pointer the two duplicate free-standing NOTE blocks).

> **Verify-Evidenz.** Confirmed in the current file: three documentation blocks (lines 19-90 'ARCHITECTURAL NOTE #2', 93-150 'ARCHITECTURAL NOTE', 152-227 class KDoc) totaling ~200 lines justify one design decision (event-trigger vs Flow / why-not-granular-events). The class KDoc explicitly restates both free-standing notes — the Zebra→Apple/SSoT/DiffUtil rationale appears in both Note #2 (36-81) and the class KDoc (190-209); the Flow-vs-event rationale appears in both Note (93-150) and the class KDoc (166-188). The finder's 'three times' is structurally imprecise (the two free-standing notes cover two sub-questions the KDoc merges), but the essence — heavy, largely triplicated design prose far longer than the code and a real multi-copy drift hazard — is accurate. Not documented-intentional anywhere: prior audits (AUDIT.md:653/1537) address only this file's code semantics, and REVIEWS.md's remark about over-long KDoc is satire, not a decision to keep duplicate copies. Collapsing to one canonical note is a defensible duplication cleanup, not mere style. Minor severity given it is comment-only in a codebase that deliberately over-documents.

---

### A25. Three unused private declarations in UsageExportRepositoryImpl

`data/src/main/java/com/github/reygnn/kolibri_launcher/data/UsageExportRepositoryImpl.kt:60`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `data-system-di`

**Ist-Zustand.** `json` (kotlinx.serialization Json, lines 60-64), `isoFormatter` (line 70) and `localFormatter` (lines 73-75) are all declared but never referenced. Export builds JSON by hand with StringBuilder + JSONObject.quote (buildExportJson), import parses via org.json.JSONObject, and formatTimestamp/parseTimestamp use Instant.toString()/Instant.parse directly — none of the three fields is read anywhere in the file (grep confirms only the declarations).

**Warum suboptimal.** Dead members carry an unused kotlinx.serialization import and two DateTimeFormatter allocations, and mislead a reader into thinking timestamps flow through those formatters. The `json` instance is a leftover from the `UsageExportData`/kotlinx-serialization model that AUDIT.md §8.6 already deleted; the formatters were superseded by the raw Instant calls.

**Richtung.** Delete `json`, `isoFormatter`, and `localFormatter` (and the now-unused `kotlinx.serialization.json.Json` and `ZoneId` imports).

> **Verify-Evidenz.** Confirmed in current code: `json` (60-64), `isoFormatter` (70) and `localFormatter` (73-75) are declared but never referenced — grep shows each name appears only at its declaration site. Export builds JSON by hand (buildExportJson + JSONObject.quote), import parses via org.json.JSONObject, and formatTimestamp/parseTimestamp call Instant.toString()/Instant.parse directly. Deleting them (plus the then-unused kotlinx.serialization.json.Json and ZoneId imports — ZoneId is only used by localFormatter at line 75) is a genuine dead-code cleanup, not style churn. AUDIT.md §8.6 covered a different symbol (the now-deleted domain/model/UsageExport.kt file, resolved 2026-05-08), not these leftover impl fields; no other doc (TODO/REVIEWS/etc.) tracks them. verified_true AND is_real_improvement AND NOT documented_intentional → survives.

---

### A26. Version-check condition is redundant and its comment lies about accepted versions

`data/src/main/java/com/github/reygnn/kolibri_launcher/data/UsageExportRepositoryImpl.kt:180`  ·  **drift**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `data-system-di`

**Ist-Zustand.** `if (version != USAGE_EXPORT_VERSION && version != "1.0.0")` — USAGE_EXPORT_VERSION is the literal "1.0.0" (line 67), so the two clauses are identical and the whole check reduces to `version != "1.0.0"`. The adjacent comment on line 179 says 'akzeptiere 1.0.0 und 1.1.0', but 1.1.0 is never accepted; a 1.1.0 file would be rejected as UnsupportedVersion.

**Warum suboptimal.** The duplicated literal is dead logic, and the comment documents behaviour the code does not have — a maintainer trusting the comment would ship a broken forward-compat assumption.

**Richtung.** Collapse to a single comparison against the constant, and either drop the 1.1.0 claim from the comment or add the 1.1.0 literal explicitly if forward compatibility is actually intended.

> **Verify-Evidenz.** Verified in current code: USAGE_EXPORT_VERSION = "1.0.0" (line 67), so line 180's `version != USAGE_EXPORT_VERSION && version != "1.0.0"` has two identical clauses — the second is dead logic and the check reduces to `version != "1.0.0"`. The line-179 comment 'akzeptiere 1.0.0 und 1.1.0' is false: a 1.1.0 file is rejected as UnsupportedVersion. No doc (CLAUDE.md, AUDIT.md, REVIEWS.md, TODO.md, KNOWN_ISSUES.md, ACCEPTED_LIMITATIONS.md) covers this specific redundant-condition/misleading-comment drift; the AUDIT.md UsageExport entries concern contract-test ADRs, the throwable audit, and a different stale comment at line 130. Rule 13 grandfathers the German comment's language only, not its factual incorrectness. Collapsing the duplicate literal and correcting/aligning the comment is a small but genuine, non-cosmetic improvement — it removes dead logic and a comment that misstates behaviour. Severity is low (no runtime bug; export writes 1.0.0 and only 1.0.0 is ever accepted, so behaviour is internally consistent today).

---

### A27. Unused Hilt entry-point method exposing the concrete impl type

`data/src/main/java/com/github/reygnn/kolibri_launcher/data/InstalledAppsRepositoryEntryPoint.kt:15`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `data-system-di`

**Ist-Zustand.** `getInstalledAppsRepository(): InstalledAppsRepositoryImpl` is declared on the entry point but never called anywhere in the codebase (grep finds only the declaration). The sole caller, PackageUpdateReceiver, uses only `getAppUpdateSignal()`.

**Warum suboptimal.** Dead accessor, and it also leaks the concrete `InstalledAppsRepositoryImpl` (not the interface) across the entry-point boundary — an unnecessary Rule-1-adjacent smell for a method nobody uses.

**Richtung.** Remove the `getInstalledAppsRepository()` method from the entry point.

> **Verify-Evidenz.** Confirmed in current code: the entry point declares getInstalledAppsRepository(): InstalledAppsRepositoryImpl, but grep across the whole repo finds only the declaration — no call site. The sole consumer, PackageUpdateReceiver.processPackageUpdate (line ~123), invokes only getAppUpdateSignal(). The unused method is genuine dead code and additionally leaks the concrete InstalledAppsRepositoryImpl type across the entry-point boundary. Removing it is a trivial, low-risk cleanup, not mere style. No CLAUDE.md rule, KNOWN_ISSUES, ACCEPTED_LIMITATIONS, AUDIT, REVIEWS, or TODO entry covers or justifies this method.

---

### A28. WallpaperLayerState.toMap()/fromMap() are dead serialization methods (with Rule-5-contradicting KDoc)

`domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/model/WallpaperState.kt:57`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `domain-api-models`

**Ist-Zustand.** WallpaperLayerState exposes toMap() (line 97) and fromMap() (line 57) as a full Map-based serialization pair, documented as 'Persistierbar über toMap() / fromMap()' (line 9) and 'Restore aus SharedPreferences' (line 55) / 'für SharedPreferences / JSON' (line 95). A repo-wide grep (production + tests + fixtures) finds zero callers of either method; the only '.toMap()' hit is a stdlib Map call in FakeCustomNamesRepository. All real wallpaper persistence goes through DataStore and the @Serializable WallpaperLayerBackup.toLayerState()/fromLayerState() path in BackupData.kt.

**Warum suboptimal.** Two never-called methods plus ~40 lines of Map<->field mapping sit in a pure-domain model as maintenance surface that silently drifts from the actual (WallpaperLayerBackup) serialization. Their KDoc also advertises SharedPreferences as the store, contradicting Rule 5 (DataStore is the only app storage), so a reader could be misled into thinking a SharedPreferences path still exists.

**Richtung.** Delete toMap()/fromMap() and the DEFAULT_SCALE-only companion bits they need, plus the SharedPreferences KDoc lines; if a Map form is ever needed again it can be reintroduced next to the WallpaperLayerBackup path. Confirm no reflection/serialization framework references the names first (none found).

> **Verify-Evidenz.** Verified in current code: WallpaperLayerState.fromMap() (line 57) and toMap() (line 97) have zero callers anywhere in production, tests, or domain/testFixtures. The only other .toMap() reference is a stdlib Map call in FakeCustomNamesRepository.kt:62. Actual wallpaper persistence uses two unrelated paths — DataStore JSON via WallpaperRepositoryImpl.layersToJson/parseLayersFromJson, and backup via the @Serializable WallpaperLayerBackup.toLayerState()/fromLayerState() pair (BackupDataAssembler.kt:115, BackupRepositoryImpl.kt:426). So these are genuinely dead ~40-line Map serialization methods sitting in a pure-domain model as maintenance/drift surface. Their KDoc (lines 9, 55, 95) additionally advertises SharedPreferences as the store, which contradicts Rule 5 (DataStore is the only app storage) and could mislead a reader. No doc (CLAUDE.md, AUDIT.md, REVIEWS.md, TODO.md, KNOWN_ISSUES.md, ACCEPTED_LIMITATIONS.md) covers or tracks this; not part of crash-safety infrastructure. Deleting them is a defensible, low-risk improvement, not mere style.

---

### A29. WallpaperSurfaceMode.AUTO KDoc claims the classifier is unshipped and AUTO resolves to DARK

`domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/model/WallpaperSurfaceMode.kt:8`  ·  **drift**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `domain-api-models`

**Ist-Zustand.** The AUTO KDoc states: 'The classifier itself ships in the follow-up commit; until then, [AUTO] resolves to [DARK] — regression-safe, matches the pre-feature behaviour.' The classifier has since shipped: ClassifyWallpaperUseCase + ResolveWallpaperSurfaceUseCase now map AUTO to a live LuminanceClassification from wallpaper luminance and system colour hints (AUTO no longer resolves to DARK unconditionally).

**Warum suboptimal.** The comment describes a temporary pre-feature state that no longer exists, actively misleading a reader about how AUTO behaves. Documented-behaviour drift on a model that other layers key surface colour off of.

**Richtung.** Update the KDoc to say AUTO delegates to the wallpaper classifier (ResolveWallpaperSurfaceUseCase/ClassifyWallpaperUseCase), with DARK only as the both-signals-unavailable fallback.

> **Verify-Evidenz.** Confirmed factually true. WallpaperSurfaceMode.kt lines 6-9 state the classifier "ships in the follow-up commit; until then, [AUTO] resolves to [DARK]." Both ClassifyWallpaperUseCase and ResolveWallpaperSurfaceUseCase now exist in domain/usecase/, and ResolveWallpaperSurfaceUseCase.invoke() line 31 maps AUTO to the live LuminanceClassification from ClassifyWallpaperUseCase (which combines wallpaper luminance + system colour hints), not to DARK unconditionally. Tests "AUTO mode delegates to classifier — DARK path"/"LIGHT path" corroborate. The KDoc describes a temporary pre-feature state that no longer exists and actively misleads readers about how AUTO behaves — genuine documentation drift on a model other layers key surface colour off. Not covered by CLAUDE.md, KNOWN_ISSUES, ACCEPTED_LIMITATIONS, AUDIT, REVIEWS, or TODO. Fix is a trivial KDoc update. Not mere style/taste.

---

### A30. BuildAppContextMenuUseCase KDoc says labels are '@StringRes ids' but they are LauncherActionLabel sealed identifiers

`domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/usecase/BuildAppContextMenuUseCase.kt:30`  ·  **drift**  ·  severity: low  ·  confidence: medium  ·  effort: trivial  ·  slice: `domain-api-models`

**Ist-Zustand.** The class KDoc says 'Labels are emitted as @StringRes ids (see AppContextMenuAction.LauncherAction). The Adapter resolves them to user-visible strings at bind time.' In reality LauncherAction.label is a LauncherActionLabel sealed-class identifier (AddToFavorites, RenameApp, ...), and the UI maps it to R.string via LauncherActionLabel.toStringResId() — precisely the sealed-identifier-not-@StringRes discipline CLAUDE.md documents for the domain.

**Warum suboptimal.** The comment contradicts both the code and the project's own stated model-design rule, describing an @StringRes-Int design the domain deliberately avoids. Small but a direct source of confusion for anyone reasoning about the domain/UI label boundary.

**Richtung.** Reword to 'Labels are emitted as LauncherActionLabel sealed identifiers; the adapter maps each to R.string at bind time', matching the actual model.

> **Verify-Evidenz.** Confirmed at BuildAppContextMenuUseCase.kt:30-31: the KDoc says "Labels are emitted as @StringRes ids ... The Adapter resolves them to user-visible strings at bind time," but LauncherAction.label is declared as LauncherActionLabel (AppContextMenuAction.kt:22) and assigned sealed identifiers (AddToFavorites, RenameApp, RestoreOriginalName, ...). The model's own KDoc (line 17) already calls it a "label identifier." CLAUDE.md documents the domain's sealed-identifier-not-@StringRes rule for exactly this AppContextMenuAction.LauncherAction.label case, so the comment describes a design the domain deliberately avoids. Not covered by AUDIT.md (which only cites the safelyXxx catch pattern for this file), REVIEWS.md, or TODO.md. A trivial but genuine comment-accuracy fix that removes a direct contradiction with both code and rule. Minor severity, but a real, defensible improvement.

---

### A31. Purgeable KDoc frames it as an androidTest-only reset hook, but it drives production factory reset

`domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/repository/Purgeable.kt:3`  ·  **drift**  ·  severity: low  ·  confidence: medium  ·  effort: trivial  ·  slice: `domain-api-models`

**Ist-Zustand.** The KDoc reads 'Ein Interface für androidTest Repositories, deren Zustand in Tests zurückgesetzt werden kann.' In production, ResetRepositoryImpl iterates purgeRepository() over ~11 Purgeable children to implement the user-facing factory/user/settings reset (FactoryResetUseCase). The androidTest set that originally motivated the name was deleted ~6 months ago; purge is now a production contract, exercised by contract tests, not a test-only affordance.

**Warum suboptimal.** The interface's documented purpose is stale and understates its role — a reader could wrongly treat purgeRepository() as test scaffolding safe to no-op, when several impls' factory-reset correctness depends on it.

**Richtung.** Update the KDoc to describe Purgeable as the reset contract used by ResetRepository/factory-reset (and additionally exercised by contract tests). German comment may stay German per Rule 13; only the content needs correcting.

> **Verify-Evidenz.** Confirmed factually: Purgeable.kt:4 KDoc frames the interface as being for "androidTest Repositories … reset in tests," but ResetRepositoryImpl injects ~11 Purgeable repositories and its purgeAll() invokes purgeRepository() on each to implement the production factory reset (FactoryResetUseCase / ResetRepository). AUDIT.md §1.3 independently describes ResetRepository as "iteration over 11 Purgeable children," confirming the production contract; the androidTest set that motivated the name was deleted ~6 months ago. The KDoc is therefore stale and understates the interface's real role — a reader could wrongly assume purgeRepository() is safe test-only scaffolding when several impls' factory-reset correctness depends on it. Not documented intentional (Rule 13 only grandfathers the German language, not incorrect content; no AUDIT/REVIEWS/TODO entry tracks this drift). It is a trivial, defensible doc-accuracy fix, not mere style, so it survives.

---

### A32. Four AppConstants have zero references anywhere in the codebase

`domain/src/main/java/com/github/reygnn/kolibri_launcher/core/AppConstants.kt:260`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `domain-core`

**Ist-Zustand.** BORDER_ALPHA (line 268), FALLBACK_BORDER_WIDTH_PX (264), FALLBACK_CORNER_RADIUS_PX (265) and SCROLL_VERIFICATION_DELAY_MS (260) are declared but referenced nowhere across :app/:data/:domain main or test sources (verified by whole-repo grep of each identifier excluding AppConstants.kt). git blame shows they last moved in the §9.2 domain-extraction refactor (54eec88, 2026-05-03) and their former call sites are gone.

**Warum suboptimal.** AUDIT.md line 217 asserts AppConstants is 'diszipliniert genutzt', but these four are stale border-styling / scroll-timing constants left behind after their consumers were removed. They add noise to a 117-constant file and quietly falsify the 'every constant is used' assumption a reader relies on.

**Richtung.** Delete the four unused constants (and their KDoc/comment lines). If any is a deliberate not-yet-wired anchor, add a one-line comment saying so; otherwise remove.

> **Verify-Evidenz.** Confirmed: whole-repo grep (.kt + .xml, excluding AppConstants.kt) finds zero references to any of the four constants. Git -S history shows they were consumed by ScrollViewBorderDecorator, which has since been deleted (commit 75cc8b4 removed the split-mode/border dead code but left the constants stranded). These are genuinely orphaned, not not-yet-wired anchors. Not documented-intentional: TODO.md:1318's AppConstants entry concerns 4 unused DataStore *imports* removed during the §9.2 domain split, a distinct matter; nothing in AUDIT/REVIEWS/TODO/CLAUDE tracks these dead constants. AUDIT.md:217 even asserts AppConstants is 'diszipliniert genutzt', which these four contradict, so removal corrects real drift. Low severity (trivial dead-code cleanup) but a valid, actionable improvement.

---

### A33. Three Get*Setting use cases swallow CancellationException via catch(Exception)

`domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/usecase/GetAutoLaunchSettingUseCase.kt:16`  ·  **latent-correctness**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `domain-usecases-A`

**Ist-Zustand.** GetAutoLaunchSettingUseCase, GetAutoShowKeyboardSettingUseCase, and GetTextShadowEnabledUseCase each wrap `settingsRepository.<flow>.first()` in `try { ... } catch (e: Exception) { return <fallback> }`. `CancellationException` is an `Exception`, so a coroutine cancelled while these suspend at `.first()` is caught and converted into a normal fallback return instead of propagating.

**Warum suboptimal.** This breaks structured concurrency (a cancelled scope keeps running / returns a stale default) and is inconsistent with every other use case in the same package (ExportUsageToFileUseCase, FactoryResetUseCase, GetDrawerAppsUseCase, GetFavoriteAppsUseCase, BuildAppContextMenuUseCase) which all explicitly `catch (e: CancellationException) { throw e }` first. It also directly contradicts AUDIT.md line 339 ("CancellationException — überall korrekt rethrow-t"), i.e. the prior audit missed these. Worse, the catch is essentially dead for real I/O: the upstream flows go through `SettingsRepositoryImpl.safeData`, which already catches read errors and emits `emptyPreferences()` (falling back to defaults), so `.first()` won't surface an IOException here — the only thing the catch actually intercepts is cancellation.

**Richtung.** Either drop the try/catch entirely (upstream safeData already guarantees a value) or, if a defensive net is desired, add `catch (e: CancellationException) { throw e }` before the broad catch to match the sibling pattern.

> **Verify-Evidenz.** Verified in current code: GetAutoLaunchSettingUseCase.kt:14-18, GetAutoShowKeyboardSettingUseCase.kt:14-18, and GetTextShadowEnabledUseCase.kt:14-18 each catch `Exception` around `<flow>.first()` with no preceding `catch (e: CancellationException) { throw e }`. Since CancellationException is an Exception, cancellation while suspended at `.first()` is swallowed and converted to a stale fallback return, breaking structured concurrency. This is a genuine (if low-severity) latent-correctness issue, not taste: `.first()` is a real suspension point that actually throws CancellationException on cancellation — unlike the dead synchronous-callback rethrows the maintainer deliberately removed (TODO.md:290, 354). It is inconsistent with ~10 sibling use cases in the same package that all rethrow CancellationException first (GetDrawerAppsUseCase.kt:67, GetFavoriteAppsUseCase.kt:117, FactoryResetUseCase.kt:35), and it directly contradicts AUDIT.md:339 ("CancellationException — überall korrekt rethrow-t"), meaning the prior audit missed these three. Not covered by CLAUDE.md rules, KNOWN_ISSUES, ACCEPTED_LIMITATIONS, REVIEWS, or any TODO entry — the TODO catch-sweep work targets HomeFragment (app/), not these domain use cases. The suggested fix (drop the unnecessary try/catch, or add the rethrow) is actionable, trivial, and matches the maintainer's own cleanup style. Survives.

---

### A34. Empty no-op purgeRepository() on GetFavoriteAppsUseCase

`domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/usecase/GetFavoriteAppsUseCase.kt:161`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `domain-usecases-A`

**Ist-Zustand.** `suspend fun purgeRepository() { // Für Tests }` is a public empty method on the use case. Every `purgeRepository()` call in the test suite targets the underlying repositories (FavoritesRepository, HiddenAppsRepository, CustomNamesRepository), not this use case; no caller of the use-case method exists in app, data, or test sources.

**Warum suboptimal.** It is dead production API — an empty suspend function that does nothing, with a stale 'Für Tests' comment implying a purpose it doesn't serve. It adds a misleading public surface and confuses the repository-vs-usecase purge contract.

**Richtung.** Delete the method.

> **Verify-Evidenz.** Confirmed in current code: GetFavoriteAppsUseCase.kt:161-163 is an empty `suspend fun purgeRepository() { // Für Tests }`. The class (line 59) does not implement Purgeable, so this is not an override — it is a standalone public no-op. Grep confirms every purgeRepository() caller targets repositories (FavoritesRepository, FavoritesOrderRepository, etc.) via ResetRepositoryImpl/Purgeable; ResetRepositoryImpl injects repositories, not use cases. The use case's own test suite (GetFavoriteAppsUseCaseTest) only exercises `.favoriteApps`, never `.purgeRepository()`. No production, data, or test caller of the use-case method exists. Not covered by any doc: AUDIT §8.7 is about :data safePurge dedup on repository impls, unrelated to this dead use-case method; KNOWN_ISSUES/ACCEPTED_LIMITATIONS/TODO/REVIEWS say nothing. Deleting it removes misleading dead public API with a stale 'Für Tests' comment — a genuine, defensible (if minor) cleanup, not style churn.

---

### A35. Two multi-layer convenience methods on SaveWallpaperStateUseCase are dead and duplicate WallpaperDelegate logic

`domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/usecase/SaveWallpaperStateUseCase.kt:46`  ·  **dead-code**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `domain-usecases-B`

**Ist-Zustand.** `updateLayerTransform` (line 46) and `updateAllLayerTransforms` (line 63) have zero callers in main or test — grep finds only the definitions. `updateTransform` (single-layer) is the only convenience method actually used (WallpaperDelegate.kt:338). Meanwhile WallpaperDelegate.onSaveLayerTransform (WallpaperDelegate.kt:565-570) and onSaveAllLayerTransforms (571-583) reimplement the exact same body — `withUpdatedLayer { it.copy(scale/translateX/translateY) }` then `saveWallpaperStateUseCase(newState)` — inline.

**Warum suboptimal.** Dead public API plus byte-for-byte duplicated transform logic living in two places. The delegate can't use the use-case methods because it also needs the computed newState for its local _wallpaperState mirror, so the convenience methods will stay dead. Two future-drift surfaces for no benefit.

**Richtung.** Delete updateLayerTransform and updateAllLayerTransforms; the delegate already owns the equivalent flow. If a shared helper is wanted, have it return the new WallpaperState so the delegate's local-state update can use it too.

> **Verify-Evidenz.** Confirmed factually: updateLayerTransform (SaveWallpaperStateUseCase.kt:46) and updateAllLayerTransforms (line 63) have zero callers anywhere in main or test — a repo-wide grep returns only the two definitions themselves; only the single-layer updateTransform is actually invoked (WallpaperDelegate.kt:338, plus its tests). The delegate reimplements the byte-identical bodies inline (WallpaperDelegate.kt:565-570 and 572-583), because it also needs the computed newState for its local _wallpaperState mirror, so the use-case convenience methods can never be adopted and will stay dead. This is genuinely dead public domain API that duplicates live delegate logic (two future-drift surfaces). Not covered by any rule exception in CLAUDE.md, not tracked in AUDIT.md/REVIEWS.md/TODO.md. Deleting the two unused methods is a defensible, low-risk cleanup. Survives.

---

### A36. retry-predicate catch(Throwable) swallows CancellationException, unlike the rest of the file

`domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/usecase/ObserveInstalledAppsUseCase.kt:53`  ·  **latent-correctness**  ·  severity: low  ·  confidence: medium  ·  effort: trivial  ·  slice: `domain-usecases-B`

**Ist-Zustand.** Inside the .retry {} predicate the only statements that can throw are the retryCount++ (can't) and delay() (throws only CancellationException on collector cancellation). The catch(Throwable) at line 53 catches that CancellationException, routes it through TimberWrapper.silentError, and returns false. Every other catch in this same file carefully rethrows CancellationException first (lines 100-101, 106-107).

**Warum suboptimal.** If the collector is cancelled while the backoff delay is pending, the CancellationException is swallowed here: in DEBUG silentError throws it as a spurious 'Error in retry logic' crash, and in release it is logged and cooperative cancellation is quietly dropped from the predicate. This is the same catch-around-effectively-non-throwing-logic pattern Rule 11 targets, and it is inconsistent with the file's own cancellation discipline.

**Richtung.** Either narrow to `if (cause is IOException)` without the surrounding try/catch (the branch body can't throw except cancellation, which should propagate), or add the `catch (e: CancellationException) throw e` guard used elsewhere in the file before the Throwable catch.

> **Verify-Evidenz.** Confirmed in current code. The retry predicate (lines 44-56) wraps its body in catch(Throwable) at line 53; the only realistic throw inside is delay() at line 48 (retryCount++ and `is IOException` can't throw, delay arg is always positive so delay only throws CancellationException on collector cancellation). That CancellationException is caught here. Via TimberWrapper.silentError -> crashInDebug (TimberWrapper.kt:67-68) it is rethrown in DEBUG as RuntimeException("SILENT_ERROR caught: Error in retry logic", cause) — a spurious dev crash on a normal cancellation; in release the predicate returns false and cooperative cancellation is silently dropped from the predicate. This is inconsistent with the file's own two other catch sites (lines 100-101 and 106-107), which both rethrow CancellationException before the Throwable catch. Not documented-intentional: AUDIT.md:1605 marks the file "verified clean" only for its three-layer crash/OOM profile and never inspects the retry predicate's cancellation handling; it even credits a different use case (FactoryResetUseCase, 1609) for the very cancellation-rethrow discipline this predicate lacks. Rule 9/11's canonical CancellationException-rethrow supports, not exempts, the fix. Trivial one-line guard, aligned with the file's own convention. Low severity because the trigger path is narrow (IOException from getInstalledApps AND cancellation exactly during the backoff delay), but it is a genuine latent inconsistency worth fixing.

---

### A37. ADR justification states wrong child-repository count (11 vs 12)

`domain/src/testFixtures/java/com/github/reygnn/kolibri_launcher/data/ResetRepositoryContract.kt:28`  ·  **drift**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `test-honesty`

**Ist-Zustand.** The ResetRepository ADR-only contract asserts the impl's behavior is to 'iterate over 11 child repositories'. Since FabPositionRepository was added, ResetRepositoryImpl now orchestrates 12 Purgeable children — its own inline comment at ResetRepositoryImpl.kt:116 already says 'a twelfth Purgeable', and the purge lists (resetUserData's 10-entry list plus the separate settings and app-usage purges) total 12.

**Warum suboptimal.** This is the ADR file whose entire purpose is to certify why no executable contract exists for ResetRepository; a stale, now-incorrect count in that justification is exactly the kind of drift the contract-honesty discipline (test CLAUDE.md 'Bewusste Drifts werden dokumentiert, nicht versteckt') is meant to prevent. It silently undercuts the doc's authority.

**Richtung.** Update the count to 12 and, if desired, list fabPosition among the orchestrated children so the ADR matches the impl.

> **Verify-Evidenz.** Verified against current code. ResetRepositoryImpl injects and purges exactly 12 Purgeable children: resetUserData lists 10 (favorites, favoritesOrder, hiddenApps, customNames, swipeActions, wallpaper, fabPosition, installedAppsState, screenLock, timeBasedEvents), resetSettings 1 (settings), resetAppUsageData 1 (appUsage) = 12, matching the 12 constructor params. The ADR at ResetRepositoryContract.kt:28 says the behavior is 'iterate over 11 child repositories' — now off by one. The impl's own purgeAll KDoc (ResetRepositoryImplImpl.kt:116) references 'a twelfth Purgeable' and its 'eleven structurally identical try/catch blocks' phrasing is the pre-fabPosition snapshot, confirming the count grew to 12 when FabPositionRepository was added. Not covered as intentional in any doc — AUDIT.md:91 merely repeats the same stale '11 Purgeable children' figure, so it is drift, not a tracked/accepted state. is_real_improvement: this is a factual count in an ADR file whose authority rests on accuracy and whose whole purpose is contract-honesty ('drifts are documented, not hidden'); a one-word fix (11→12) corrects a genuine documentation drift and is squarely in the audit's 'inconsistency/drift' scope, not mere style. Low severity but survives.

---

### A38. Contract-status table omits FabPositionRepository entirely

`app/src/test/CLAUDE.md:52`  ·  **drift**  ·  severity: low  ·  confidence: high  ·  effort: trivial  ·  slice: `test-honesty`

**Ist-Zustand.** The 'Status je Repository' table claims 'Alle 16 Repository-Interfaces sind behandelt — 12 mit Contract-Pair, 4 mit ADR-only' and lists 16 rows. FabPositionRepository — which has a full, honest contract triple (FabPositionRepositoryContract with 7 @Test, FakeFabPositionRepositoryContractTest, FabPositionRepositoryImplContractTest) — is missing from the table. Actual totals are 17 interfaces / 13 contract-pairs. FabPosition appears in none of AUDIT.md, REVIEWS.md, TODO.md, or this table.

**Warum suboptimal.** This table is the authoritative map that future sessions use to answer 'which repositories are covered and how'. An untracked repository in the very doc that governs contract-coverage honesty means the doc no longer reflects reality — a new contributor could believe FabPosition lacks coverage, or that the suite is complete at 16.

**Richtung.** Add a FabPositionRepository row (Contract ✓ / Impl-CT ✓) and bump the '16 / 12 Contract-Pair' counts to 17 / 13.

> **Verify-Evidenz.** Confirmed factually true. FabPositionRepository is a real 17th repository interface (domain/src/main/.../domain/repository/FabPositionRepository.kt) with a full, honest contract triple: FabPositionRepositoryContract.kt (7 @Test methods, verified), FakeFabPositionRepositoryContractTest.kt, and FabPositionRepositoryImplContractTest.kt. The status table at app/src/test/CLAUDE.md:52-72 asserts completeness ('Alle 16 Repository-Interfaces sind behandelt — 12 mit Contract-Pair, 4 mit ADR-only') and lists exactly 16 rows, omitting FabPosition. Real totals are 17 / 13 contract-rows / 4 ADR-only. FabPosition is mentioned nowhere in AUDIT.md, REVIEWS.md, TODO.md, CLAUDE.md, or this doc (grep: zero hits), so it is not documented-intentional. This file explicitly positions itself as the authoritative 'what exists' map and the table makes a false completeness claim, so correcting it is a genuine (if low-severity) improvement, not style churn.

---
