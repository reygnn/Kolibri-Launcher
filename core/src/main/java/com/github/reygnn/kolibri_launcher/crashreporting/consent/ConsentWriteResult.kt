package com.github.reygnn.kolibri_launcher.crashreporting.consent

/**
 * Outcome of persisting a crash-report consent choice.
 *
 * Consent persistence is deliberately best-effort: a failed DataStore write
 * must never crash the app or block the UI (the caller applies the decision to
 * ACRA in-memory synchronously and does not await the write). But the failure
 * must not be *invisible* either — swallowing it silently is what
 * AUDIT-10 #11 flagged, because the in-memory ACRA state and the persisted
 * state then diverge with no signal (ACRA_FLOW.md invariant A4).
 *
 * So the write reports its outcome instead of returning `Unit`: the caller can
 * surface a [Failed] to the user (a toast) instead of assuming a save that
 * never happened. A failed write persists nothing, so it can never pin a
 * choice the user did not make.
 *
 * It does NOT follow that the choice is always re-asked. That only holds for a
 * first-ever decision on a readable store, where the stored
 * [ConsentDecision.NeverAsked] makes the startup gate show the dialog again.
 * Two cases where it does not: a decision changed later (Settings), where the
 * store already holds [ConsentDecision.Granted]/[ConsentDecision.Denied] and
 * the gate re-affirms the OLD stored decision instead of asking; and a store
 * that stays unreadable, where the gate skips entirely (see
 * [ConsentReadResult]). In both, the failed write is simply lost and the
 * previous stored decision wins — which is why [Failed] must be surfaced to
 * the user rather than only logged.
 */
sealed interface ConsentWriteResult {

    /** The choice was persisted. */
    data object Saved : ConsentWriteResult

    /**
     * The DataStore write failed and nothing was persisted. [cause] is the
     * caught exception (already reported via `silentError` at the failure
     * site) — carried here for optional caller-side logging, not for control
     * flow. Not thrown: cancellation still propagates, every other failure
     * surfaces as this value (A6).
     */
    data class Failed(val cause: Throwable) : ConsentWriteResult
}
