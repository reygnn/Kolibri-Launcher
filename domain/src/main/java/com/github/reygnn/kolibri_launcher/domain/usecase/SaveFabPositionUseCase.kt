package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.FabPosition
import com.github.reygnn.kolibri_launcher.domain.repository.FabPositionRepository
import javax.inject.Inject

class SaveFabPositionUseCase @Inject constructor(
    private val repository: FabPositionRepository,
) {
    suspend operator fun invoke(position: FabPosition) {
        repository.saveFabPosition(position)
    }
}
