package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.launcher.core.KolibriLog
import javax.inject.Inject

class HandleSwipeActionUseCase @Inject constructor(
    private val swipeActionsRepository: SwipeActionsRepository,
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val recordAppLaunchUseCase: RecordAppLaunchUseCase
) {
    /**
     * Definiert das Ergebnis: Entweder eine App zum Starten oder nichts.
     *
     * [AppNotInstalled] is the lazy-validation signal (Windows-shortcut model):
     * the slot holds an assignment whose target is not in the current app list,
     * so the gesture is validated at trigger time and the caller surfaces a
     * "no longer installed" toast — the assignment is NEVER auto-cleared here
     * (a stale/cold-start list is an unreliable uninstall signal). Cleanup is a
     * user action (reassign / clear the slot in Settings).
     */
    sealed class Result {
        data class LaunchApp(val app: AppInfo) : Result()
        data object NoAction : Result()
        data class AppNotInstalled(val slot: SwipeSlot, val componentName: String) : Result()
    }

    suspend operator fun invoke(slot: SwipeSlot): Result {
        // NONE is never passed by GestureDelegate; handle it explicitly instead
        // of reading a slot for it.
        if (slot == SwipeSlot.NONE) return Result.NoAction

        // Read the CURRENT assignment straight from the store via
        // getSwipeActionComponent (authoritative fresh read): a slot changed in
        // the Settings activity must take effect on the very next swipe, so this
        // never reads through a cache that could launch the previously assigned
        // app.
        val componentName = swipeActionsRepository.getSwipeActionComponent(slot)

        if (componentName == null) {
            KolibriLog.d("No app assigned to swipe $slot")
            return Result.NoAction
        }

        val currentApps = installedAppsStateRepository.getCurrentApps()
        val appToLaunch = currentApps.find {
            it.componentName == componentName
        }

        return when {
            appToLaunch != null -> {
                // Recording the launch ticks AppUsageRepository.usageFlow → the drawer
                // re-sorts reactively (REACTIVE_APPLIST_SPEC). No refreshAppsUseCase():
                // a swipe-launch must not force a full re-enumeration.
                recordAppLaunchUseCase(appToLaunch)
                Result.LaunchApp(appToLaunch)
            }

            currentApps.isEmpty() -> {
                // Empty list = cold-start window before the first load (or a
                // transient failure). "Absent" is an unreliable uninstall signal
                // here — the app may well be installed — so stay SILENT (no toast),
                // exactly as before. This is the branch that closed the AUDIT-5
                // cold-start data-loss; it must never mutate persisted state.
                KolibriLog.d("Swipe $slot: app list not loaded yet, treating as no-op ($componentName)")
                Result.NoAction
            }

            else -> {
                // The list IS loaded (non-empty) and the assigned component is
                // genuinely absent → lazily validate (Windows-shortcut model). The
                // caller surfaces a "no longer installed" toast; the assignment is
                // NEVER auto-cleared here (there is no load-path reconcile any more),
                // the user reassigns/clears the slot in Settings.
                KolibriLog.w("App for swipe $slot not in current list: $componentName. Lazy-validated (no auto-clear).")
                Result.AppNotInstalled(slot, componentName)
            }
        }
    }
}