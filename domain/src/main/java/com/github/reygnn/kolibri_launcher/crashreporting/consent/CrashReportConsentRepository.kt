package com.github.reygnn.kolibri_launcher.crashreporting.consent

/**
 * Persistence contract for the ACRA crash-report consent decision.
 *
 * The decision is a single tri-state [ConsentDecision], persisted as one
 * string key (`consent_decision`) in its own DataStore file (excluded from
 * Auto Backup). This replaces the earlier two-boolean backing, eliminating the
 * illegal `asked = false, consent = true` combination by construction
 * (ACRA_FLOW.md invariant A8, §3.1).
 *
 * Deliberately NOT `Purgeable`: a factory reset must not silently flip the
 * privacy state (A5). Consent is re-asked on a fresh install rather than
 * purged with the rest of the user state.
 *
 * Note: the pre-Hilt bootstrap read in `attachBaseContext` goes through the
 * low-level `ConsentBootstrap` directly (it runs before Hilt can inject this
 * repository). Both hit the same underlying `consentDataStore` file, so they
 * stay consistent — one file, one key, two readers (§3.3).
 *
 * ## Failure model (best-effort telemetry consent — by design, contract-tested)
 *
 * This is opt-in crash telemetry, not user data: correctness of the *stored*
 * decision is best-effort, and I/O failures are never propagated as
 * exceptions. They are not swallowed either — both directions report their
 * outcome as a value, so a caller can tell a real answer from a failure. This
 * is a deliberate contract, pinned by `CrashReportConsentRepositoryContract`
 * (success shapes) and the impl sad-path tests (failure branches); it should
 * not be re-flagged as an unguarded swallow.
 *
 *  - **The decision-driving read reports its outcome.** [readState] returns a
 *    [ConsentReadResult]: [ConsentReadResult.Loaded] with the stored
 *    [ConsentDecision], or [ConsentReadResult.Unavailable] if the read threw
 *    or the stored token could not be interpreted. This matters because the
 *    [ConsentDecision.NeverAsked] branch of the startup gate ends in a
 *    non-cancelable dialog that writes back whatever the user taps — collapsing
 *    an unreadable store into "never asked" would let a transient I/O failure
 *    overwrite a decision the user already made. An unknown state instead makes
 *    the gate do nothing (A2, SR2/SR5, AUDIT-10 #2).
 *  - **Writes are best-effort and report their outcome.** [setConsent] returns
 *    a [ConsentWriteResult] instead of `Unit`: on failure it persists nothing,
 *    reports via `silentError`, and returns [ConsentWriteResult.Failed] rather
 *    than throwing — so a caller that already switched ACRA in-memory can
 *    surface the divergence (a toast) instead of silently assuming success
 *    (A4, AUDIT-10 #11).
 *  - **The two plain getters stay total.** [hasConsent] / [hasAsked] fall back
 *    to `false` on any non-loaded read. They only feed display and are never
 *    followed by a write, so an unknown state cannot escalate there — the
 *    tri-state is deliberately confined to [readState] (SR4).
 *
 *  Cancellation is not a failure: [kotlinx.coroutines.CancellationException]
 *  propagates on every path and is never collapsed into an
 *  [ConsentReadResult.Unavailable] / [ConsentWriteResult.Failed] (A6).
 */
interface CrashReportConsentRepository {

    /**
     * Reads the stored decision in a single pass.
     *
     * Returns [ConsentReadResult.Loaded] with the stored [ConsentDecision], or
     * [ConsentReadResult.Unavailable] if the read failed or the stored token
     * was unknown — an unreadable/uninterpretable store must stay
     * distinguishable from a stored [ConsentDecision.NeverAsked] (A2, SR5).
     */
    suspend fun readState(): ConsentReadResult

    /**
     * Records the user's consent choice: `true` persists
     * [ConsentDecision.Granted], `false` persists [ConsentDecision.Denied].
     * Best-effort: returns [ConsentWriteResult.Saved] on success or
     * [ConsentWriteResult.Failed] if the underlying write threw — it does not
     * throw for an I/O failure (cancellation still propagates). See A4.
     */
    suspend fun setConsent(granted: Boolean): ConsentWriteResult

    /**
     * Whether the user has consented to ACRA crash reporting — i.e. the stored
     * decision is [ConsentDecision.Granted]. Display-only and total: any
     * non-loaded read yields `false` (see the class KDoc failure model).
     */
    suspend fun hasConsent(): Boolean

    /**
     * Whether the consent dialog has been answered at least once — i.e. the
     * stored decision is not [ConsentDecision.NeverAsked]. Display-only and
     * total: any non-loaded read yields `false` (see the class KDoc failure
     * model).
     */
    suspend fun hasAsked(): Boolean
}
