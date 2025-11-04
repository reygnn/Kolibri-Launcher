package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.data.AppInfo
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.data.InstalledAppsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetOnboardingAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository
) : GetOnboardingAppsUseCaseRepository {

    override val onboardingAppsFlow: Flow<List<AppInfo>> =
        installedAppsRepository.getInstalledApps()
            .map { apps ->
                try {
                    // Validierung: Entferne potentiell defekte Apps
                    apps.filter { app ->
                        try {
                            app.packageName.isNotBlank() &&
                                    app.displayName.isNotBlank()
                        } catch (e: Throwable) {
                            TimberWrapper.silentError(e, "Error validating app for onboarding")
                            false
                        }
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error filtering onboarding apps, returning all")
                    apps
                }
            }
            .catch { e ->
                TimberWrapper.silentError(e, "Error in onboarding apps flow, emitting empty list")
                emit(emptyList())
            }

    override fun purgeRepository() {
        // Für Tests - keine Implementierung nötig in Production
    }
}