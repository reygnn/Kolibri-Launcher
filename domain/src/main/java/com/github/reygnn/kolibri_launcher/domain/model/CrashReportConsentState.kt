package com.github.reygnn.kolibri_launcher.domain.model

/**
 * A single-read snapshot of the ACRA crash-report consent state.
 *
 * Bundles the two flags that back the flow — whether the user has consented
 * ([hasConsent]) and whether the dialog has already been shown once
 * ([hasAsked]) — so the launcher startup gate can decide "show dialog vs.
 * re-affirm" from one DataStore read instead of two separate collections of
 * the same file (AUDIT-10 #4).
 */
data class CrashReportConsentState(
    val hasConsent: Boolean,
    val hasAsked: Boolean,
)
