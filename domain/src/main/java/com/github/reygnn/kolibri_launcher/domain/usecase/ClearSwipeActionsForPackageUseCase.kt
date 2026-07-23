package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.KolibriLog
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Reconciles swipe-action assignments after an app is uninstalled.
 *
 * Invoked from the package-removed broadcast path (see `PackageUpdateReceiver`
 * in `:data`): when a package is *genuinely* removed — NOT during an app
 * update, which the receiver filters out via `EXTRA_REPLACING` — any swipe
 * slot pointing at that package is cleared.
 *
 * Keeping this reconciliation off the swipe-launch path is the whole point:
 * [HandleSwipeActionUseCase] no longer has to guess "uninstalled" vs. "not
 * loaded yet" (the cold-start ambiguity AUDIT-5 fought, TODO §24). It only
 * launches or no-ops; the persisted setting is mutated here, driven by the
 * real uninstall event.
 */
class ClearSwipeActionsForPackageUseCase @Inject constructor(
    private val swipeActionsRepository: SwipeActionsRepository
) {
    suspend operator fun invoke(packageName: String) {
        if (packageName.isBlank()) return

        // Log a stable hash, never the raw package name — raw package names are
        // PII (e.g. "com.gambling.bigwin") and must not reach logcat, not even
        // at WARN. Same rationale and formula as PackageUpdateReceiver, so the
        // two log lines stay correlatable for one uninstall.
        val packageHash = packageName.hashCode().toString(16)

        val slots = listOf(
            SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT to swipeActionsRepository.swipeLeftAppFlow,
            SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT to swipeActionsRepository.swipeRightAppFlow
        )

        for ((slot, flow) in slots) {
            if (belongsToPackage(flow.first(), packageName)) {
                KolibriLog.w("Clearing $slot swipe action: package (hash $packageHash) was removed.")
                swipeActionsRepository.setSwipeAction(slot, null)
            }
        }
    }

    /**
     * A stored swipe assignment is the flattened componentName
     * `"$packageName/$className"` (see [com.github.reygnn.kolibri_launcher.domain.model.AppInfo.componentName]).
     * The package part is everything before the first '/'. Exact equality on
     * that part avoids prefix false-positives ("com.foo" must not match
     * "com.foobar").
     */
    private fun belongsToPackage(componentName: String?, packageName: String): Boolean =
        componentName != null && componentName.substringBefore('/') == packageName
}
