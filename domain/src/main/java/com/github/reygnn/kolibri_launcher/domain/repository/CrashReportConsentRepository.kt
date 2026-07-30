package com.github.reygnn.kolibri_launcher.domain.repository

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
 * flag is best-effort. This is a deliberate contract, pinned by
 * `CrashReportConsentRepositoryContract` and the impl sad-path tests — it is
 * not an unguarded swallow, and should not be re-flagged as one.
 *
 *  - **Writes are best-effort and report their outcome.** [setConsent]
 *    returns a [ConsentWriteResult] instead of `Unit`: on failure it persists
 *    nothing, reports via `silentError`, and returns [ConsentWriteResult.Failed]
 *    rather than throwing — so a caller that already switched ACRA in-memory
 *    can log the divergence instead of silently assuming success. The unset
 *    `hasAsked` self-heals via re-ask next launch. See AUDIT-10 #11.
 *
 *  Cancellation is not a failure: [kotlinx.coroutines.CancellationException]
 *  propagates on every path and is never collapsed into a [ConsentWriteResult.Failed].
 */
interface CrashReportConsentRepository {

    /** Whether the user has consented to ACRA crash reporting. */
    suspend fun hasConsent(): Boolean

    /** Whether the consent dialog has been shown at least once already. */
    suspend fun hasAsked(): Boolean

    /**
     * Reads both flags in a single pass and returns them as a
     * [CrashReportConsentState]. Prefer this over separate [hasConsent] +
     * [hasAsked] calls on hot paths (launcher startup), where it avoids a
     * second read of the same underlying store (AUDIT-10 #4).
     */
    suspend fun readState(): CrashReportConsentState

    /**
     * Records the user's consent choice and marks the dialog as asked in a
     * single write. Best-effort: returns [ConsentWriteResult.Saved] on
     * success or [ConsentWriteResult.Failed] if the underlying write threw —
     * it does not throw for an I/O failure (cancellation still propagates).
     * See AUDIT-10 #11 and the class KDoc failure model.
     */
    suspend fun setConsent(consent: Boolean): ConsentWriteResult
}
