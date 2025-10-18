package com.github.reygnn.kolibri_launcher

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
/**
 * Dies ist die "Dummy"-Implementierung von DataStoreBackup für Release-Builds.
 * Sie hat die exakt gleiche Struktur (Klassenname, Konstruktor, Methoden) wie die
 * Debug-Version, aber alle Methoden sind leer und tun absichtlich nichts.
 *
 * Gradle wird diese Version automatisch für den Release-Build auswählen.
 */
@Singleton
class DataStoreBackup @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    // private val dataStoreName = AppConstants.SETTINGS_DATASTORE_NAME

    /**
     * Macht in Release-Builds nichts.
     */
    suspend fun createBackup() {
        // No-op (No Operation)
    }

    /**
     * Macht in Release-Builds nichts.
     */
    suspend fun restoreFromBackup() {
        // No-op (No Operation)
    }

    /**
     * In release builds, backups are not supported. This function always returns `false`.
     */
    fun isBackupPresent(): Boolean {
        return false
    }
}