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

## Fixed for RC1 (fix + regression test)
- **#1 (MED) — Existing wallpaper wiped on an unrestorable-wallpaper restore.**
  Resolved via **preserve-on-failure** (chosen over documenting the wipe as
  intended). `BackupDataAssembler` Phase 7b no longer pre-clears the wallpaper
  state before the restore. Investigation showed the pre-clear was redundant on
  success (the restorer's `saveWallpaperState` is a full overwrite of the single
  `KEY_LAYERS_JSON` blob — single↔multi share that one key, no stale-key risk)
  and consequential only on TOTAL failure, where it produced the wipe. Removing
  it means a backup whose wallpaper is entirely unrestorable (dead `content://`
  from another device, missing ZIP entry) leaves the user's current wallpaper
  intact; `droppedWallpaperLayers` still surfaces the failure. `clearWallpaper()`
  never deleted files, so `gcOrphans` is unaffected. Pinned on BOTH restore
  paths: `BackupRepositoryImplWallpaperTest` (single-layer) and
  `BackupRepositoryImplMultiLayerImportTest` (multi-layer, all image-bearing
  layers inaccessible) — each asserts an existing wallpaper survives an
  unrestorable-backup-wallpaper restore. Verified safe by a two-agent
  adversarial review (correctness + test validity); the review also noted the
  change removes a pre-existing wipe-on-cancel window.
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

## Low-findings follow-up (resolved)

- **#8 (LOW) — `performImport` non-atomic across the 8 repositories.**
  Cannot be fixed in code (DataStore offers no cross-store transaction, and the
  separate stores are deliberate). Documented as `ACCEPTED_LIMITATIONS.md` §8
  with rationale (import is user-initiated, re-runnable, idempotent per phase)
  and a re-evaluation trigger.
- **#5 (LOW) — Untested consent revoke-purge safety net.** Test-only. Added a
  `ConsentControllerTest` case that injects a throwing `purgeReportQueue()` with
  a capturing `CoroutineExceptionHandler` on the app scope and asserts nothing
  escaped `applyConsent`'s catch (so removing the catch turns it red).
- **#6 (INFO) — `ConsentController.currentDecision()` production-dead.** Verified
  zero production callers (class, not an interface); deleted the method and its
  test.
- **#7 (LOW) — Usage `loadFromFile` size guard bypassed on unknown length.**
  `openFileDescriptor(...).statSize == -1` (streaming/pipe providers) skipped the
  `> MAX_BACKUP_SIZE_BYTES` fast-path, reaching an unbounded `readText()`. Fixed
  with a **bounded read**: read ≤ `MAX_BACKUP_SIZE_BYTES + 1` bytes and reject if
  over — deliberately NOT "reject on non-positive statSize" (which would regress
  legitimate small files whose provider reports `-1`/`0`). Pinned by two
  `UsageExportRepositoryImplDoomsdaySpec` cases: unknown-size + small content
  still imports (regression guard against the naive fix), and unknown-size +
  oversized content is rejected "File too large".

## Deferred — logged with re-evaluation trigger

_None — all audit findings are resolved. (Re-evaluation triggers for the
architectural limitations #8 and #7 are recorded above / in `ACCEPTED_LIMITATIONS.md`.)_
