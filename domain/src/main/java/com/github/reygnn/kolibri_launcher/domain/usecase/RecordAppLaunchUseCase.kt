package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import javax.inject.Inject

class RecordAppLaunchUseCase @Inject constructor(
    private val appUsageRepository: AppUsageRepository
) {
    suspend operator fun invoke(app: AppInfo) {
        appUsageRepository.recordPackageLaunch(app.packageName)
    }
}