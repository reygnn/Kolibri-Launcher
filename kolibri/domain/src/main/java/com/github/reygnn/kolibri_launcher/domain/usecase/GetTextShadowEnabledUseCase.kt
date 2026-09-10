package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.firstOrDefault
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import javax.inject.Inject

class GetTextShadowEnabledUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    /**
     * Ruft die Einstellung 'textShadowEnabled' einmalig ab.
     */
    suspend operator fun invoke(): Boolean {
        // Safe fallback is true (shadow on) when the read fails.
        return settingsRepository.textShadowEnabledFlow.firstOrDefault(true)
    }
}