package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class GetDoubleTapClipboardSettingUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    /**
     * Reads the 'double-tap performs a clipboard action' setting once.
     * Falls back to the safe answer (disabled) on any read failure, so a
     * broken DataStore can never silently enable a clipboard-reading gesture.
     */
    suspend operator fun invoke(): Boolean {
        return try {
            settingsRepository.doubleTapClipboardEnabledFlow.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
