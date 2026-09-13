package com.github.reygnn.kolibri_launcher.data

/**
 * ============================================================================
 * DEFAULT-APPS REPOSITORY — NO CONTRACT TEST (ADR)
 * ============================================================================
 *
 * This file contains no executable code. It documents an intentional gap in the
 * contract-test suite, in the same shape as [ShortcutRepositoryContract] and
 * [TimeBasedEventsRepositoryContract].
 *
 * THE INTERFACE:
 * ```
 * interface DefaultAppsRepository {
 *     suspend fun getDefaultAppPackages(): List<String>
 * }
 * ```
 * One read-only method; no stored state, so not [Purgeable].
 *
 * WHY NO CONTRACT?
 *
 * 1. **No honest impl-side contract test possible.**
 *    [com.github.reygnn.kolibri_launcher.data.DefaultAppsRepositoryImpl]
 *    resolves the user's real defaults through system APIs that are not honestly
 *    JVM-instantiable:
 *      - `TelecomManager.getDefaultDialerPackage()`
 *      - `Telephony.Sms.getDefaultSmsPackage(context)` (a framework static)
 *      - `PackageManager.resolveActivity(...)` for the email (`mailto:`), browser and
 *        camera intents
 *    A heavily-mocked impl-side contract would only verify the fake against a mock
 *    construct, not against the real device behaviour. Same rationale as
 *    [ShortcutRepositoryContract], [InstalledAppsRepositoryContract] and
 *    [TimeBasedEventsRepositoryContract].
 *
 * 2. **A fake-only contract would be tautological today.**
 *    There is no `FakeDefaultAppsRepository`: the interface's single consumer is
 *    the first-run onboarding pre-selection, and the *pure* part of that flow —
 *    package → launcher-component mapping, order, de-duplication, skip-if-absent —
 *    lives in [com.github.reygnn.kolibri_launcher.domain.usecase.GetDefaultFavoriteComponentsUseCase]
 *    and is pinned by `GetDefaultFavoriteComponentsUseCaseTest` (a plain in-test
 *    fake stands in for this interface there). Writing a second fake purely to
 *    test it against itself would prove nothing.
 *
 * The impl's own logic is thin glue over the system resolvers (call four APIs,
 * drop null / blank / the resolver package / our own package, de-dup). It is a
 * device-truth path — verified on device, not on the JVM.
 */
