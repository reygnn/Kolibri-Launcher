package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import javax.inject.Inject

class ImportBackupUseCase @Inject constructor(
    private val backupRepository: BackupRepository
) {
    suspend operator fun invoke(uriString: String, options: ImportOptions): ImportResult {
        return backupRepository.loadBackupFromFile(uriString, options)
    }
}