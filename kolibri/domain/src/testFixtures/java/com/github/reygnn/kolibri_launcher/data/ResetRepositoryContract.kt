package com.github.reygnn.kolibri_launcher.data

/**
 * ============================================================================
 * RESET REPOSITORY — NO CONTRACT TEST (ADR)
 * ============================================================================
 *
 * This file contains no executable code. It documents an intentional gap in
 * the contract-test suite.
 *
 * THE INTERFACE:
 * ```
 * interface ResetRepository {
 *     suspend fun resetUserData(): Boolean
 *     suspend fun resetSettings(): Boolean
 *     suspend fun resetAppUsageData(): Boolean
 * }
 * ```
 * Three suspend methods, each returning `Boolean`.
 *
 * WHY NO CONTRACT?
 *
 * 1. **It is a composition repository, not a data repository.**
 *    [com.github.reygnn.kolibri_launcher.data.ResetRepositoryImpl] holds no
 *    state. Its behavior is entirely "iterate over 12 child repositories,
 *    call `Purgeable.purgeRepository()` on each, aggregate the success
 *    flag." There is no interface invariant to pin — `resetUserData`
 *    returning `true` only tells the caller "no purge threw"; `false` only
 *    tells the caller "at least one purge threw." A contract test would
 *    verify those tautologies against a fake that simply returns `true`,
 *    which proves nothing about the impl's actual coordination.
 *
 *    This matches the framing for `Purgeable` itself — surface too thin
 *    for contract testing.
 *
 * 2. **The actual coordination IS the value, and it IS tested.**
 *    The impl's coordination logic — which child gets purged in which
 *    reset path, the per-child error isolation (each child wrapped in its
 *    own try/catch so one failure does not abort the rest), and the
 *    deliberate skip of `appUsageRepository` from `resetUserData` (it is
 *    purged separately by `resetAppUsageData`, so it survives a
 *    user-data-only reset) — is pinned by [ResetRepositoryImplTest] using
 *    MockK on each child interface.
 *
 * 3. **No `FakeResetRepository` exists.**
 *    No consumer needs one. `FactoryResetUseCase` calls the granular
 *    `resetSettings` / `resetUserData` / `resetAppUsageData` methods
 *    directly; its tests mock the repository where needed. Adding a fake
 *    purely to enable a contract test would be ceremony.
 *
 * WHEN TO REVISIT:
 *   - If the interface grows to expose observable state (e.g., a reset
 *     progress flow or a "last-reset timestamp" property) that multiple
 *     consumers depend on, the surface becomes worth contracting.
 *   - If a second implementation is added (highly unlikely for an
 *     orchestrator).
 *
 * SEE ALSO:
 *   - [ResetRepositoryImplTest] — coordination tests via mocked children.
 *   - [TimeBasedEventsRepositoryContract] — the prior ADR-only template
 *     (different rationale: system-API; same outcome: no contract).
 * ============================================================================
 */
@Suppress("unused")
private val ARCHITECTURAL_DECISION_RECORD_ONLY = Unit
