package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import javax.inject.Inject

class CheckAppUsageUseCase @Inject constructor(
    private val appUsageRepository: AppUsageRepository
) {
    /**
     * Prüft, ob für ein App-Paket Nutzungsdaten vorhanden sind.
     * Kapselt die Geschäftslogik sicher.
     */
    suspend operator fun invoke(packageName: String?): Boolean {
        if (packageName == null) {
            return false
        }
        return appUsageRepository.hasUsageDataForPackage(packageName)
    }
}