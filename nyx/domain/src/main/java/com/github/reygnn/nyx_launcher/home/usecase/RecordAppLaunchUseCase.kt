package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.home.repository.AppUsageRepository
import javax.inject.Inject

/**
 * Record that an app was launched, so the drawer's usage sort can rank it (mirrors kolibri's
 * RecordAppLaunchUseCase). Called from the single launch choke-point; usage is aggregated per
 * package. A blank package is a no-op (handled by the repository).
 */
class RecordAppLaunchUseCase @Inject constructor(
    private val appUsageRepository: AppUsageRepository,
) {
    suspend operator fun invoke(packageName: String?) = appUsageRepository.recordPackageLaunch(packageName)
}
