package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ToggleSortOrderUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke() {
        val currentOrder = settingsRepository.sortOrderFlow.first()

        val newOrder = if (currentOrder == SortOrder.ALPHABETICAL) {
            SortOrder.TIME_WEIGHTED_USAGE
        } else {
            SortOrder.ALPHABETICAL
        }

        settingsRepository.setSortOrder(newOrder)
    }
}