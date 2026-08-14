package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppLoad
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * UseCase zum Abrufen der installierten Apps.
 *
 * Unwrap-Adapter (INSTALLED_APPS_LOAD_SPEC §3 Belang A a): the repository now
 * yields a typed [AppLoad]; this use case exposes the plain `List<AppInfo>` its
 * ViewModel consumers expect, mapping [AppLoad.Failed] to an empty list. That
 * `Failed → emptyList` is a deliberate, STATELESS compat boundary — behaviourally
 * identical to the pre-AppLoad collapse, so no regression for these consumers.
 * The distinguishable failure lives only where it is acted on (the reconcile
 * pipeline in `ObserveInstalledAppsUseCase`), keeping the type change local.
 *
 * Custom names are folded in reactively via [applyCustomNames] (REACTIVE_APPLIST_SPEC
 * Site 1), so a rename re-derives through [CustomNamesRepository.customNamesFlow]
 * instead of forcing a re-enumeration. Since migration step 2b the enumeration
 * emits the original label, so [applyCustomNames] is the operative name-application
 * point for this Site-1 family.
 *
 * **The flow is UNSORTED (RAL-4 map-only): [applyCustomNames] no longer imposes a
 * display order.** The name says so — every collector sorts for itself (CustomNames
 * via `buildCustomNamesViews`, Hidden / Swipe via `sortedByDisplayName()`) or is
 * order-agnostic (Settings). Underneath, the enumeration still emits a deterministic
 * order (`InstalledAppsRepositoryImpl` sorts by original name), so this flow's own
 * `distinctUntilChanged` behaves identically — but collectors must NOT rely on that
 * for what they DISPLAY.
 */
class GetInstalledAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val customNamesRepository: CustomNamesRepository
) {
    // Computed getter (not a stored val): builds a fresh cold flow per access,
    // exactly as the former operator invoke() did — the combine arguments are
    // evaluated lazily on collection, not at construction.
    val unsortedInstalledAppsFlow: Flow<List<AppInfo>>
        get() = combine(
            installedAppsRepository.getInstalledApps().map { load ->
                when (load) {
                    is AppLoad.Loaded -> load.apps
                    is AppLoad.Failed -> emptyList()
                }
            },
            customNamesRepository.customNamesFlow
        ) { apps, names -> applyCustomNames(apps, names) }
            // Collapse redundant identical re-derivations: a customNamesFlow tick
            // that leaves the applied list unchanged (post step-2b the enumeration
            // no longer re-bakes the name, so only the reactive overlay changes it).
            .distinctUntilChanged()
}