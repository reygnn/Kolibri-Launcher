package com.github.reygnn.kolibri_launcher

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
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
    }

    fun isFirstLaunch(): Boolean {
        return try {
            val prefs = context.getSharedPreferences(VERSION_PREFS_NAME, Context.MODE_PRIVATE)
                ?: return true  // ✅ Bei null: als ersten Start behandeln

            val currentVersion = prefs.getInt(KEY_DATA_VERSION, 0)
            currentVersion < TARGET_DATA_VERSION
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error checking first launch status")
            true  // ✅ Bei Fehler: als ersten Start behandeln
        }
    }

    suspend fun runMigrationIfNeeded() {
        try {
            val prefs = context.getSharedPreferences(VERSION_PREFS_NAME, Context.MODE_PRIVATE)
                ?: run {
                    TimberWrapper.silentError("Failed to access SharedPreferences for migration")
                    return
                }

            val currentVersion = prefs.getInt(KEY_DATA_VERSION, 0)

            when {
                currentVersion == 0 -> {
                    // Erste Installation - keine Migration nötig
                    Timber.i("First installation detected. Setting data version to $TARGET_DATA_VERSION.")
                    try {
                        prefs.edit().putInt(KEY_DATA_VERSION, TARGET_DATA_VERSION).apply()
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error setting initial data version")
                        // Nicht kritisch, beim nächsten Start wird es erneut versucht
                    }
                }

                currentVersion < TARGET_DATA_VERSION -> {
                    // Alte Version - Migration durchführen
                    Timber.i("Old data version ($currentVersion) detected. Starting migration to version $TARGET_DATA_VERSION.")

                    var migrationSuccessful = false

                    try {
                        dataStore.edit { settings ->
                            settings.clear()
                        }
                        migrationSuccessful = true
                        Timber.i("Migration completed successfully. DataStore has been cleared.")

                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error during data migration! Migration will be retried on next start.")
                        // WICHTIG: Version NICHT hochsetzen, damit Migration beim nächsten Start erneut versucht wird
                    }

                    // Version nur hochsetzen, wenn Migration erfolgreich war
                    if (migrationSuccessful) {
                        try {
                            prefs.edit().putInt(KEY_DATA_VERSION, TARGET_DATA_VERSION).apply()
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Migration successful but failed to update version number")
                            // Migration war erfolgreich, nur das Version-Update hat nicht geklappt
                            // Beim nächsten Start wird die Migration erneut durchgeführt (idempotent, also ok)
                        }
                    }
                }

                else -> {
                    Timber.d("Data is already up to date (version $currentVersion). No migration necessary.")
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Critical error in migration manager! App will continue with existing data.")
            // App darf trotz Migration-Fehler starten
        }
    }
}