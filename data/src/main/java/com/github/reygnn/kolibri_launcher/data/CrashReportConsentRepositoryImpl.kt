package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.di.ConsentDataStore
import com.github.reygnn.kolibri_launcher.domain.model.CrashReportConsentState
import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed [CrashReportConsentRepository] for the interactive
 * (post-Hilt) path: the consent dialog and the settings toggle read/write
 * through here instead of touching [CrashReportConsentStore] directly.
 *
 * Shares the key definitions with [CrashReportConsentStore] (single source
 * of truth for the preference key names) and writes to the same
 * `consentDataStore` file the bootstrap reader uses — so the pre-Hilt read
 * in `KolibriLauncherApp.attachBaseContext` and this repository always
 * agree.
 *
 * Unlike [CrashReportConsentStore] (which is on the pre-ACRA bootstrap
 * path and therefore uses plain `Timber.e` per Rule 9), this impl runs
 * well after ACRA/Timber are wired, so it uses [TimberWrapper.silentError]
 * — read/write failures are logged and, in DEBUG, loud.
 */
@Singleton
class CrashReportConsentRepositoryImpl @Inject constructor(
    @param:ConsentDataStore private val dataStore: DataStore<Preferences>,
) : CrashReportConsentRepository {

    override suspend fun hasConsent(): Boolean = read(CrashReportConsentStore.HAS_CONSENT_KEY, "consent")

    override suspend fun hasAsked(): Boolean = read(CrashReportConsentStore.HAS_ASKED_KEY, "has-asked")

    override suspend fun readState(): CrashReportConsentState {
        return try {
            val prefs = dataStore.data.first()
            CrashReportConsentState(
                hasConsent = prefs[CrashReportConsentStore.HAS_CONSENT_KEY] ?: false,
                hasAsked = prefs[CrashReportConsentStore.HAS_ASKED_KEY] ?: false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Failed to read crash-report consent state from DataStore")
            CrashReportConsentState(hasConsent = false, hasAsked = false)
        }
    }

    private suspend fun read(key: Preferences.Key<Boolean>, label: String): Boolean {
        return try {
            dataStore.data.first()[key] ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Failed to read crash-report $label from DataStore")
            false
        }
    }

    override suspend fun setConsent(consent: Boolean) {
        try {
            dataStore.edit { prefs ->
                prefs[CrashReportConsentStore.HAS_CONSENT_KEY] = consent
                prefs[CrashReportConsentStore.HAS_ASKED_KEY] = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Failed to save crash-report consent to DataStore")
        }
    }
}
