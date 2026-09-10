package com.github.reygnn.kolibri_launcher.crashreporting.consent

/**
 * The crash-report consent fact — exactly one tri-state value.
 *
 * This is the single source of truth for "may ACRA report?", persisted as one
 * string key (`consent_decision`) and mirrored at runtime by ACRA's volatile
 * `enabled` flag. It replaces the earlier two-boolean model
 * (`hasConsent` + `hasAsked`), whose four combinations included one that is
 * illegal by construction (`asked = false, consent = true`).
 *
 * Modelling the fact as a sealed tri-state makes that illegal state
 * *unrepresentable* — there is no field pair that can drift out of sync, so no
 * test can forget it (ACRA_FLOW.md invariant A8, §3.1). [NeverAsked] *is* the
 * "not asked yet" state; there is no separate `hasAsked = false`.
 *
 * Persistence maps this directly (ACRA_SPEC.md A.2):
 *  - key absent  → [NeverAsked]  (absence *is* the default — no sentinel)
 *  - `"GRANTED"` → [Granted]
 *  - `"DENIED"`  → [Denied]
 *  - any other stored string is *not* a decision: it is an unknown token that
 *    surfaces as [ConsentReadResult.Unavailable], never silently as
 *    [NeverAsked] (A2, SR5).
 *
 * Pure Kotlin: no Android imports, no Parcelable — `:domain` is a JVM module.
 */
sealed interface ConsentDecision {

    /** The consent dialog has never been answered. Default; backing key absent. */
    data object NeverAsked : ConsentDecision

    /** The user granted crash reporting. Only this value enables ACRA. */
    data object Granted : ConsentDecision

    /** The user declined crash reporting. */
    data object Denied : ConsentDecision
}
