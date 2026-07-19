package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class GetTextShadowEnabledUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    /**
     * Ruft die Einstellung 'textShadowEnabled' einmalig ab.
     */
    suspend operator fun invoke(): Boolean {
        return try {
            settingsRepository.textShadowEnabledFlow.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true // Sicherer Fallback
        }
    }
}