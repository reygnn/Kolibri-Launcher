package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.PreviewResult
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import javax.inject.Inject

class PreviewBackupUseCase @Inject constructor(
    private val backupRepository: BackupRepository
) {
    suspend operator fun invoke(uriString: String): PreviewResult {
        return backupRepository.previewBackup(uriString)
    }
}