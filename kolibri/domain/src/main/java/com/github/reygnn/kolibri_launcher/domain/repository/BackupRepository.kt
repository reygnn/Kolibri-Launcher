package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.PreviewResult

interface BackupRepository {
    suspend fun exportToJson(): String
    suspend fun importFromJson(jsonString: String, options: ImportOptions): ImportResult
    suspend fun saveBackupToFile(uriString: String): Boolean
    suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult
    /** What the backup at [uriString] contains, or why it can't be imported (2a-7b). */
    suspend fun previewBackup(uriString: String): PreviewResult
}