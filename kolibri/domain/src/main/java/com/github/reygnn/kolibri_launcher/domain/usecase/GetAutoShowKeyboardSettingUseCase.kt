package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.firstOrDefault
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import javax.inject.Inject

class GetAutoShowKeyboardSettingUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    /**
     * Ruft die Einstellung 'autoShowKeyboard' einmalig ab.
     */
    suspend operator fun invoke(): Boolean {
        return settingsRepository.autoShowKeyboardFlow.firstOrDefault(false)
    }
}