package com.github.reygnn.kolibri_launcher

import kotlinx.serialization.Serializable

/**
 * Versioniertes Backup-Format für Kolibri Launcher Settings.
 *
 * Struktur ist absichtlich getrennt von DataStore-Implementation,
 * um Unabhängigkeit und Migrationsfähigkeit zu gewährleisten.
 *
 * @property version Backup-Format-Version (Semantic Versioning)
 * @property timestamp Unix-Timestamp der Backup-Erstellung
 * @property appVersion App-Version, die das Backup erstellt hat
 * @property settings Eigentliche Launcher-Einstellungen
 */
@Serializable
data class BackupData(
    val version: String = "1.0.0",
    val timestamp: Long = System.currentTimeMillis(),
    val appVersion: String = BuildConfig.VERSION_NAME,
    val settings: LauncherSettings
)

/**
 * Launcher-Einstellungen für Backup/Export.
 *
 * Entspricht den Daten aus FavoritesRepository und FavoritesOrderRepository,
 * aber in einer vom DataStore unabhängigen Struktur.
 */
@Serializable
data class LauncherSettings(
    /**
     * Set der favorisierten Component-Namen.
     * Format: "packageName/activityClassName"
     *
     * Beispiel: "com.google.android.gm/.ConversationListActivityGmail"
     */
    val favoriteComponents: Set<String> = emptySet(),

    /**
     * Geordnete Liste der Favoriten für Display-Reihenfolge.
     *
     * Kann Elemente enthalten, die nicht in favoriteComponents sind
     * (lazy cleanup beim Import). Wird beim Import gefiltert.
     */
    val favoritesOrder: List<String> = emptyList()
)

/**
 * Ergebnis eines Backup-Imports mit detaillierter Information.
 */
sealed class ImportResult {
    data class Success(
        val importedCount: Int,
        val skippedCount: Int,
        val missingApps: Set<String>
    ) : ImportResult()

    data class UnsupportedVersion(val version: String) : ImportResult()

    data class LimitExceeded(
        val packageCount: Int,
        val limit: Int
    ) : ImportResult()

    object InvalidFormat : ImportResult()

    data class Error(val message: String) : ImportResult()
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)