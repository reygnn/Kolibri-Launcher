package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import javax.inject.Inject

class SetTextShadowEnabledUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(isEnabled: Boolean) {
        settingsRepository.setTextShadowEnabled(isEnabled)
    }
}