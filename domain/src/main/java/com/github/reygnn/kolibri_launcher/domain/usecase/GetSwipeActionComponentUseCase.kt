package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import javax.inject.Inject

/**
 * Reads the CURRENT component assigned to [slot] straight from the store —
 * an authoritative fresh read, NOT the hot swipe flow's replay cache.
 *
 * The settings screen uses this to populate the chip on open. The hot
 * `swipeXxxAppFlow` (replay=1, WhileSubscribed) can serve a stale replayed value
 * on the first read after the assignment was changed while no subscriber was warm
 * — so a `.first()` on the flow would show the previously assigned app. Same root
 * cause and fix as the launch path ([HandleSwipeActionUseCase]).
 */
class GetSwipeActionComponentUseCase @Inject constructor(
    private val repository: SwipeActionsRepository
) {
    suspend operator fun invoke(slot: SwipeSlot): String? =
        repository.getSwipeActionComponent(slot)
}
