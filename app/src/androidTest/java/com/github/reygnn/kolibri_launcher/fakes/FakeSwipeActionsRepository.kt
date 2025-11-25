package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSwipeActionsRepository : SwipeActionsRepository, Purgeable {
    private val swipeLeftState = MutableStateFlow<String?>(null)
    private val swipeRightState = MutableStateFlow<String?>(null)

    override val swipeLeftAppFlow: Flow<String?> = swipeLeftState
    override val swipeRightAppFlow: Flow<String?> = swipeRightState

    override suspend fun setSwipeAction(slot: SwipeSlot, componentName: String?) {
        when (slot) {
            SwipeSlot.LEFT -> swipeLeftState.value = componentName
            SwipeSlot.RIGHT -> swipeRightState.value = componentName
            SwipeSlot.NONE -> {
                // Ignore, wie im echten Manager
            }
        }
    }

    override suspend fun purgeRepository() {
        swipeLeftState.value = null
        swipeRightState.value = null
    }
}