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
 *       suspend fun removeOrphanKeys(): Result   // Removed(count) | Failed
 *   }
 *   ```
 *
 * WHY NO CONTRACT (fake-vs-impl parity test)?
 *
 *   The whole behaviour is "remove the settings-store keys matching
 *   `RetiredDataStoreKeys`". A FAKE would have to re-implement exactly that filter
 *   over an in-memory map — so a fake-vs-impl contract would only assert that two
 *   copies of the same one-line predicate agree. That is tautological: it pins
 *   nothing the predicate itself doesn't already state, and any real drift would
 *   live in the DataStore edit mechanics, which a pure-map fake cannot exercise.
 *
 *   The behaviour that CAN drift — filtering by `RetiredDataStoreKeys` and removing
 *   exactly the matched keys via `edit {}` while leaving live keys untouched, plus
 *   the fail-soft contract (`Removed(count)` on success, `Failed` on an I/O error,
 *   `CancellationException` always propagated) — is pinned directly against a
 *   throwing/healthy `FakeSettingsDataStore` in `DataStoreMaintenanceRepositoryImplTest`
 *   (the impl test). The retired-set itself (the only place a mistake could delete
 *   live data) is pinned by `RetiredDataStoreKeysTest`.
 *
 *   So: impl test + registry test cover the real risk; a fake contract would be
 *   pure ceremony. This is the same call as `BackupRepository` /
 *   `InstalledAppsRepository` — a repo whose fake would be a tautology.
 */
