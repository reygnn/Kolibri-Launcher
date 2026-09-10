package com.github.reygnn.kolibri_launcher.data

/**
 * ============================================================================
 * DATASTORE MAINTENANCE REPOSITORY — NO CONTRACT TEST
 * ============================================================================
 *
 * NO CONTRACT TEST (ADR) — canonical marker read by `./gradlew checkConventions`
 * (tools/check-contract-triple.sh). Rationale below.
 *
 * This file intentionally contains no code. It documents a deliberate gap in
 * the contract-test suite.
 *
 * THE INTERFACE
 *   ```
 *   interface DataStoreMaintenanceRepository {
 *       suspend fun removeOrphanKeys(): Result           // Removed(count) | Failed
 *       suspend fun previewOrphanKeys(): PreviewResult   // Loaded(keyNames) | Failed  (dry run)
 *   }
 *   ```
 *
 * WHY NO CONTRACT (fake-vs-impl parity test)?
 *
 *   The whole behaviour is "remove every settings-store key that no
 *   `OwnsSettingsStoreKeys` owner claims". A FAKE would have to re-implement exactly
 *   that keep-list filter over an in-memory map — so a fake-vs-impl contract would
 *   only assert that two copies of the same predicate agree. That is tautological:
 *   it pins nothing the predicate itself doesn't already state, and any real drift
 *   would live in the DataStore edit mechanics, which a pure-map fake cannot
 *   exercise.
 *
 *   The behaviour that CAN drift — building the keep-list from the injected owners
 *   and removing exactly the un-owned keys via `edit {}` while leaving claimed keys
 *   (exact + prefix) untouched, the read-only `previewOrphanKeys()` dry run
 *   computing the same set while deleting nothing, the empty-keep-list refusal, plus
 *   the fail-soft contract (`Removed(count)` / `Loaded(names)` on success, `Failed`
 *   on an I/O error or empty keep-list, `CancellationException` always propagated) —
 *   is pinned directly against a throwing/healthy `FakeSettingsDataStore` in
 *   `DataStoreMaintenanceRepositoryImplTest` (the impl test). The keep-list's
 *   completeness (the only place a mistake could delete live data) is pinned NOT by
 *   a runtime test but by the `checkConventions` settings-key linter, which proves
 *   every settings-store key declaration is registered by an owner.
 *
 *   So: impl test + linter cover the real risk; a fake contract would be pure
 *   ceremony. This is the same call as `BackupRepository` /
 *   `InstalledAppsRepository` — a repo whose fake would be a tautology.
 */
