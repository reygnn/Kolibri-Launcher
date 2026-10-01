package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.ImportOptions
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.model.PreviewResult

/**
 * Nyx's backup to and from a document the user picked (SAF), over the shared engine
 * (2b-3a). Same operations as Kolibri's `BackupRepository`; the URI is passed as a string so
 * the domain stays Android-free.
 */
interface BackupRepository {
    /** Writes a backup into [uriString]; false on failure, and then the document is removed again (U3). */
    suspend fun saveBackupToFile(uriString: String): Boolean

    suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult

    /** What the backup at [uriString] contains, or why it can't be imported (2b-3b). */
    suspend fun previewBackup(uriString: String): PreviewResult
}
