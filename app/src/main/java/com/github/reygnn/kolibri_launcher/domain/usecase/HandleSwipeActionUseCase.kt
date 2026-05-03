package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

class HandleSwipeActionUseCase @Inject constructor(
    private val swipeActionsRepository: SwipeActionsRepository,
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val recordAppLaunchUseCase: RecordAppLaunchUseCase,
    private val refreshAppsUseCase: RefreshAppsUseCase
) {
    /**
     * Definiert das Ergebnis: Entweder eine App zum Starten oder nichts.
     */
    sealed class Result {
        data class LaunchApp(val app: AppInfo) : Result()
        data object NoAction : Result()
    }

    suspend operator fun invoke(slot: SwipeSlot): Result {
        val componentName = if (slot == SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) {
            swipeActionsRepository.swipeLeftAppFlow.first()
        } else {
            swipeActionsRepository.swipeRightAppFlow.first()
        }

        if (componentName == null) {
            Timber.d("No app assigned to swipe $slot")
            return Result.NoAction
        }

        val appToLaunch = installedAppsStateRepository.getCurrentApps().find {
            it.componentName == componentName
        }

        return if (appToLaunch != null) {
            recordAppLaunchUseCase(appToLaunch)
            refreshAppsUseCase()
            Result.LaunchApp(appToLaunch)
        } else {
            Timber.w("App for swipe $slot not found: $componentName. Clearing setting.")
            swipeActionsRepository.setSwipeAction(slot, null)
            Result.NoAction
        }
    }
}