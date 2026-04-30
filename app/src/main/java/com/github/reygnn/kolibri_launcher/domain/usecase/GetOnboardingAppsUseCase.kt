package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
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
            .map { apps ->
                // String.isNotBlank() auf Non-Null-Properties einer
                // Datenklasse — kann nicht werfen. Programmierfehler
                // (sollten nie auftreten) propagieren zum Flow-catch
                // unten, der in DEBUG via silentError laut wird.
                apps.filter { app ->
                    app.packageName.isNotBlank() &&
                            app.displayName.isNotBlank()
                }
            }
            .catch { e ->
                TimberWrapper.silentError(e, "Error in onboarding apps flow, emitting empty list")
                emit(emptyList())
            }

}