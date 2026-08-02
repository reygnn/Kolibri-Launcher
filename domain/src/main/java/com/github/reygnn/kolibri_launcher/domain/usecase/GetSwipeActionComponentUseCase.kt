package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import javax.inject.Inject

/**
 * Reads the CURRENT component assigned to [slot] straight from the store — an
 * authoritative fresh read.
 *
 * The settings screen uses this to populate the chip on open: it must reflect
 * the value currently stored, so reopening the screen right after a change never
 * shows the previously assigned app. Same authoritative-read contract as the
 * launch path ([HandleSwipeActionUseCase]).
 */
class GetSwipeActionComponentUseCase @Inject constructor(
    private val repository: SwipeActionsRepository
) {
    suspend operator fun invoke(slot: SwipeSlot): String? =
        repository.getSwipeActionComponent(slot)
}
