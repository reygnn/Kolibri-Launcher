package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import javax.inject.Inject

class CompleteOnboardingUseCase @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val settingsRepository: SettingsRepository
) {
    /**
     * Speichert die Favoriten und markiert optional das Onboarding als abgeschlossen.
     */
    suspend operator fun invoke(componentNames: List<String>, isInitialSetup: Boolean) {
        favoritesRepository.saveFavoriteComponents(componentNames)

        if (isInitialSetup) {
            settingsRepository.setOnboardingCompleted()
        }
    }
}