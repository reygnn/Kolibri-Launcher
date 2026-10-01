package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.BackupPreview
import com.github.reygnn.nyx_launcher.home.model.ImportOptions
import com.github.reygnn.nyx_launcher.home.model.ImportResult

/** Configurable stub of [BackupRepository] for UI tests; records the last call's arguments. */
class FakeBackupRepository : BackupRepository {
    var exportSuccess = true
    var importResult: ImportResult = ImportResult.Success()
    var previewResult: BackupPreview? = null

    var lastExportUri: String? = null
    var lastImportUri: String? = null
    var lastOptions: ImportOptions? = null
    var lastPreviewUri: String? = null

    override suspend fun saveBackupToFile(uriString: String): Boolean {
        lastExportUri = uriString
        return exportSuccess
    }

    override suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult {
        lastImportUri = uriString
        lastOptions = options
        return importResult
    }

    override suspend fun previewBackup(uriString: String): BackupPreview? {
        lastPreviewUri = uriString
        return previewResult
    }
}
