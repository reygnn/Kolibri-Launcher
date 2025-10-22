package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataMigrationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val VERSION_PREFS_NAME = "kolibri_data_version"
        private const val KEY_DATA_VERSION = "data_version"
        private const val TARGET_DATA_VERSION = 1
    }

    private val versionPrefs: SharedPreferences? by lazy {
        try {
            context.getSharedPreferences(VERSION_PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to get SharedPreferences for version tracking")
            null
        }
    }

    suspend fun runMigrationIfNeeded() {
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

    private suspend fun runMigration(currentVersion: Int) {
        try {
            when {
                currentVersion == 0 -> {
                    // ✅ Erste Installation: NICHTS clearen, nur Version setzen
                    Timber.i("First launch detected, setting version without clearing DataStore")
                }
                currentVersion < TARGET_DATA_VERSION -> {
                    // ✅ Alte Version: DataStore clearen
                    Timber.i("Old data version detected: $currentVersion. Clearing DataStore...")
                    try {
                        dataStore.edit { it.clear() }
                        Timber.i("DataStore cleared successfully")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Failed to clear DataStore during migration")
                    }
                }
                else -> {
                    // ✅ Aktuelle oder neuere Version: Nichts tun
                    Timber.d("Data version is current or newer: $currentVersion")
                    return
                }
            }

            // Version setzen
            updateVersionInPreferences(TARGET_DATA_VERSION)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error during migration")
        }
    }

    private fun getCurrentVersion(): Int {
        return try {
            versionPrefs?.getInt(KEY_DATA_VERSION, 0) ?: 0
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error reading data version")
            0
        }
    }

    // ✅ NEU: Helper function zum Version setzen
    private fun updateVersionInPreferences(version: Int) {
        try {
            versionPrefs?.edit()?.apply {
                putInt(KEY_DATA_VERSION, version)
                apply()
            }
            Timber.i("Data version updated to $version")
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to update version in preferences")
        }
    }

    fun isFirstLaunch(): Boolean {
        return try {
            getCurrentVersion() < TARGET_DATA_VERSION
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error checking first launch status")
            true
        }
    }
}