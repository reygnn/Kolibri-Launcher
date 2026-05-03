package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class RequestNotificationsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val screenLockRepository: ScreenLockRepository
) {
    /**
     * Definiert das Ergebnis der Anforderung.
     * (Wir können denselben Typ wie im anderen UseCase verwenden)
     */
    sealed class Result {
        data object Success : Result()
        data object ErrorAccessibility : Result()
        data object ErrorDisabled : Result()
        data object ErrorGeneric : Result()
    }

    suspend operator fun invoke(): Result {
        return try {
            // 1. Prüfe die Einstellung
            if (settingsRepository.swipeDownToNotificationsEnabledFlow.first()) {

                // 2. Prüfe die Verfügbarkeit
                if (screenLockRepository.isLockingAvailableFlow.value) {

                    // 3. Führe die Aktion aus
                    screenLockRepository.requestOpenNotifications()
                    Result.Success
                } else {
                    Result.ErrorAccessibility
                }
            } else {
                Result.ErrorDisabled
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in RequestNotificationsUseCase")
            Result.ErrorGeneric
        }
    }
}