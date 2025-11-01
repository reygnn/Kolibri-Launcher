package com.github.reygnn.kolibri_launcher

import android.net.Uri

interface BackupRepository {
    suspend fun exportToJson(): String
    suspend fun importFromJson(jsonString: String): ImportResult
    suspend fun saveBackupToFile(uri: Uri): Boolean
    suspend fun loadBackupFromFile(uri: Uri): ImportResult
}