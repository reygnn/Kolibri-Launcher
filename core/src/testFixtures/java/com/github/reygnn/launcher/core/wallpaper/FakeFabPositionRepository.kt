package com.github.reygnn.launcher.core.wallpaper

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
