package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.kolibri_launcher.domain.repository.FabPositionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetFabPositionUseCase @Inject constructor(
    private val repository: FabPositionRepository,
) {
    operator fun invoke(): Flow<FabPosition> = repository.fabPositionFlow
}
