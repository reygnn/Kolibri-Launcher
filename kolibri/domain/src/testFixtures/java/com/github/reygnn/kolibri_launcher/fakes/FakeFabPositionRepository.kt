package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.FabPosition
import com.github.reygnn.kolibri_launcher.domain.repository.FabPositionRepository
import kotlinx.coroutines.flow.MutableStateFlow

class FakeFabPositionRepository : FabPositionRepository {
    private val flow = MutableStateFlow(FabPosition.DEFAULT)

    var currentPosition: FabPosition
        get() = flow.value
        set(value) {
            flow.value = value
        }

    override val fabPositionFlow = flow

    override suspend fun saveFabPosition(position: FabPosition) {
        currentPosition = position
    }

    override suspend fun purgeRepository() {
        currentPosition = FabPosition.DEFAULT
    }
}
