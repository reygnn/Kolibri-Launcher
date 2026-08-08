package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppLoad
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetOnboardingAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository
) {

    val onboardingAppsFlow: Flow<List<AppInfo>> =
        installedAppsRepository.getInstalledApps()
            .map { load ->
                when (load) {
                    // Behavior-preserving compat boundary (INSTALLED_APPS_LOAD_SPEC
                    // §3 Belang A b): a load failure shows an empty picker, exactly
                    // as before AppLoad — NOT the live error path (that is Belang D,
                    // decided separately). Deliberate, not an incidental collapse.
                    is AppLoad.Failed -> emptyList()
                    is AppLoad.Loaded -> load.apps.filter { app ->
                        // isNotBlank() on non-null data-class properties cannot throw;
                        // a programmer error would propagate to the Flow.catch below,
                        // which surfaces it via silentError in DEBUG.
                        app.packageName.isNotBlank() &&
                                app.displayName.isNotBlank()
                    }
                }
            }
            .catch { e ->
                if (e is CancellationException) throw e
                TimberWrapper.silentError(e, "Error in onboarding apps flow, emitting empty list")
                emit(emptyList())
            }

}