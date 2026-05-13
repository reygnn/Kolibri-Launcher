package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.github.reygnn.kolibri_launcher.di.settingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * DataStore-backed persistence for ACRA crash-report consent state.
 *
 * Storage: Jetpack DataStore Preferences (the project-wide
 * [settingsDataStore]). Historically migrated from a legacy SP file
 * `acra_consent` via `DataMigrationManager` V1→V2 (TODO §7); both the
 * manager and the legacy file have been removed once every existing
 * install moved past V2. Today the keys are simply the DataStore
 * source of truth.
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
            context.settingsDataStore.data.first()[HAS_CONSENT_KEY] ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to read consent status from DataStore.")
            false
        }
    }

    suspend fun hasAsked(context: Context): Boolean {
        return try {
            context.settingsDataStore.data.first()[HAS_ASKED_KEY] ?: false
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
            context.settingsDataStore.edit { prefs ->
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
            context.settingsDataStore.edit { prefs ->
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
