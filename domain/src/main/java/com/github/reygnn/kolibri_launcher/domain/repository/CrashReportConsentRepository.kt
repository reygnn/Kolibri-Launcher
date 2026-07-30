package com.github.reygnn.kolibri_launcher.domain.repository

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
     * Records the user's consent choice and marks the dialog as asked.
     * A single write updates both the consent and the "has asked" flag.
     */
    suspend fun setConsent(consent: Boolean)
}
