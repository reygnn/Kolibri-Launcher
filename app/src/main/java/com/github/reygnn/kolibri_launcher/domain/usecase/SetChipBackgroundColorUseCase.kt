package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import javax.inject.Inject

class SetChipBackgroundColorUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(color: Int) {
        settingsRepository.setChipBackgroundColor(color)
    }
}