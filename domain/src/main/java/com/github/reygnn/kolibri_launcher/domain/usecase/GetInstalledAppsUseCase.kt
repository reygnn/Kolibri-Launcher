package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppLoad
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.flow.Flow
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
 */
class GetInstalledAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository
) {
    operator fun invoke(): Flow<List<AppInfo>> =
        installedAppsRepository.getInstalledApps().map { load ->
            when (load) {
                is AppLoad.Loaded -> load.apps
                is AppLoad.Failed -> emptyList()
            }
        }
}