package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.ConsentReadResult
import com.github.reygnn.kolibri_launcher.domain.model.ConsentWriteResult
import com.github.reygnn.kolibri_launcher.domain.model.CrashReportConsentState

/**
 * Persistence contract for the ACRA crash-report consent state.
 *
 * Two independent flags back this: whether the user has consented
 * ([hasConsent]) and whether the consent dialog has already been shown
 * once ([hasAsked]). [setConsent] records the user's choice AND marks the
 * dialog as asked in a single write.
 *
 * Deliberately NOT [Purgeable]: a factory reset must not silently flip the
 * privacy state. The consent lives in its own DataStore file (excluded from
 * Auto Backup) and is re-asked on a fresh install rather than purged with
 * the rest of the user state.
 *
 * Note: the pre-Hilt bootstrap read in
 * `KolibriLauncherApp.attachBaseContext` still goes through the low-level
 * `CrashReportConsentStore` directly (it runs before Hilt can inject this
 * repository). Both hit the same underlying `consentDataStore` file, so
 * they stay consistent.
 *
 * ## Failure model (best-effort telemetry consent — by design, contract-tested)
 *
 * This is opt-in crash telemetry, not user data: correctness of the *stored*
 * flag is best-effort, and I/O failures are never propagated as exceptions.
 * They are not swallowed either — both directions report their outcome as a
 * value, so a caller can tell a real answer from a failure. This is a
 * deliberate contract, pinned by `CrashReportConsentRepositoryContract` and
 * the impl sad-path tests; it should not be re-flagged as an unguarded
 * swallow.
 *
 *  - **The decision-driving read reports its outcome.** [readState] returns
 *    a [ConsentReadResult]: [ConsentReadResult.Loaded] with the stored flags,
 *    or [ConsentReadResult.Unavailable] if the read threw. This matters
 *    because the "not asked yet" branch of the startup gate ends in a
 *    non-cancelable dialog that writes back whatever the user taps —
 *    collapsing an unreadable store into "not asked" would let a transient
 *    I/O failure overwrite a decision the user already made. An unknown
 *    state instead makes the gate do nothing. See AUDIT-10 #2.
 *  - **Writes are best-effort and report their outcome.** [setConsent]
 *    returns a [ConsentWriteResult] instead of `Unit`: on failure it persists
 *    nothing, reports via `silentError`, and returns [ConsentWriteResult.Failed]
 *    rather than throwing — so a caller that already switched ACRA in-memory
 *    can report the divergence instead of silently assuming success. Nothing
 *    is persisted, so a failed write can never pin a choice the user did not
 *    make; it is simply lost, and the previously stored state keeps winning.
 *    See AUDIT-10 #11 and the [ConsentWriteResult] KDoc for when that does
 *    and does not lead to a re-ask.
 *  - **The two plain getters stay total.** [hasConsent] / [hasAsked] fall
 *    back to `false` on an I/O failure. They only feed display and are never
 *    followed by a write, so an unknown state cannot escalate there — the
 *    tri-state is deliberately confined to [readState].
 *
 *  Cancellation is not a failure: [kotlinx.coroutines.CancellationException]
 *  propagates on every path and is never collapsed into an
 *  [ConsentReadResult.Unavailable] / [ConsentWriteResult.Failed].
 */
interface CrashReportConsentRepository {

    /**
     * Whether the user has consented to ACRA crash reporting. Total: an I/O
     * failure yields `false` (see the class KDoc failure model).
     */
    suspend fun hasConsent(): Boolean

    /**
     * Whether the consent dialog has been shown at least once already. Total:
     * an I/O failure yields `false` (see the class KDoc failure model).
     */
    suspend fun hasAsked(): Boolean

    /**
     * Reads both flags in a single pass. Prefer this over separate
     * [hasConsent] + [hasAsked] calls on hot paths (launcher startup), where
     * it avoids a second read of the same underlying store (AUDIT-10 #4).
     *
     * Returns [ConsentReadResult.Loaded] with the stored
     * [CrashReportConsentState], or [ConsentReadResult.Unavailable] if the
     * read failed — an unreadable store must stay distinguishable from a
     * stored "not asked yet" (AUDIT-10 #2).
     */
    suspend fun readState(): ConsentReadResult

    /**
     * Records the user's consent choice and marks the dialog as asked in a
     * single write. Best-effort: returns [ConsentWriteResult.Saved] on
     * success or [ConsentWriteResult.Failed] if the underlying write threw —
     * it does not throw for an I/O failure (cancellation still propagates).
     * See AUDIT-10 #11 and the class KDoc failure model.
     */
    suspend fun setConsent(consent: Boolean): ConsentWriteResult
}
