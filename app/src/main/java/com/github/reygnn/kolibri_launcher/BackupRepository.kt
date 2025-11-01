package com.github.reygnn.kolibri_launcher

import android.net.Uri

interface BackupRepository {
    suspend fun exportToJson(): String
    suspend fun importFromJson(jsonString: String, options: ImportOptions): ImportResult
    suspend fun saveBackupToFile(uri: Uri): Boolean
    suspend fun loadBackupFromFile(uri: Uri, options: ImportOptions): ImportResult
    suspend fun previewBackup(uri: Uri): BackupPreview?
}