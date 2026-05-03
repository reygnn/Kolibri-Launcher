package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import javax.inject.Inject

class SetCustomNameUseCase @Inject constructor(
    private val repository: CustomNamesRepository
) {
    /**
     * Setzt einen benutzerdefinierten Namen.
     * Das Repository entscheidet intern: Wenn der String leer ist, wird der Eintrag entfernt.
     */
    suspend operator fun invoke(packageName: String, customName: String): Boolean {
        return repository.setCustomNameForPackage(packageName, customName)
    }
}