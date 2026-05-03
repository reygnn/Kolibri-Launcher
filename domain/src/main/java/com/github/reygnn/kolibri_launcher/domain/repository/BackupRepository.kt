package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult

interface BackupRepository {
    suspend fun exportToJson(): String
    suspend fun importFromJson(jsonString: String, options: ImportOptions): ImportResult
    suspend fun saveBackupToFile(uriString: String): Boolean
    suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult
    suspend fun previewBackup(uriString: String): BackupPreview?
}