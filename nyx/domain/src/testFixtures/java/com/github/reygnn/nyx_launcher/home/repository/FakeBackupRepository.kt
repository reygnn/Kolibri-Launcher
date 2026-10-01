package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.nyx_launcher.home.model.ImportOptions
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.model.PreviewResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Configurable stub of [BackupRepository] for UI tests; records the last call's arguments. */
class FakeBackupRepository : BackupRepository {
    var exportSuccess = true
    var importResult: ImportResult = ImportResult.Success()
    /** Default: a file that is no readable backup. */
    var previewResult: PreviewResult = PreviewResult.Refused(ImportResult.InvalidFormat)
    /**
     * Set to make previewBackup hang like a provider blocked in read(): it ignores cancellation
     * and returns only after ten preview timeouts (2b-3b-c).
     */
    var previewHangs = false

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

    override suspend fun previewBackup(uriString: String): PreviewResult {
        lastPreviewUri = uriString
        if (previewHangs) withContext(NonCancellable) { delay(10 * AppConstants.BACKUP_PREVIEW_TIMEOUT_MS) }
        return previewResult
    }
}
