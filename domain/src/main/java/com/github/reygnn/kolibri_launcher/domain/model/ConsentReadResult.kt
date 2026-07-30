package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Outcome of reading the crash-report consent state.
 *
 * The mirror image of [ConsentWriteResult]: a read that fails must not
 * masquerade as a valid answer. Collapsing an unreadable store into the
 * all-false state looks privacy-safe — ACRA off, dialog re-appears — but it
 * is *not* neutral towards the store, because the "not asked yet" branch of
 * the startup gate ends in a non-cancelable dialog whose every answer is
 * written back. A transient I/O hiccup could therefore escalate into
 * overwriting a decision the user had already made (AUDIT-10 #2).
 *
 * Keeping "unreadable" distinguishable from "not consented" lets the gate do
 * the only correct thing for an unknown state: nothing. ACRA keeps whatever
 * the bootstrap read established, no dialog is shown, and the next launch
 * reads again.
 */
sealed interface ConsentReadResult {

    /** The store was read successfully; [state] is what it holds. */
    data class Loaded(val state: CrashReportConsentState) : ConsentReadResult

    /**
     * The read failed and the stored state is unknown. [cause] is the caught
     * exception (already reported via `silentError` at the failure site) —
     * carried for optional caller-side logging, not for control flow. Not
     * thrown: cancellation still propagates, every other failure surfaces as
     * this value.
     */
    data class Unavailable(val cause: Throwable) : ConsentReadResult
}
