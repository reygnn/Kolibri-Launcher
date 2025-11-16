package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class ResetAppUsageUseCase @Inject constructor(
    private val appUsageRepository: AppUsageRepository
) {
    suspend operator fun invoke(app: AppInfo) {
        try {
            appUsageRepository.removeUsageDataForPackage(app.packageName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error removing usage data")
            throw e
        }
    }
}