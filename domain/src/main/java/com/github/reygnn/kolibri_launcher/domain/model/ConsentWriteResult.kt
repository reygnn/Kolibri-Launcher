package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Outcome of persisting a crash-report consent choice.
 *
 * Consent persistence is deliberately best-effort: a failed DataStore write
 * must never crash the app or block the UI (the caller applies the decision
 * to ACRA in-memory synchronously and does not await the write). But the
 * failure must not be *invisible* either — swallowing it silently is what
 * AUDIT-10 #11 flagged, because the in-memory ACRA state and the persisted
 * state then diverge with no signal.
 *
 * So the write reports its outcome instead of returning `Unit`: callers can
 * log/telemeter a [Failed] without having to await or handle it as an error.
 * A failed write persists nothing, so it can never pin a choice the user did
 * not make.
 *
 * It does NOT follow that the choice is always re-asked. That only holds for
 * a first-ever decision on a readable store, where the still-unset `hasAsked`
 * makes the startup gate show the dialog again. Two cases where it does not:
 * a decision changed later (Settings), where `hasAsked` is already `true` and
 * the gate re-affirms the OLD stored consent instead of asking; and a store
 * that stays unreadable, where the gate skips entirely (see
 * [ConsentReadResult]). In both, the failed write is simply lost and the
 * previous stored state wins — which is why [Failed] must be surfaced to the
 * user rather than only logged.
 */
sealed interface ConsentWriteResult {

    /** The choice was persisted (both consent and the "asked" flag). */
    data object Saved : ConsentWriteResult

    /**
     * The DataStore write failed and nothing was persisted. [cause] is the
     * caught exception (already reported via `silentError` at the failure
     * site) — carried here for optional caller-side logging, not for control
     * flow. Not thrown: cancellation still propagates, every other failure
     * surfaces as this value.
     */
    data class Failed(val cause: Throwable) : ConsentWriteResult
}
