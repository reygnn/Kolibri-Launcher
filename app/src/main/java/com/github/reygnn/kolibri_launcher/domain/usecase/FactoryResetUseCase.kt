package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ResetRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class FactoryResetUseCase @Inject constructor(
    private val resetRepository: ResetRepository,
    private val installedAppsRepository: InstalledAppsRepository
) {
    sealed class Result {
        data object Success : Result()
        data object PartialFailure : Result()
        data object Error : Result()
    }

    suspend operator fun invoke(includeUsageData: Boolean): Result {
        return try {
            val settingsSuccess = resetRepository.resetSettings()
            val userDataSuccess = resetRepository.resetUserData()

            val usageSuccess = if (includeUsageData) {
                resetRepository.resetAppUsageData()
            } else true

            if (settingsSuccess && userDataSuccess && usageSuccess) {
                // Trigger update only on success
                installedAppsRepository.triggerAppsUpdate()
                Result.Success
            } else {
                Result.PartialFailure
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error during factory reset use case")
            Result.Error
        }
    }
}