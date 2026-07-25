package com.github.reygnn.kolibri_launcher.ui.backup

sealed class BackupState {
    object Idle : BackupState()
    object Loading : BackupState()
    object ExportSuccess : BackupState()
    data class ImportSuccess(
        val importedCount: Int,
        val skippedCount: Int,
        val missingApps: Set<String>,
        val droppedWallpaperLayers: Int = 0
    ) : BackupState()
    data class LimitExceeded(
        val packageCount: Int,
        val limit: Int
    ) : BackupState()
    data class UnsupportedVersion(val version: String) : BackupState()
    object InvalidFormat : BackupState()
    data class Error(val message: String) : BackupState()
}