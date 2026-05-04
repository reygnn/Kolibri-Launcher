package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bootstraps schema migrations for the app's persisted user state on each
 * startup. Reads the currently-stored data version, compares it against
 * [TARGET_DATA_VERSION], and runs the migration path if needed. Migration
 * steps are additive — see [runMigration].
 *
 * ## Why this class uses SharedPreferences for the version flag
 *
 * CLAUDE.md Rule 5 says DataStore is the only app storage. This class is
 * the one explicit exception: the migration mechanism cannot bootstrap
 * itself through the very thing it might need to migrate.
 *
 * Concretely, the version flag is stored in a tiny SharedPreferences
 * file (`kolibri_data_version`, single Int key `data_version`). It must
 * be readable *before* anything else opens DataStore — if we kept the
 * version flag inside DataStore, then on a corrupted-DataStore launch
 * we would have no way to detect that we are starting fresh vs. that
 * a real version-N DataStore has just failed to load. The
 * SharedPreferences-on-disk file decouples "what version is the on-disk
 * state at" from "what state can we currently load".
 *
 * Tradeoff: this is exactly the kind of `SharedPreferences` access that
 * triggers StrictMode noise. It's accepted here because the read is
 * tiny (one Int), happens once on app start in `Dispatchers.IO`, and
 * the alternative (DataStore-bootstrap) is not safe.
 *
 * Don't try to migrate this away. If you spot the SharedPreferences
 * call in `getCurrentVersion` / `updateVersionInPreferences` and want
 * to "clean it up", read this KDoc and CLAUDE.md Rule 5 first.
 *
 * ## Per-step legacy-SP reads are different
 *
 * Some individual migration steps (currently: V1→V2 ACRA consent) also
 * touch a different, *legacy* SharedPreferences file — one that this
 * codebase used to write but no longer does. Those reads exist solely
 * to copy the old value into DataStore and then delete the legacy file.
 * They are not the version-flag exception above; they are scoped to a
 * single migration run. New migration steps that touch legacy SP files
 * follow the same shape: read, write to DataStore, delete the legacy
 * file, never touch it again.
 */
