package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.UsageExportRepository
import javax.inject.Inject

class ImportUsageFromFileUseCase @Inject constructor(
    private val repository: UsageExportRepository
) {
    /**
     * Leitet den Import an das Repository weiter.
     * Da UsageImportResult bereits ein Domain-Model (Sealed Class) ist,
     * geben wir es direkt zurück.
     */
    suspend operator fun invoke(uriString: String, mergeWithExisting: Boolean): UsageImportResult {
        return repository.loadFromFile(uriString, mergeWithExisting)
    }
}