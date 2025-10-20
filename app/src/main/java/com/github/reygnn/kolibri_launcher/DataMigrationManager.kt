package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataMigrationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {

    companion object {
        private const val VERSION_PREFS_NAME = "kolibri_data_version"
        private const val KEY_DATA_VERSION = "data_version"
        private const val TARGET_DATA_VERSION = 1
        private const val MIGRATION_TIMEOUT_MS = 10000L
    }

    /**
     * Prüft ob dies der erste Start der App ist.
     * WICHTIG: Bei jedem Fehler wird "true" zurückgegeben (safe default).
     */
    fun isFirstLaunch(): Boolean {
        return try {
            val prefs = context.getSharedPreferences(VERSION_PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs == null) {
                Timber.w("Could not access SharedPreferences, treating as first launch")
                return true
            }

            val currentVersion = try {
                prefs.getInt(KEY_DATA_VERSION, 0)
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error reading data version, treating as first launch")
                0
            }

            currentVersion < TARGET_DATA_VERSION

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error checking first launch status, treating as first launch")
            true // Safe default
        }
    }

    /**
     * Führt Migration durch falls nötig.
     * KRITISCH: Diese Funktion darf NIEMALS crashen!
     */
    suspend fun runMigrationIfNeeded() {
        try {
            val prefs = context.getSharedPreferences(VERSION_PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs == null) {
                TimberWrapper.silentError("Failed to access SharedPreferences for migration, skipping")
                return
            }

            val currentVersion = try {
                prefs.getInt(KEY_DATA_VERSION, 0)
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error reading current data version, assuming version 0")
                0
            }

            when {
                currentVersion == 0 -> {
                    handleFirstInstallation(prefs)
                }

                currentVersion < TARGET_DATA_VERSION -> {
                    handleMigration(prefs, currentVersion)
                }

                else -> {
                    Timber.d("Data is already up to date (version $currentVersion). No migration necessary.")
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "CRITICAL error in migration manager! App will continue with existing data.")
        }
    }

    /**
     * Behandelt erste Installation
     */
    private fun handleFirstInstallation(prefs: SharedPreferences) {
        try {
            Timber.i("First installation detected. Setting data version to $TARGET_DATA_VERSION.")

            // Verwende commit() für sofortiges Feedback
            val success = try {
                prefs.edit().putInt(KEY_DATA_VERSION, TARGET_DATA_VERSION).commit()
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error setting initial data version")
                false
            }

            if (!success) {
                Timber.w("Could not set initial version, will retry on next start")
            }

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in handleFirstInstallation")
        }
    }

    /**
     * Führt die eigentliche Migration durch
     */
    private suspend fun handleMigration(prefs: SharedPreferences, currentVersion: Int) {
        try {
            Timber.i("Old data version ($currentVersion) detected. Starting migration to version $TARGET_DATA_VERSION.")

            var migrationSuccessful = false

            try {
                withTimeout(MIGRATION_TIMEOUT_MS) {
                    dataStore.edit { settings ->
                        settings.clear()
                    }
                }
                migrationSuccessful = true
                Timber.i("Migration completed successfully. DataStore has been cleared.")

            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error during data migration! Migration will be retried on next start.")
            }

            // Version nur hochsetzen, wenn Migration erfolgreich war
            if (migrationSuccessful) {
                updateDataVersion(prefs)
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in handleMigration")
        }
    }

    /**
     * Aktualisiert die Datenversion in SharedPreferences
     */
    private fun updateDataVersion(prefs: SharedPreferences) {
        try {
            val success = try {
                prefs.edit().putInt(KEY_DATA_VERSION, TARGET_DATA_VERSION).commit()
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error updating version")
                false
            }

            if (success) {
                Timber.i("Version updated successfully to $TARGET_DATA_VERSION")
            } else {
                Timber.w("Migration successful but failed to update version number")
                // Beim nächsten Start wird die Migration erneut durchgeführt (idempotent, also ok)
            }

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in updateDataVersion")
        }
    }
}