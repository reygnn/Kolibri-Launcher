package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.github.reygnn.kolibri_launcher.di.consentDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Low-level DataStore accessor for ACRA crash-report consent.
 *
 * Storage: a dedicated Jetpack DataStore Preferences file
 * ([consentDataStore], `acra_consent`) — deliberately SEPARATE from the
 * project-wide `settingsDataStore`. The settings store is included in
 * Android Auto Backup so user config travels to a new device; the
 * consent flag must NOT travel (privacy-by-default), and backup rules
 * exclude at file granularity, hence its own file. See
 * `AppConstants.CONSENT_DATASTORE_NAME`.
 *
 * ## Why this object still exists next to [com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository]
 *
 * The interactive (post-Hilt) consent path — the dialog and the settings
 * toggle — goes through the Hilt-injected `CrashReportConsentRepository`.
 * This object is kept for the two call sites that CANNOT use Hilt:
 *   - [hasConsent] is read synchronously from
 *     `KolibriLauncherApp.attachBaseContext`, BEFORE Hilt (and ACRA) are
 *     initialised, to decide whether ACRA starts enabled.
 *   - [saveConsent] is the seed helper the instrumented (`androidTest`)
 *     suite uses to pre-answer the dialog (consent = false) so it does not
 *     pop during UI tests.
 * Both share the [HAS_CONSENT_KEY] / [HAS_ASKED_KEY] definitions with the
 * repository (single source of truth) and hit the same `consentDataStore`
 * file, so the pre-Hilt read and the repository writes always agree.
 *
 * Plain `Timber.e` is intentional here per CLAUDE.md Rule 9: [hasConsent]
 * is called synchronously from `KolibriLauncherApp.attachBaseContext`
 * (before Hilt and ACRA are initialised). A `silentError` throw at that
 * bootstrap point would crash the app before the crash reporter is wired,
 * hiding rather than surfacing the bug. (The repository, which runs
 * post-Hilt, uses `silentError` instead.)
 */
object CrashReportConsentStore {

    /** Whether the user has consented to ACRA crash reporting. */
    val HAS_CONSENT_KEY = booleanPreferencesKey("acra_has_consent")

    /** Whether the consent dialog has been shown at least once already. */
    val HAS_ASKED_KEY = booleanPreferencesKey("acra_has_asked")

    /**
     * Pre-Hilt bootstrap read: whether the user has consented. Called from
     * `KolibriLauncherApp.attachBaseContext` to decide ACRA's initial state.
     */
    suspend fun hasConsent(context: Context): Boolean {
        return try {
            context.consentDataStore.data.first()[HAS_CONSENT_KEY] ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to read consent status from DataStore.")
            false
        }
    }

    /**
     * Saves the user's consent choice plus the "has asked" flag.
     *
     * Production writes go through
     * [com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository];
     * this remains only as the `androidTest` seed helper (pre-answering the
     * dialog before a UI test), which has no Hilt graph to inject the
     * repository from.
     */
    suspend fun saveConsent(context: Context, consent: Boolean) {
        try {
            context.consentDataStore.edit { prefs ->
                prefs[HAS_CONSENT_KEY] = consent
                prefs[HAS_ASKED_KEY] = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to save consent choice to DataStore.")
        }
    }
}
