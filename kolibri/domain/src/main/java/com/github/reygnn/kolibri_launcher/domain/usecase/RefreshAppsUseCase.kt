package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.launcher.core.InstalledAppsRepository
import javax.inject.Inject

class RefreshAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository
) {
    suspend operator fun invoke() {
        installedAppsRepository.triggerAppsUpdate()
    }
}