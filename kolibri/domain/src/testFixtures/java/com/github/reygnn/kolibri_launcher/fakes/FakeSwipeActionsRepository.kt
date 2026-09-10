package com.github.reygnn.kolibri_launcher.fakes

// TIMESTAMP 2025-12-03 19:50

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot

class FakeSwipeActionsRepository : SwipeActionsRepository {
    var swipeLeftApp: String? = null
    var swipeRightApp: String? = null

    override suspend fun setSwipeAction(slot: SwipeSlot, componentName: String?) {
        when (slot) {
            SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT -> swipeLeftApp = componentName
            SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT -> swipeRightApp = componentName
            SwipeSlot.NONE -> {}
        }
    }

    override suspend fun getSwipeActionComponent(slot: SwipeSlot): String? =
        when (slot) {
            SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT -> swipeLeftApp
            SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT -> swipeRightApp
            SwipeSlot.NONE -> null
        }

    override suspend fun reconcileSwipeActions(
        installedComponentNames: List<String>,
        isStillPresent: suspend (String) -> Boolean,
    ) {
        val installedSet = installedComponentNames.toSet()
        swipeLeftApp?.let { if (it !in installedSet && !isStillPresent(it)) swipeLeftApp = null }
        swipeRightApp?.let { if (it !in installedSet && !isStillPresent(it)) swipeRightApp = null }
    }

    override suspend fun purgeRepository() {
        swipeLeftApp = null
        swipeRightApp = null
    }
}