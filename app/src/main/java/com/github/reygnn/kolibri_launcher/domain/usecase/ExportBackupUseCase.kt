package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import javax.inject.Inject

class ExportBackupUseCase @Inject constructor(
    private val backupRepository: BackupRepository
) {
    suspend operator fun invoke(uriString: String): Boolean {
        return backupRepository.saveBackupToFile(uriString)
    }
}