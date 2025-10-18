package com.github.reygnn.kolibri_launcher

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class DataStoreBackup @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val dataStoreName = AppConstants.SETTINGS_DATASTORE_NAME

    companion object {
        private const val BACKUP_DIR = "KolibriLauncherBackup"
        private const val BACKUP_FILE_NAME = "kolibri_settings.backup"
    }

    private val dataStoreDir = File(context.filesDir, "datastore")
    private val dataStoreFile = File(dataStoreDir, "$dataStoreName.preferences_pb")

    /**
     * HINWEIS: Diese Implementierung verwendet die veraltete `getExternalStoragePublicDirectory`-API.
     * Sie funktioniert auf vielen physischen Geräten für Debug-Zwecke, schlägt aber auf
     * modernen Emulatoren und strikten Android-Versionen aufgrund von Scoped Storage fehl.
     * Dies ist für den aktuellen Entwicklungs-Workflow beabsichtigt.
     * Für eine Veröffentlichung im Play Store müsste dies durch das Storage Access Framework (SAF)
     * ersetzt werden.
     */
    @Suppress("DEPRECATION")
    private val backupDir = File(android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DOWNLOADS), BACKUP_DIR)
    private val backupFile = File(backupDir, BACKUP_FILE_NAME)

    /**
     * Creates a backup of the DataStore.
     */
    suspend fun createBackup() {
        withContext(Dispatchers.IO) {
            if (!dataStoreFile.exists()) {
                Timber.w("DataStore file not found, cannot create backup.")
                return@withContext
            }
            try {
                if (!backupDir.exists()) {
                    backupDir.mkdirs()
                }

                // Prüfen, ob die Backup-Datei existiert und versuchen, sie zu löschen.
                if (backupFile.exists()) {
                    if (!backupFile.delete()) {
                        Timber.w("Could not delete existing backup file at: ${backupFile.absolutePath}")
                    }
                }

                // Jetzt die neue Datei kopieren. overwrite=true ist jetzt eine zusätzliche Absicherung.
                dataStoreFile.copyTo(backupFile, overwrite = true)
                Timber.i("DataStore successfully backed up to ${backupFile.absolutePath}")

            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                Timber.w("Error while creating DataStore backup.")
            }
        }
    }

    /**
     * Restores a backup.
     */
    suspend fun restoreFromBackup() {
        withContext(Dispatchers.IO) {
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
                if (e is CancellationException) {
                    throw e
                }
                Timber.w("Error while restoring DataStore backup.")
            }
        }
    }


    /**
     * Checks if a backup file exists by checking the filesystem.
     * @return `true` if a backup exists, `false` otherwise.
     */
    suspend fun isBackupPresent(): Boolean = withContext(Dispatchers.IO) {
        try {
            backupFile.exists()
        } catch (_: Exception) {
            if (BuildConfig.DEBUG) {
                Timber.d("Could not check backup presence")
            }
            false
        }
    }

}