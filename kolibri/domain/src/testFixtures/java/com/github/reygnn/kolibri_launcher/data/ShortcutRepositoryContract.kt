package com.github.reygnn.kolibri_launcher.data

/**
 * ============================================================================
 * SHORTCUT REPOSITORY — NO CONTRACT TEST (ADR)
 * ============================================================================
 *
 * This file contains no executable code. It documents an intentional gap in
 * the contract-test suite, in the same shape as
 * [TimeBasedEventsRepositoryContract].
 *
 * THE INTERFACE:
 * ```
 * interface ShortcutRepository : Purgeable {
 *     fun getShortcutsForPackage(packageName: String): List<ShortcutInfo>
 * }
 * ```
 * One real method plus the Purgeable no-op.
 *
 * WHY NO CONTRACT?
 *
 * 1. **No honest impl-side contract test possible.**
 *    [com.github.reygnn.kolibri_launcher.data.ShortcutRepositoryImpl]
 *    requires:
 *      - `Context` (for `Context.LAUNCHER_APPS_SERVICE` and `PackageManager`)
 *      - The Android-managed `LauncherApps` system service
 *      - A default-launcher status check via `PackageManager.resolveActivity`
 *    None of that is honestly instantiable in a JVM unit test. The same
 *    rationale applies as for [BackupRepositoryContract],
 *    [InstalledAppsRepositoryContract], and [TimeBasedEventsRepositoryContract]:
 *    a heavily-mocked impl-side contract would only verify conformance
 *    between the fake and a mock construct, not between the fake and the
 *    real impl.
 *
 * 2. **A fake-only contract would be tautological today.**
 *    There is currently no `FakeShortcutRepository`. The repository is
 *    consumed by the app-context-menu code path, which only runs on a
 *    device that has a `LauncherApps` service. Writing a fake purely to
 *    test it against itself proves nothing.
 *
 * 3. **The real logic is in the impl, and it IS tested.**
 *    [ShortcutRepositoryImpl] handles: the blank-package guard, the
 *    default-launcher status check, four catch arms (`SecurityException`,
 *    `IllegalStateException`, `IllegalArgumentException`, generic
 *    `Throwable`), and the no-op `purgeRepository` (system data, not user
 *    data — same shape as
 *    [com.github.reygnn.kolibri_launcher.data.InstalledAppsStateRepositoryImpl.purgeRepository]).
 *    This logic is pinned by [ShortcutRepositoryImplTest].
 *
 * WHEN TO REVISIT:
 *   - If a `FakeShortcutRepository` is added (e.g., for an Activity-test
 *     scenario), introduce a fake-only contract — same shape as
 *     [BackupRepositoryContract].
 *   - If the interface grows to surface state observed by multiple
 *     consumers (e.g., a flow that emits when shortcuts change), the
 *     surface becomes worth contracting.
 *
 * SEE ALSO:
 *   - [BackupRepositoryContract] — comparable system-API rationale.
 *   - [InstalledAppsRepositoryContract] — the dual-fake pattern.
 *   - [TimeBasedEventsRepositoryContract] — the prior ADR-only template.
 *   - [ShortcutRepositoryImplTest] — the actual logic tests via MockK.
 * ============================================================================
 */
@Suppress("unused")
private val ARCHITECTURAL_DECISION_RECORD_ONLY = Unit
