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

    // WICHTIG: Kein 'by lazy' hier, das den Main Thread blockieren könnte.
    // Wir holen die Prefs explizit im IO-Context.

    suspend fun runMigrationIfNeeded() {
        // Wrapper, um sicherzustellen, dass alles im Hintergrund passiert
        withContext(Dispatchers.IO) {
            try {
                val currentVersion = getCurrentVersion()

                if (currentVersion < TARGET_DATA_VERSION) {
                    Timber.Forest.i("Migration needed from version $currentVersion to $TARGET_DATA_VERSION")
                    runMigration(currentVersion)
                } else {
                    Timber.Forest.d("No migration needed. Current version: $currentVersion")
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
            when {
                currentVersion == 0 -> {
                    Timber.Forest.i("First launch detected, setting version without clearing DataStore")
                }
                currentVersion < TARGET_DATA_VERSION -> {
                    Timber.Forest.i("Old data version detected: $currentVersion. Clearing DataStore...")
                    try {
                        dataStore.edit { it.clear() }
                        Timber.Forest.i("DataStore cleared successfully")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Failed to clear DataStore during migration")
                    }
                }
                else -> {
                    return
                }
            }
            updateVersionInPreferences(TARGET_DATA_VERSION)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error during migration")
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
            TimberWrapper.silentError(e, "Error reading data version")
            0
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
            Timber.Forest.i("Data version updated to $version")
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