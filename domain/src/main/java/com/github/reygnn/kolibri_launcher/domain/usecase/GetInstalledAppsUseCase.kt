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
 * Custom names are folded in reactively via [applyNames] (REACTIVE_APPLIST_SPEC
 * Site 1), so a rename re-derives through [CustomNamesRepository.customNamesFlow]
 * instead of forcing a re-enumeration. Since migration step 2b the enumeration
 * emits the original label, so [applyNames] is the operative name-application
 * point for this Site-1 family.
 */
class GetInstalledAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val customNamesRepository: CustomNamesRepository
) {
    operator fun invoke(): Flow<List<AppInfo>> =
        combine(
            installedAppsRepository.getInstalledApps().map { load ->
                when (load) {
                    is AppLoad.Loaded -> load.apps
                    is AppLoad.Failed -> emptyList()
                }
            },
            customNamesRepository.customNamesFlow
        ) { apps, names -> applyNames(apps, names) }
            // Collapse redundant identical re-derivations: a customNamesFlow tick
            // that leaves the applied list unchanged (post step-2b the enumeration
            // no longer re-bakes the name, so only the reactive overlay changes it).
            .distinctUntilChanged()
}