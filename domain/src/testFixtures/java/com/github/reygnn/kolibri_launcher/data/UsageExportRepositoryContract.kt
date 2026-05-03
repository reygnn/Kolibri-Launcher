package com.github.reygnn.kolibri_launcher.data

/**
 * ============================================================================
 * USAGE EXPORT REPOSITORY — NO CONTRACT TEST (ADR)
 * ============================================================================
 *
 * This file contains no executable code. It documents an intentional gap in
 * the contract-test suite.
 *
 * THE INTERFACE:
 * ```
 * interface UsageExportRepository {
 *     suspend fun exportToJson(): String
 *     suspend fun importFromJson(jsonString: String, mergeWithExisting: Boolean = true): UsageImportResult
 *     suspend fun saveToFile(uriString: String): Boolean
 *     suspend fun loadFromFile(uriString: String, mergeWithExisting: Boolean = true): UsageImportResult
 * }
 * ```
 * Four suspend methods: two pure JSON transforms, two file-I/O wrappers
 * around `Uri` + `ContentResolver`.
 *
 * WHY NO CONTRACT?
 *
 * 1. **The file methods are not honestly testable as a fake.**
 *    `saveToFile` and `loadFromFile` use the platform `Uri` +
 *    `ContentResolver` pair. Same rationale as [BackupRepositoryContract]:
 *    a fake that holds bytes in an in-memory map proves nothing about the
 *    URI-resolution and document-tree code paths the real impl exercises.
 *
 * 2. **The JSON methods are testable, but the contract surface is too thin
 *    to be useful.**
 *    `exportToJson` produces an opaque JSON blob; `importFromJson` consumes
 *    one. A fake-only contract pinning "round-trip works" would be
 *    tautological for a fake that round-trips by definition. The real
 *    value is in the *format* (ISO 8601 timestamps, version field, merge
 *    rules, timestamp validation, OOM caps), and that lives entirely in
 *    the impl — a fake that replicates it would just re-implement the
 *    impl.
 *
 * 3. **The impl logic IS pinned, in a deliberate split.**
 *    [com.github.reygnn.kolibri_launcher.data.UsageExportRepositoryImpl] is
 *    covered by five files:
 *      - [UsageExportRepositoryImplTest] — main path
 *      - [UsageExportRepositoryImplFormatSpec] — JSON shape and ISO 8601
 *        round-trip
 *      - [UsageExportRepositoryImplTimeLordSpec] — timestamp validation,
 *        time-zone handling, Y2K38 edge cases
 *      - [UsageExportRepositoryImplDoomsdaySpec] — file-I/O failure modes
 *        for `saveToFile`/`loadFromFile` (Uri/ContentResolver paths,
 *        DoS-sized files, permission-denied, broken pipes); needs
 *        Robolectric for real `Uri` parsing
 *      - [UsageExportRepositoryImplXenomorphSpec] — adversarial JSON
 *        input: malformed structure, type confusion, parser stress
 *
 * 4. **No `FakeUsageExportRepository` exists.**
 *    The repository is consumed only by `UsageExportViewModel`, whose
 *    tests stub the interface where needed. Nothing else depends on
 *    a faked-out export.
 *
 * WHEN TO REVISIT:
 *   - If a second implementation is added (e.g., a remote-sync or
 *     cloud-backup variant).
 *   - If a fake is added for ViewModel tests with non-trivial state, and
 *     the export format starts mattering across multiple consumers (e.g.,
 *     if the same JSON is also produced by the backup pipeline).
 *
 * SEE ALSO:
 *   - [BackupRepositoryContract] — comparable Uri/ContentResolver
 *     rationale.
 *   - [TimeBasedEventsRepositoryContract] — the prior ADR-only template.
 *   - The four spec files above for the actual format and edge-case
 *     tests.
 * ============================================================================
 */
@Suppress("unused")
private val ARCHITECTURAL_DECISION_RECORD_ONLY = Unit
