package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import javax.inject.Inject

class RemoveCustomNameUseCase @Inject constructor(
    private val repository: CustomNamesRepository
) {
    suspend operator fun invoke(packageName: String): Boolean {
        return repository.removeCustomNameForPackage(packageName)
    }
}