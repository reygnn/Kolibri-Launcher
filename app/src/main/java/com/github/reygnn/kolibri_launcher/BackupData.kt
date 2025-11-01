package com.github.reygnn.kolibri_launcher

import kotlinx.serialization.Serializable

/**
 * Versioniertes Backup-Format für Kolibri Launcher Settings.
 */
@Serializable
data class BackupData(
    val version: String = "1.0.0",
    val timestamp: Long = System.currentTimeMillis(),
    val appVersion: String = BuildConfig.VERSION_NAME,
    val settings: LauncherSettings
)

@Serializable
data class LauncherSettings(
    val favoriteComponents: Set<String> = emptySet(),
    val favoritesOrder: List<String> = emptyList()
)

/**
 * Optionen für selektiven Import.
 */
data class ImportOptions(
    val importFavorites: Boolean = true,
    val importOrder: Boolean = true
) {
    val importNothing: Boolean
        get() = !importFavorites && !importOrder
}

/**
 * Preview-Informationen über ein Backup.
 */
data class BackupPreview(
    val version: String,
    val timestamp: Long,
    val favoriteCount: Int,
    val orderCount: Int
)

/**
 * Ergebnis eines Backup-Imports.
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