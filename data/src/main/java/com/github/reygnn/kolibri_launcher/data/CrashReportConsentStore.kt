package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.github.reygnn.kolibri_launcher.di.consentDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * DataStore-backed persistence for ACRA crash-report consent state.
 *
 * Storage: a dedicated Jetpack DataStore Preferences file
 * ([consentDataStore], `acra_consent`) — deliberately SEPARATE from the
 * project-wide `settingsDataStore`. The settings store is included in
 * Android Auto Backup so user config travels to a new device; the
 * consent flag must NOT travel (privacy-by-default), and backup rules
 * exclude at file granularity, hence its own file. See
 * `AppConstants.CONSENT_DATASTORE_NAME`.
 *
 * The consent lived in `settingsDataStore` until the AUDIT-4 auto-backup
 * fix split it out (and, further back, in a legacy SP file `acra_consent`
 * migrated V1→V2 via the now-removed `DataMigrationManager`). There is
 * intentionally NO migration
 * from the old settings-store keys: on the first launch after the split
 * an existing install's consent resets once (`hasAsked` → false → dialog
 * re-appears, `hasConsent` → false → ACRA stays off until re-consent).
 * That is the privacy-safe default and avoids re-introducing migration
 * machinery on this bootstrap path.
 *
 * Plain `Timber.e` is intentional here per CLAUDE.md Rule 9: this
 * store's read methods are called synchronously from
 * `KolibriLauncherApp.attachBaseContext` (before Hilt and ACRA are
 * initialised). A `silentError` throw at that bootstrap point would
 * crash the app before the crash reporter is wired, hiding rather
 * than surfacing the bug.
 */
object CrashReportConsentStore {

    /** Whether the user has consented to ACRA crash reporting. */
    val HAS_CONSENT_KEY = booleanPreferencesKey("acra_has_consent")

    /** Whether the consent dialog has been shown at least once already. */
    val HAS_ASKED_KEY = booleanPreferencesKey("acra_has_asked")

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

    suspend fun hasAsked(context: Context): Boolean {
        return try {
            context.consentDataStore.data.first()[HAS_ASKED_KEY] ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to read 'has_asked' status from DataStore.")
            false
        }
    }

    suspend fun setConsent(context: Context, consent: Boolean) {
        saveConsent(context, consent)
    }

    /**
     * Resets the consent, forcing the dialog to be shown on the next app start.
     */
    suspend fun resetConsent(context: Context) {
        try {
            context.consentDataStore.edit { prefs ->
                prefs.remove(HAS_CONSENT_KEY)
                prefs.remove(HAS_ASKED_KEY)
            }
            Timber.i("Crash report consent has been reset.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to reset consent.")
        }
    }

    /**
     * Saves the user's consent choice plus the "has asked" flag.
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
