package com.github.reygnn.nyx_launcher.settings

import com.github.reygnn.nyx_launcher.home.model.ImportResult

/**
 * PURE LOGIC — what the settings tell the user after a backup operation (2b-3b). Each outcome
 * of an import or a refused preview has its own message; the fragment turns it into text.
 */
sealed interface BackupMessage {
    data object ExportDone : BackupMessage
    data object ExportFailed : BackupMessage
    data class ImportDone(val droppedWallpaperLayers: Int) : BackupMessage
    data class ForeignBackup(val appId: String) : BackupMessage
    data object OutdatedBackup : BackupMessage
    data class UnsupportedVersion(val version: String) : BackupMessage
    data object InvalidBackup : BackupMessage
    /** Reading failed (provider, size, timeout); the details are in the log. */
    data object ImportFailed : BackupMessage
    data object NothingSelected : BackupMessage

    companion object {
        /** One mapping for both paths: the import's outcome and a refused preview. */
        fun forImport(result: ImportResult): BackupMessage = when (result) {
            is ImportResult.Success -> ImportDone(result.droppedWallpaperLayers)
            is ImportResult.ForeignBackup -> ForeignBackup(result.appId)
            ImportResult.OutdatedBackup -> OutdatedBackup
            is ImportResult.UnsupportedVersion -> UnsupportedVersion(result.version)
            ImportResult.InvalidFormat -> InvalidBackup
            is ImportResult.Error -> ImportFailed
        }
    }
}
