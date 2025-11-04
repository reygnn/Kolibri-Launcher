package com.github.reygnn.kolibri_launcher.domain

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.github.reygnn.kolibri_launcher.data.AppInfo
import com.github.reygnn.kolibri_launcher.domain.SortOrder
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.data.AppUsageRepository
import com.github.reygnn.kolibri_launcher.data.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.data.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.data.SettingsRepository
import com.github.reygnn.kolibri_launcher.di.DefaultDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetDrawerAppsUseCase @Inject constructor(
    private val appUsageManager: AppUsageRepository,
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val appVisibilityManager: HiddenAppsRepository,
    private val settingsManager: SettingsRepository,
    @param:DefaultDispatcher private val dispatcher: CoroutineDispatcher
) : GetDrawerAppsUseCaseRepository {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override val drawerApps: LiveData<List<AppInfo>> = combine(
        installedAppsStateRepository.rawAppsFlow,
        settingsManager.sortOrderFlow,
        appVisibilityManager.hiddenAppsFlow
    ) { rawApps, sortOrder, hiddenComponents ->

        try {
            Timber.Forest.d("[DATAFLOW] 6. UseCase combine block triggered. SortOrder: $sortOrder, Hidden components size: ${hiddenComponents.size}")

            // Filtere versteckte Apps
            val visibleApps = try {
                rawApps.filter { app ->
                    try {
                        !hiddenComponents.contains(app.componentName)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(
                            e,
                            "Error checking visibility for app: ${app.packageName}"
                        )
                        true // Im Fehlerfall: App sichtbar lassen
                    }
                }
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error filtering visible apps, using all apps")
                rawApps
            }

            // Sortiere basierend auf Einstellung
            val sortedApps = try {
                when (sortOrder) {
                    SortOrder.ALPHABETICAL -> {
                        try {
                            visibleApps.sortedBy { it.displayName.lowercase() }
                        } catch (e: Throwable) {
                            TimberWrapper.silentError(
                                e,
                                "Error in alphabetical sort, returning unsorted"
                            )
                            visibleApps
                        }
                    }

                    SortOrder.TIME_WEIGHTED_USAGE -> {
                        try {
                            appUsageManager.sortAppsByTimeWeightedUsage(visibleApps)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            TimberWrapper.silentError(
                                e,
                                "Error in time-weighted sort, falling back to alphabetical"
                            )
                            try {
                                visibleApps.sortedBy { it.displayName.lowercase() }
                            } catch (e2: Throwable) {
                                TimberWrapper.silentError(
                                    e2,
                                    "Error in fallback sort, returning unsorted"
                                )
                                visibleApps
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(
                    e,
                    "Critical error in sorting, returning visible apps unsorted"
                )
                visibleApps
            }

            Timber.Forest.d("[DATAFLOW] 7. UseCase is providing a new sorted list. Size: ${sortedApps.size}")
            sortedApps

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Critical error in combine block, returning empty list")
            emptyList()
        }
    }
        .catch { e ->
            TimberWrapper.silentError(e, "Critical error in drawerApps flow, emitting empty list")
            emit(emptyList())
        }
        .asLiveData(scope.coroutineContext)

    override fun purgeRepository() {
        // Für Tests - keine Implementierung nötig in Production
    }
}