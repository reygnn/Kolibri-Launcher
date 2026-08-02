package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.core.KolibriLog
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
        // NONE is never passed by GestureDelegate; handle it explicitly instead
        // of reading a slot for it.
        if (slot == SwipeSlot.NONE) return Result.NoAction

        // Read the CURRENT assignment straight from the store via
        // getSwipeActionComponent, NOT the hot-shared swipeXxxAppFlow: that
        // flow's replay cache can serve a stale value on the first swipe after
        // the assignment was changed in the Settings activity while Home held no
        // subscriber, launching the previously assigned app (fixed: authoritative
        // fresh read).
        val componentName = swipeActionsRepository.getSwipeActionComponent(slot)

        if (componentName == null) {
            KolibriLog.d("No app assigned to swipe $slot")
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
            // App not in the current list. We do NOT clear the assignment here:
            // getCurrentApps() can return a stale last-known-good cache and the
            // cold-start window can precede the first load, so "absent" is an
            // unreliable uninstall signal on the launch path (it caused the
            // AUDIT-5 data-loss). Orphan cleanup runs on the load path instead
            // (ObserveInstalledAppsUseCase reconciles swipe assignments against
            // the freshly loaded list after every successful load, TODO §24).
            // Here we only launch-or-no-op and never mutate persisted state.
            KolibriLog.w("App for swipe $slot not in current list: $componentName. No-op (cleanup runs on app load).")
            Result.NoAction
        }
    }
}