@Singleton
class DataMigrationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val VERSION_PREFS_NAME = "kolibri_data_version"
        private const val KEY_DATA_VERSION = "data_version"

        /**
         * Bump this when introducing a new schema/storage migration.
         * Each `if (currentVersion < N) migrateToVN()` step in [runMigration]
         * runs only when the on-disk version is below N, so migrations are
         * additive and replayable across upgrades.
         *
         * Version history:
         *   1 — initial DataStore schema (pre-§7 baseline)
         *   2 — ACRA consent flag migrated from SharedPreferences (acra_consent)
         *       to DataStore (TODO §7 / 2026-05-01)
         */
        private const val TARGET_DATA_VERSION = 2

        // Legacy SharedPreferences names retained only so the V1→V2 migration
        // can find and read them. Do not use these names from any new code.
        private const val LEGACY_ACRA_CONSENT_PREFS = "acra_consent"
        private const val LEGACY_ACRA_KEY_CONSENT = "has_consent"
        private const val LEGACY_ACRA_KEY_ASKED = "has_asked"
    }

    // WICHTIG: Kein 'by lazy' hier, das den Main Thread blockieren könnte.
    // Wir holen die Prefs explizit im IO-Context.

    suspend fun runMigrationIfNeeded() {
        // Wrapper, um sicherzustellen, dass alles im Hintergrund passiert
        withContext(Dispatchers.IO) {
            try {
                val currentVersion = getCurrentVersion()

                if (currentVersion < TARGET_DATA_VERSION) {
                    Timber.i("Migration needed from version $currentVersion to $TARGET_DATA_VERSION")
                    runMigration(currentVersion)
                } else {
                    Timber.d("No migration needed. Current version: $currentVersion")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Critical error during migration check")
            }
        }
    }

    private suspend fun runMigration(currentVersion: Int) {
        try {
            if (currentVersion == 0) {
                Timber.i("First launch detected — running schema migrations from initial state")
            }

            // Additive migrations: each step runs if the on-disk version is below it.
            // New steps go here as `if (currentVersion < N) migrateToVN()`.
            if (currentVersion < 2) {
                migrateAcraConsentToDataStore()
            }

            updateVersionInPreferences(TARGET_DATA_VERSION)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error during migration")
        }
    }

    /**
     * V1 → V2 migration: copy the ACRA consent flags from the legacy
     * `acra_consent` SharedPreferences file into DataStore, then delete the
     * legacy file.
     *
     * Skips the DataStore write entirely when neither legacy key exists —
     * which is the case on every install that started on V2-or-later code
     * (no legacy file ever existed). Without this guard the migration would
     * unconditionally write `false/false` to DataStore even on a fresh
     * install, racing with [CrashReportConsentStore.saveConsent] if the
     * user accepts the consent dialog before this coroutine completes
     * (Application.onCreate launches this asynchronously). The race would
     * silently clobber the user's freshly given consent. The
     * `legacyPrefs.contains(...)` check makes the migration a true no-op
     * for installs that never had legacy data — even a non-existent SP
     * file returns `false` for `contains`, so the guard is safe.
     *
     * Failure handling: any exception is caught + logged. The version still
     * gets bumped (via [runMigration]'s `updateVersionInPreferences`), so we
     * don't loop forever; the worst-case user-visible effect is the consent
     * dialog showing once more on next launch (annoying, not a privacy
     * violation since the default is "no consent").
     */
    private suspend fun migrateAcraConsentToDataStore() {
        try {
            val legacyPrefs = context.getSharedPreferences(
                LEGACY_ACRA_CONSENT_PREFS, Context.MODE_PRIVATE
            )

            val hasLegacyData = legacyPrefs.contains(LEGACY_ACRA_KEY_CONSENT) ||
                legacyPrefs.contains(LEGACY_ACRA_KEY_ASKED)

            if (!hasLegacyData) {
                Timber.i(
                    "ACRA consent migration: no legacy SP data, skipping DataStore write"
                )
                return
            }

            val hasConsent = legacyPrefs.getBoolean(LEGACY_ACRA_KEY_CONSENT, false)
            val hasAsked = legacyPrefs.getBoolean(LEGACY_ACRA_KEY_ASKED, false)

            dataStore.edit { prefs ->
                prefs[CrashReportConsentStore.HAS_CONSENT_KEY] = hasConsent
                prefs[CrashReportConsentStore.HAS_ASKED_KEY] = hasAsked
            }

            // Only delete after the DataStore write succeeded. On Android 24+
            // (we ship SDK 36) `deleteSharedPreferences` removes both the in-
            // memory cache and the on-disk XML. Best-effort: failure here just
            // leaves a small unused file behind, no functional impact.
            context.deleteSharedPreferences(LEGACY_ACRA_CONSENT_PREFS)

            Timber.i(
                "ACRA consent migrated to DataStore (consent=$hasConsent, asked=$hasAsked)"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(
                e,
                "ACRA consent migration failed — DataStore stays at default false/false"
            )
        }
    }

    /**
     * Liest die Version sicher im IO-Thread.
     */
    private fun getCurrentVersion(): Int {
        return try {
            val prefs = context.getSharedPreferences(VERSION_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getInt(KEY_DATA_VERSION, 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Ein silent return 0 würde "First launch detected" triggern und
            // den Onboarding-Flow über echte User-Daten kippen. Lieber
            // kontrolliert sterben — beim Neustart kommt der Read-Versuch
            // nochmal, und wenn er weiter failt, häuft sich der ACRA-Report.
            TimberWrapper.silentDeath(
                e,
                "Cannot read data version — refusing to assume first launch"
            )
        }
    }

    private fun updateVersionInPreferences(version: Int) {
        try {
            val prefs = context.getSharedPreferences(VERSION_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                putInt(KEY_DATA_VERSION, version)
                // commit() ist hier okay, da wir bereits im Dispatchers.IO Block sind (via runMigrationIfNeeded)
                // commit() schreibt sofort, apply() asynchron. Bei Migrationen ist Sicherheit > Speed.
                commit()
            }
            Timber.i("Data version updated to $version")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to update version in preferences")
        }
    }

    /**
     * Prüft "First Launch" Status.
     * ACHTUNG: Muss suspend sein, um StrictMode zu vermeiden!
     */
    suspend fun isFirstLaunch(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                getCurrentVersion() < TARGET_DATA_VERSION
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error checking first launch status")
                true
            }
        }
    }
}