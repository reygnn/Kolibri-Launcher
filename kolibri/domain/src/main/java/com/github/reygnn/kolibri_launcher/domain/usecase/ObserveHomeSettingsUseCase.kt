package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.HomeSettings
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ObserveHomeSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<HomeSettings> {
        // Only sortOrder is consumed downstream (AppManagementDelegate reads
        // exactly HomeSettings.sortOrder; auto-launch is read live via
        // GetAutoLaunchSettingUseCase). Mapping the single sortOrderFlow drops
        // the extra autoLaunchAppFlow subscription, which — being an
        // un-deduped shared-store flow — re-ran this builder on every unrelated
        // settings write.
        return settingsRepository.sortOrderFlow.map { sortOrder ->
            HomeSettings(sortOrder = sortOrder)
        }
    }
}