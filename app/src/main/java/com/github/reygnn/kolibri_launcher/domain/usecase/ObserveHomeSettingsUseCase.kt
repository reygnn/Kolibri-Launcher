package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.HomeSettings
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class ObserveHomeSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<HomeSettings> {
        return combine(
            settingsRepository.sortOrderFlow,
            settingsRepository.doubleTapToLockEnabledFlow,
            settingsRepository.swipeDownToNotificationsEnabledFlow,
            settingsRepository.autoLaunchAppFlow
        ) { sortOrder, doubleTap, swipeDown, autoLaunch ->
            HomeSettings(
                sortOrder = sortOrder,
                doubleTapToLockEnabled = doubleTap,
                swipeDownToNotificationsEnabled = swipeDown,
                autoLaunchApp = autoLaunch
            )
        }
    }
}