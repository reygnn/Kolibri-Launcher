package com.github.reygnn.kolibri_launcher

import kotlinx.serialization.Serializable

@Serializable
data class BackupData(
    val version: String = "1.0.0",
    val timestamp: Long = 0L,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val settings: LauncherSettings
)

@Serializable
data class LauncherSettings(
    val favoriteComponents: Set<String> = emptySet(),
    val favoritesOrder: List<String> = emptyList(),
    val hiddenComponents: Set<String> = emptySet(),
    val customAppNames: Map<String, String> = emptyMap(),
    val swipeLeftApp: String? = null,
    val swipeRightApp: String? = null,
    val textColor: Int? = null,
    val textShadowEnabled: Boolean? = null
)

data class ImportOptions(
    val importFavorites: Boolean = true,
    val importOrder: Boolean = true,
    val importHiddenApps: Boolean = true,
    val importCustomNames: Boolean = true,
    val importSwipeActions: Boolean = true,
    val importThemeSettings: Boolean = true
) {
    val importNothing: Boolean
        get() = !importFavorites &&
                !importOrder &&
                !importHiddenApps &&
                !importCustomNames &&
                !importSwipeActions &&
                !importThemeSettings
}

data class BackupPreview(
    val version: String,
    val timestamp: Long,
    val favoriteCount: Int,
    val orderCount: Int,
    val hiddenCount: Int,
    val customNamesCount: Int,
    val hasSwipeLeft: Boolean,
    val hasSwipeRight: Boolean,
    val hasThemeSettings: Boolean
)

sealed class ImportResult {
    data class Success(
        val importedCount: Int,
        val skippedCount: Int,
        val missingApps: Set<String>
    ) : ImportResult()

    data class UnsupportedVersion(val version: String) : ImportResult()
    data class LimitExceeded(val packageCount: Int, val limit: Int) : ImportResult()
    object InvalidFormat : ImportResult()
    data class Error(val message: String) : ImportResult()
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)