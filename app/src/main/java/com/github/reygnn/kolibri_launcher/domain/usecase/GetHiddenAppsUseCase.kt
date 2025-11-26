package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetHiddenAppsUseCase @Inject constructor(
    private val repository: HiddenAppsRepository
) {
    operator fun invoke(): Flow<Set<String>> {
        return repository.hiddenAppsFlow
    }
}