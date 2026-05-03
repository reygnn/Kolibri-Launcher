package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import javax.inject.Inject

class UpdateHiddenAppsUseCase @Inject constructor(
    private val repository: HiddenAppsRepository
) {
    /**
     * Aktualisiert die Sichtbarkeit mehrerer Apps gleichzeitig.
     *
     * @param componentsToHide Set von ComponentNames, die versteckt werden sollen.
     * @param componentsToShow Set von ComponentNames, die wieder sichtbar werden sollen.
     */
    suspend operator fun invoke(componentsToHide: Set<String>, componentsToShow: Set<String>) {
        repository.updateComponentVisibilities(componentsToHide, componentsToShow)
    }
}