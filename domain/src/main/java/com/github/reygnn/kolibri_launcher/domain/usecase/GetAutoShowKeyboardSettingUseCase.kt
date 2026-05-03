package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class GetAutoShowKeyboardSettingUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    /**
     * Ruft die Einstellung 'autoShowKeyboard' einmalig ab.
     */
    suspend operator fun invoke(): Boolean {
        return try {
            settingsRepository.autoShowKeyboardFlow.first()
        } catch (e: Exception) {
            false
        }
    }
}