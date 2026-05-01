package com.github.reygnn.kolibri_launcher.domain.usecase

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.di.DefaultDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
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
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val drawerApps: LiveData<List<AppInfo>> = combine(
        // Critical Flow: rawApps darf nicht crashen (kein .catch())
        installedAppsStateRepository.rawAppsFlow,

        // Non-critical Flows: Mit individuellen Fallbacks
        settingsManager.sortOrderFlow.catch { e ->
            Timber.w(e, "sortOrderFlow error - using ALPHABETICAL fallback")
            emit(SortOrder.ALPHABETICAL)
        },
        appVisibilityManager.hiddenAppsFlow.catch { e ->
            Timber.w(e, "hiddenAppsFlow error - showing all apps")
            emit(emptySet())
        },
    ) { rawApps, sortOrder, hiddenComponents ->
        Timber.d(
            "[DATAFLOW] 6. UseCase combine block triggered. " +
                "SortOrder: $sortOrder, Hidden components size: ${hiddenComponents.size}",
        )

        // Filter + alphabetischer Sort sind reine Operationen auf
        // String/Set/List — können nicht werfen. Frühere Throwable-Catches
        // hier entfernt (Throwable-Audit Pilot, §2): Programmierfehler
        // sollen via Rule 9 in DEBUG laut werden (über den Flow-catch
        // unten propagiert silentError und wirft).
        val visibleApps = rawApps.filter { app ->
            !hiddenComponents.contains(app.componentName)
        }

        val sortedApps = when (sortOrder) {
            SortOrder.ALPHABETICAL -> visibleApps.sortedBy { it.displayName.lowercase() }

            SortOrder.TIME_WEIGHTED_USAGE -> try {
                // Echte externe Abhängigkeit (UsageStats / System-Clock /
                // DataStore via Manager). Fallback auf Alphabetical ist
                // bewusster Graceful-Degrade. Dies ist der einzige
                // legitime Catch in diesem Block.
                appUsageManager.sortAppsByTimeWeightedUsage(visibleApps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(
                    e,
                    "Error in time-weighted sort, falling back to alphabetical",
                )
                visibleApps.sortedBy { it.displayName.lowercase() }
            }
        }

        Timber.d("[DATAFLOW] 7. UseCase is providing a new sorted list. Size: ${sortedApps.size}")
        sortedApps
    }
        .catch { e ->
            // Letztes Sicherheitsnetz: rawAppsFlow-Failures plus alles,
            // was die obigen Catches nicht abdecken (Programmierfehler).
            // silentError macht das in DEBUG laut, in RELEASE landet
            // emptyList() im LiveData.
            TimberWrapper.silentError(e, "Critical error in drawerApps flow, emitting empty list")
            emit(emptyList())
        }
        .asLiveData(scope.coroutineContext)
}