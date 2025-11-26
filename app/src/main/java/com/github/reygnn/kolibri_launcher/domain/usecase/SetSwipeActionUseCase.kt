package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import javax.inject.Inject

class SetSwipeActionUseCase @Inject constructor(
    private val repository: SwipeActionsRepository
) {
    suspend operator fun invoke(slot: SwipeSlot, componentName: String?) {
        repository.setSwipeAction(slot, componentName)
    }
}