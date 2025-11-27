package com.github.reygnn.kolibri_launcher

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------
// AKTIVE KLASSE (STILLGELEGT / STUB)
// ---------------------------------------------------------
@Singleton
class DataStoreBackup @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /**
     * Creates a backup of the DataStore.
     * LOGIC DISABLED
     */
    suspend fun createBackup() {
        Timber.d("DataStoreBackup: createBackup was called but is disabled.")
    }

    /**
     * Restores a backup.
     * LOGIC DISABLED
     */
    suspend fun restoreFromBackup() {
        Timber.d("DataStoreBackup: restoreFromBackup was called but is disabled.")
    }

    suspend fun isBackupPresent(): Boolean {
        // Immer false zurückgeben, damit die UI nicht denkt, es gäbe ein Backup.
        return false
    }
}

// ---------------------------------------------------------
// ALTE KLASSE (KOMPLETT DEAKTIVIERT)
// ---------------------------------------------------------
/*
HINWEIS: Dieser Code ist auskommentiert, damit Hilt ihn ignoriert und keine
Abstürze durch die File-Initialisierung verursacht werden.
ALSO: NICHT LÖSCHEN !!!
*/

/*
@Singleton
class OrigDataStoreBackup @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val dataStoreName = com.github.reygnn.kolibri_launcher.core.AppConstants.SETTINGS_DATASTORE_NAME

    companion object {
        private const val BACKUP_DIR = "KolibriLauncherBackup"
        private const val BACKUP_FILE_NAME = "kolibri_settings.backup"
    }

    private val dataStoreDir = java.io.File(context.filesDir, "datastore")
    private val dataStoreFile = java.io.File(dataStoreDir, "$dataStoreName.preferences_pb")

    @Suppress("DEPRECATION")
    private val backupDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DOWNLOADS), BACKUP_DIR)
    private val backupFile = java.io.File(backupDir, BACKUP_FILE_NAME)

    suspend fun createBackup() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!dataStoreFile.exists()) {
                Timber.w("DataStore file not found, cannot create backup.")
                return@withContext
            }
            try {
                if (!backupDir.exists()) {
                    backupDir.mkdirs()
                }
                if (backupFile.exists()) {
                    if (!backupFile.delete()) {
                        Timber.w("Could not delete existing backup file at: ${backupFile.absolutePath}")
                    }
                }
                dataStoreFile.copyTo(backupFile, overwrite = true)
                Timber.i("DataStore successfully backed up to ${backupFile.absolutePath}")

            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Timber.w("Error while creating DataStore backup.")
            }
        }
    }

    suspend fun restoreFromBackup() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (backupFile.exists()) {
                    if (!dataStoreDir.exists()) {
                        dataStoreDir.mkdirs()
                    }
                    backupFile.copyTo(dataStoreFile, overwrite = true)
                    Timber.i("DataStore successfully restored from ${backupFile.absolutePath}")
                } else {
                    Timber.d("No backup file found, skipping restore.")
                }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Timber.w("Error while restoring DataStore backup.")
            }
        }
    }

    suspend fun isBackupPresent(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            backupFile.exists()
        } catch (_: Exception) {
            false
        }
    }
}
*/