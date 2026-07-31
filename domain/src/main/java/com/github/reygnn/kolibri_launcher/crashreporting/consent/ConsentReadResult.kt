package com.github.reygnn.kolibri_launcher.crashreporting.consent

/**
 * Outcome of reading the crash-report consent decision.
 *
 * The mirror image of [ConsentWriteResult]: a read that fails must not
 * masquerade as a valid answer. Collapsing an unreadable store into
 * [ConsentDecision.NeverAsked] looks privacy-safe — ACRA off, dialog
 * re-appears — but it is *not* neutral towards the store, because the
 * "never asked" branch of the startup gate ends in a non-cancelable dialog
 * whose every answer is written back. A transient I/O hiccup could therefore
 * escalate into overwriting a decision the user had already made
 * (ACRA_FLOW.md invariant A2, AUDIT-10 #2).
 *
 * Keeping "unreadable" distinguishable from "not consented" lets the gate do
 * the only correct thing for an unknown state: nothing (the `Skip` action).
 * ACRA keeps whatever the bootstrap read established, no dialog is shown, and
 * the next launch reads again.
 *
 * A *present but unknown* token (a stored string that is neither `"GRANTED"`
 * nor `"DENIED"`) is [Unavailable], not [ConsentDecision.NeverAsked]: we do
 * hold a record, we just cannot interpret it — the same lying-read hazard
 * (A2, SR5).
 */
sealed interface ConsentReadResult {

    /** The store was read and interpreted successfully; [decision] is what it holds. */
    data class Loaded(val decision: ConsentDecision) : ConsentReadResult

    /**
     * The stored decision is unknown — either the read threw (I/O) or the
     * stored value could not be interpreted (unknown token, SR5). [cause] is
     * the caught exception (already reported via `silentError` at the failure
     * site) — carried for optional caller-side logging, not for control flow.
     * Not thrown: cancellation still propagates, every other failure surfaces
     * as this value (A6).
     */
    data class Unavailable(val cause: Throwable) : ConsentReadResult
}
