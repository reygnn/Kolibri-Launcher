package com.github.reygnn.kolibri_launcher.crashreporting.consent

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.di.ConsentDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed [CrashReportConsentRepository] for the interactive
 * (post-Hilt) path: the consent dialog and the settings toggle read/write
 * through here instead of touching [ConsentBootstrap] directly.
 *
 * Shares the key definition with [ConsentBootstrap] (single source of truth)
 * and writes the same `consentDataStore` file the bootstrap reader uses — so
 * the pre-Hilt R1 read and this repository always agree (§3.3).
 *
 * Unlike [ConsentBootstrap] (on the pre-ACRA bootstrap path, plain `Timber.e`
 * per Rule 9), this impl runs well after ACRA/Timber are wired, so it uses
 * [TimberWrapper.silentError] — failures are logged and, in DEBUG, loud.
 *
 * Failure handling is the deliberate best-effort contract documented on
 * [CrashReportConsentRepository]: [readState] reports a [ConsentReadResult],
 * [setConsent] a [ConsentWriteResult], and neither throws for I/O. The
 * `catch (Exception)` blocks below are that contract — they turn a failure
 * into a value the caller can act on, not into silence (A2 / A4).
 */
@Singleton
class CrashReportConsentRepositoryImpl @Inject constructor(
    @param:ConsentDataStore private val dataStore: DataStore<Preferences>,
) : CrashReportConsentRepository {

    override suspend fun readState(): ConsentReadResult {
        return try {
            when (val token = dataStore.data.first()[ConsentBootstrap.CONSENT_DECISION_KEY]) {
                null -> ConsentReadResult.Loaded(ConsentDecision.NeverAsked)
                ConsentBootstrap.VALUE_GRANTED -> ConsentReadResult.Loaded(ConsentDecision.Granted)
                ConsentBootstrap.VALUE_DENIED -> ConsentReadResult.Loaded(ConsentDecision.Denied)
                else -> {
                    // A present but uninterpretable token is NOT "never asked":
                    // we hold a record we cannot read — the A2 lying-read hazard.
                    // Report it (loud in DEBUG) and surface Unavailable so the
                    // gate skips rather than showing a dialog that overwrites it
                    // (SR5).
                    val e = IllegalStateException("Unknown crash-report consent token: $token")
                    TimberWrapper.silentError(e, "Unknown crash-report consent token in DataStore")
                    ConsentReadResult.Unavailable(e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Report the unknown state instead of collapsing it into a default:
            // the caller's NeverAsked branch writes back, so a failed read must
            // not be able to overwrite a stored decision (A2, AUDIT-10 #2).
            TimberWrapper.silentError(e, "Failed to read crash-report consent decision from DataStore")
            ConsentReadResult.Unavailable(e)
        }
    }

    override suspend fun setConsent(granted: Boolean): ConsentWriteResult {
        return try {
            dataStore.edit { prefs ->
                prefs[ConsentBootstrap.CONSENT_DECISION_KEY] =
                    if (granted) ConsentBootstrap.VALUE_GRANTED else ConsentBootstrap.VALUE_DENIED
            }
            ConsentWriteResult.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort persist: report the failure instead of throwing or
            // swallowing it. Nothing was written, so the previous stored
            // decision keeps winning; the caller (already switched ACRA
            // in-memory) gets a Failed it can surface (A4, AUDIT-10 #11).
            TimberWrapper.silentError(e, "Failed to save crash-report consent to DataStore")
            ConsentWriteResult.Failed(e)
        }
    }
}
