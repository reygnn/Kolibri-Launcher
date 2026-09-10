package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository

class FakeBackupRepository : BackupRepository {
    var exportSuccess = true
    var shouldThrowOnExport = false
    var shouldThrowOnImport = false
    var shouldThrowOnPreview = false

    var importResult: ImportResult = ImportResult.Success(0, 0, emptySet())
    var previewResult: BackupPreview? = null
    var lastOptions: ImportOptions? = null

    override suspend fun exportToJson(): String {
        if (shouldThrowOnExport) {
            throw Exception("Simulated export exception")
        }
        return """{"version":"1.0.0","timestamp":0,"settings":{}}"""
    }

    override suspend fun importFromJson(jsonString: String, options: ImportOptions): ImportResult {
        lastOptions = options
        if (shouldThrowOnImport) {
            throw Exception("Simulated import exception")
        }
        return importResult
    }

    override suspend fun saveBackupToFile(uriString: String): Boolean {
        if (shouldThrowOnExport) {
            throw Exception("Simulated export exception")
        }
        return exportSuccess
    }

    override suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult {
        lastOptions = options
        if (shouldThrowOnImport) {
            throw Exception("Simulated import exception")
        }
        return importResult
    }

    override suspend fun previewBackup(uriString: String): BackupPreview? {
        if (shouldThrowOnPreview) {
            throw Exception("Simulated preview exception")
        }
        return previewResult
    }
}