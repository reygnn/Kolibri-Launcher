package com.github.reygnn.kolibri_launcher.fakes

// TIMESTAMP 2025-12-03 19:50

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSwipeActionsRepository : SwipeActionsRepository {
    private val leftFlow = MutableStateFlow<String?>(null)
    private val rightFlow = MutableStateFlow<String?>(null)

    var swipeLeftApp: String?
        get() = leftFlow.value
        set(value) {
            leftFlow.value = value
        }

    var swipeRightApp: String?
        get() = rightFlow.value
        set(value) {
            rightFlow.value = value
        }

    override val swipeLeftAppFlow = leftFlow
    override val swipeRightAppFlow = rightFlow

    override suspend fun setSwipeAction(slot: SwipeSlot, componentName: String?) {
        when (slot) {
            SwipeSlot.SWIPE_FROM_LEFT -> swipeLeftApp = componentName
            SwipeSlot.SWIPE_FROM_RIGHT -> swipeRightApp = componentName
            SwipeSlot.NONE -> {}
        }
    }

    override suspend fun purgeRepository() {
        swipeLeftApp = null
        swipeRightApp = null
    }
}
