package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class RequestLockUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val screenLockRepository: ScreenLockRepository
) {
    /**
     * Definiert das Ergebnis der Sperr-Anforderung.
     * Das ViewModel muss nur noch auf diese Ergebnisse reagieren.
     */
    sealed class Result {
        data object Success : Result() // Aktion wurde ausgeführt
        data object ErrorAccessibility : Result() // Funktion an, aber Accessibility aus
        data object ErrorDisabled : Result() // Funktion in Einstellungen aus
        data object ErrorGeneric : Result() // Unerwarteter Fehler
    }

    suspend operator fun invoke(): Result {
        return try {
            // 1. Prüfe die Einstellung
            if (settingsRepository.doubleTapToLockEnabledFlow.first()) {

                // 2. Prüfe die Verfügbarkeit
                if (screenLockRepository.isLockingAvailableFlow.value) {

                    // 3. Führe die Aktion aus
                    screenLockRepository.requestLock()
                    Result.Success
                } else {
                    // 4. Gebe klares Ergebnis zurück
                    Result.ErrorAccessibility
                }
            } else {
                // 5. Gebe klares Ergebnis zurück
                Result.ErrorDisabled
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in RequestLockUseCase")
            Result.ErrorGeneric
        }
    }
}