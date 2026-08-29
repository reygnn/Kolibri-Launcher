package com.github.reygnn.kolibri_launcher.crashreporting.consent

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.di.consentDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Low-level pre-Hilt accessor for the ACRA crash-report consent decision.
 *
 * Storage: the dedicated `consentDataStore` file (`acra_consent`) — separate
 * from the project-wide `settingsDataStore` so the consent decision is
 * excluded from Auto Backup (privacy-by-default) while user config still
 * travels. One string key holds the whole tri-state (ACRA_SPEC.md A.2):
 * absent = [ConsentDecision.NeverAsked], `"GRANTED"` / `"DENIED"` the answers.
 *
 * ## Why this object exists next to [CrashReportConsentRepository]
 *
 * The interactive (post-Hilt) path — dialog and settings toggle — goes through
 * the Hilt-injected repository. This object serves the two call sites that
 * CANNOT use Hilt:
 *   - [readDecision] is the synchronous **R1** read from
 *     `KolibriLauncherApp.attachBaseContext`, BEFORE Hilt and ACRA are wired,
 *     deciding whether ACRA starts enabled. Process-gating (X2) is the
 *     caller's job, not this object's.
 *   - [seedDecision] is the `androidTest` seed helper that pre-answers the
 *     dialog before a UI test (no Hilt graph to inject from).
 * Both share [CONSENT_DECISION_KEY] with [CrashReportConsentRepositoryImpl]
 * (single source of truth) and hit the same file, so R1 and the repository
 * always agree — one file, one key, two readers (§3.3).
 *
 * Plain `Timber.e` is intentional here per CLAUDE.md Rule 9: [readDecision]
 * runs before Hilt and ACRA are initialised. A `silentError` throw at that
 * bootstrap point would crash the app before the crash reporter is wired,
 * hiding rather than surfacing the bug. (The repository, post-Hilt, uses
 * `silentError`.)
 */
object ConsentBootstrap {

    /** The single tri-state key. Absence means [ConsentDecision.NeverAsked]. */
    val CONSENT_DECISION_KEY = stringPreferencesKey("consent_decision")

    const val VALUE_GRANTED = "GRANTED"
    const val VALUE_DENIED = "DENIED"

    /**
     * Pre-Hilt R1 read used to decide ACRA's initial state. Only
     * [ConsentDecision.Granted] should enable ACRA; everything else
     * (absent / unknown token / read error) yields a non-`Granted` decision so
     * ACRA stays OFF (fail-closed, A1). The [ConsentReadResult.Unavailable]
     * distinction is not needed here — no dialog or write follows R1, only
     * `setEnabled(decision == Granted)` — so it lives in the repository's
     * [CrashReportConsentRepository.readState] (R2) instead.
     */
    suspend fun readDecision(context: Context): ConsentDecision =
        readDecision(context.consentDataStore)

    /**
     * Testable core: maps the stored token to a decision, fail-closed. Exposed
     * so the R1 gate mapping (and its I/O / cancellation handling) can be pinned
     * with a fake [DataStore] instead of the pre-Hilt `context` extension.
     */
    @VisibleForTesting
    internal suspend fun readDecision(dataStore: DataStore<Preferences>): ConsentDecision {
        return try {
            when (dataStore.data.first()[CONSENT_DECISION_KEY]) {
                VALUE_GRANTED -> ConsentDecision.Granted
                VALUE_DENIED -> ConsentDecision.Denied
                // Absent OR an unknown token: not Granted -> ACRA stays OFF.
                // R2 (readState) is where an unknown token becomes Unavailable.
                else -> ConsentDecision.NeverAsked
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // pre-wiring bare Timber.e (Rule 9): readDecision runs in the onCreate
            // consent gate, BEFORE AcraTree is planted (planted in
            // CrashReportingBootstrap.onCreate only AFTER this gate runs). reportToAcra
            // would be a no-op here, and silentError's DEBUG throw would crash the
            // app before the reporter exists — so this stays a bare Timber.e.
            Timber.e(e, "Failed to read crash-report consent decision from DataStore.")
            ConsentDecision.NeverAsked
        }
    }

    /**
     * Seeds a decision. Production writes go through
     * [CrashReportConsentRepository]; this remains only as the `androidTest`
     * seed helper (pre-answering the dialog before a UI test).
     * [ConsentDecision.NeverAsked] clears the key (restores the default).
     */
    @VisibleForTesting
    suspend fun seedDecision(context: Context, decision: ConsentDecision) {
        try {
            context.consentDataStore.edit { prefs ->
                when (decision) {
                    ConsentDecision.Granted -> prefs[CONSENT_DECISION_KEY] = VALUE_GRANTED
                    ConsentDecision.Denied -> prefs[CONSENT_DECISION_KEY] = VALUE_DENIED
                    ConsentDecision.NeverAsked -> prefs.remove(CONSENT_DECISION_KEY)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // pre-wiring bare Timber.e (Rule 9): seedDecision is a @VisibleForTesting
            // helper invoked only from androidTest to pre-answer the dialog — off
            // the production path, kept bare for parity with readDecision above.
            Timber.e(e, "Failed to seed crash-report consent decision in DataStore.")
        }
    }
}
