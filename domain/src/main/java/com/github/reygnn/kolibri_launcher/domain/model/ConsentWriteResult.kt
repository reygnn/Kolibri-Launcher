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
 * The privacy-safe self-heal is intrinsic — a failed write leaves `hasAsked`
 * unset, so the dialog simply re-appears on the next launch rather than
 * pinning a choice the store never recorded.
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
