# RC1 Edge-Case Audit — Findings Log

Result of the scoped Section-3 edge-case audit (see `CORRECTNESS-EDGE-CASES.md`)
across the four RC risk surfaces, run 2026-08-29. Two surfaces came back solid;
the parser halves are exceptionally hardened. This log tracks the eight findings:
which were fixed for RC1 and which are deferred, each with a re-evaluation
trigger, so no find is lost (RC gate Section 5).

## Surfaces that came back solid (no open findings)
- **Consent bootstrap chain** — privacy-by-default holds on every constructed
  path; both AUDIT-10 "default that acts" hazards closed and pinned; enable/
  disable ordering double fail-closed; write-failure surfaced as a value.
- **The three named save-gates** (Onboarding `PreselectState`, HiddenApps
  diff-against-initial, Swipe `isLoaded`) + the Onboarding restore/Done race —
  solid, fail-closed, fully pinned.
- **Backup + usage parser halves** — type-confusion, oversize, NaN/Infinity,
  integer overflow, duplicate keys, Unicode/null-byte, deep nesting,
  snake↔camel, null-in-array, missing/unknown fields: all already pinned.

## Fixed for RC1 (fix + regression test on branch fix/rc-edge-cases)
- **#2 (MED) — Usage import aborted by one out-of-range ISO timestamp.**
  `UsageExportRepositoryImpl.parseTimestamp` caught only `DateTimeParseException`;
  a valid ISO instant with an extreme year parses but overflows `Long` on
  `toEpochMilli()` (`ArithmeticException`), which propagated to the file-level
  catch → whole import rejected as `InvalidFormat`. Fixed: also catch
  `ArithmeticException` → skip just that entry (graceful-skip contract).
  Pinned by `UsageExportRepositoryImplTest`.
- **#3 (MED-LOW) — FavoritesSort save-gate failed OPEN.**
  `FavoritesSortViewModel.persistOrder` persisted unconditionally; an empty
  in-memory list (reachable via the Fragment's fail-open parcelable-parse
  `catch → emptyList()` after process death) let Sort/Reset write `"[]"` over
  the stored order. Fixed: gate `persistOrder` on `initialized && apps
  isNotEmpty` (fail-closed, matching the sibling gates). Pinned by
  `FavoritesSortViewModelTest`.
- **#4 (MED, coverage) — ZIP-restore pure logic was untested.**
  `BackupSerializer.resolveZipImages` / `buildPreview` (the CURRENT backup
  format's resolve step) had zero test references. Added `BackupSerializerZipResolveTest`
  (plain JVM) covering entry-present, entry-missing stale-URI fallback,
  single-image resolve/fallback, and preview counts/flags.

## Deferred — logged with re-evaluation trigger

- **#1 (MED) — Existing wallpaper wiped on an unrestorable-wallpaper restore.**
  `BackupDataAssembler` Phase 7b calls `wallpaperRepository.clearWallpaper()`
  BEFORE the restore; if the backup carries a wallpaper whose image is not
  restorable on this device (legacy flat-JSON with a dead `content://`, or a
  ZIP missing its `wallpapers/` entry) and the user currently has a wallpaper,
  the clear runs, the restore adds no layer, and the old wallpaper is lost
  (`droppedWallpaperLayers > 0` surfaces a warning, so not fully silent).
  **Decision pending (user):** intended replace-semantics (→ pin behaviour +
  ACCEPTED_LIMITATIONS) vs. preserve (reorder so clear runs only after ≥1 layer
  restores — a restore-logic behaviour change). **Re-eval trigger:** decide
  before RC1 tag; a Robolectric test in `BackupRepositoryImplWallpaperTest`
  starting from an existing `WallpaperState.single(...)` documents whichever
  direction is chosen.

- **#8 (LOW) — `performImport` is non-atomic across the 8 repositories.**
  A repository write throwing mid-sequence (e.g. Phase 4 DataStore I/O after
  Phases 1-3 committed) returns `ImportResult.Error` with favorites/order/hidden
  already persisted — a partial-state "lying failure". Inherent to spanning
  independent DataStores; likely acceptable. **Action:** add an
  `ACCEPTED_LIMITATIONS.md` entry. **Re-eval trigger:** if a single-transaction
  store or a staged-commit import is ever introduced.

- **#5 (LOW) — Untested consent revoke-purge safety net.**
  `ConsentController.applyConsent` runs `purgeReportQueue()` on `applicationScope`
  (no `CoroutineExceptionHandler`); the `catch (Throwable) { Timber.w }` is the
  only thing preventing a throwing purge from crashing the launcher on revoke,
  and no test makes the purge throw. **Action:** add a `ConsentControllerTest`
  that injects a throwing `purgeReportQueue()` and asserts no exception surfaces.
  **Re-eval trigger:** any refactor of the revoke path.

- **#7 (LOW) — Usage `loadFromFile` size guard bypassed on unknown length.**
  `openFileDescriptor(...).statSize == -1` (streaming/pipe providers) skips the
  `> MAX_BACKUP_SIZE_BYTES` guard → unbounded `readText()`. Needs an adversarial/
  streaming provider (normal SAF documents report an accurate size), and the
  downstream parse caps bound the graph. **Action:** treat non-positive
  `statSize` as reject, or read through a hard byte cap. **Re-eval trigger:** if
  a non-SAF import entry point is added.

## Dead code (cleanup, not a bug)
- **#6 (INFO) — `ConsentController.currentDecision()` is production-dead.**
  The settings summary now flows through `CrashReportingHealthMonitor.evaluate()`;
  `currentDecision()` has no production caller and its KDoc is stale. Delete (and
  its test) or re-point the KDoc — no correctness/privacy impact.
