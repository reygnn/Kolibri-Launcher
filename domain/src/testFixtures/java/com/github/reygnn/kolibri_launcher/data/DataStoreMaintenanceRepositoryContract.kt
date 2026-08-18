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
 *       suspend fun scanOrphanKeys(): List<String>
 *       suspend fun removeOrphanKeys(): Int
 *   }
 *   ```
 *
 * WHY NO CONTRACT (fake-vs-impl parity test)?
 *
 *   The whole behaviour is "enumerate the settings-store keys and keep/remove
 *   the ones matching `RetiredDataStoreKeys`". A FAKE would have to re-implement
 *   exactly that filter over an in-memory map — so a fake-vs-impl contract would
 *   only assert that two copies of the same one-line predicate agree. That is
 *   tautological: it pins nothing the predicate itself doesn't already state, and
 *   any real drift would live in the DataStore read/edit mechanics, which a
 *   pure-map fake cannot exercise.
 *
 *   The behaviour that CAN drift — reading `DataStore.data.first()`, filtering by
 *   `RetiredDataStoreKeys`, and removing exactly the matched keys via `edit {}`
 *   while leaving live keys untouched — is pinned directly against a `FakeDataStore`
 *   in `DataStoreMaintenanceRepositoryImplTest` (the impl test). The retired-set
 *   itself (the only place a mistake could delete live data) is pinned by
 *   `RetiredDataStoreKeysTest`.
 *
 *   So: impl test + registry test cover the real risk; a fake contract would be
 *   pure ceremony. This is the same call as `BackupRepository` /
 *   `InstalledAppsRepository` — a repo whose fake would be a tautology.
 */
