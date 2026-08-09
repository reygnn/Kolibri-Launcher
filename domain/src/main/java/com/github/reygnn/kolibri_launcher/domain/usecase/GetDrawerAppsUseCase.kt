package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.DefaultDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import com.github.reygnn.kolibri_launcher.core.KolibriLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetDrawerAppsUseCase @Inject constructor(
    private val appUsageRepository: AppUsageRepository,
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val hiddenAppsRepository: HiddenAppsRepository,
    private val settingsRepository: SettingsRepository,
    private val customNamesRepository: CustomNamesRepository,
    @param:DefaultDispatcher private val dispatcher: CoroutineDispatcher
) {

    val drawerApps: Flow<List<AppInfo>> = combine(
        // Critical Flow: rawApps darf nicht crashen (kein .catch())
        // Stays on the post-veto keep-last-good rawAppsFlow (REACTIVE_APPLIST_SPEC
        // Site 2, NOT getInstalledApps) so the transient-empty-drawer flicker
        // cannot return.
        installedAppsStateRepository.rawAppsFlow,

        // Non-critical Flows: Mit individuellen Fallbacks
        settingsRepository.sortOrderFlow.catch { e ->
            if (e is CancellationException) throw e
            KolibriLog.w(e, "sortOrderFlow error - using ALPHABETICAL fallback")
            emit(SortOrder.ALPHABETICAL)
        },
        hiddenAppsRepository.hiddenAppsFlow.catch { e ->
            if (e is CancellationException) throw e
            KolibriLog.w(e, "hiddenAppsFlow error - showing all apps")
            emit(emptySet())
        },
        // Custom names folded in reactively (REACTIVE_APPLIST_SPEC Site 2): a
        // rename re-derives here instead of re-enumerating. Non-critical → on a
        // read failure fall back to original names.
        customNamesRepository.customNamesFlow.catch { e ->
            if (e is CancellationException) throw e
            KolibriLog.w(e, "customNamesFlow error - using original names")
            emit(emptyMap())
        },
    ) { rawApps, sortOrder, hiddenComponents, customNames ->
        KolibriLog.d(
            "[DATAFLOW] 6. UseCase combine block triggered. " +
                "SortOrder: $sortOrder, Hidden components size: ${hiddenComponents.size}",
        )

        // Custom names applied over the veto-held raw list (no-op overlay while
        // the enumeration still bakes the name in — migration step 2a).
        val namedApps = applyNames(rawApps, customNames)

        // Filter + alphabetischer Sort sind reine Operationen auf
        // String/Set/List — können nicht werfen. Frühere Throwable-Catches
        // hier entfernt (Throwable-Audit Pilot, §2): Programmierfehler
        // sollen via Rule 9 in DEBUG laut werden (über den Flow-catch
        // unten propagiert silentError und wirft).
        val visibleApps = namedApps.filter { app ->
            !hiddenComponents.contains(app.componentName)
        }

        val sortedApps = when (sortOrder) {
            SortOrder.ALPHABETICAL -> visibleApps.sortedBy { it.displayName.lowercase() }

            SortOrder.TIME_WEIGHTED_USAGE -> try {
                // Echte externe Abhängigkeit (UsageStats / System-Clock /
                // DataStore via Manager). Fallback auf Alphabetical ist
                // bewusster Graceful-Degrade. Dies ist der einzige
                // legitime Catch in diesem Block.
                appUsageRepository.sortAppsByTimeWeightedUsage(visibleApps)
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

        KolibriLog.d("[DATAFLOW] 7. UseCase is providing a new sorted list. Size: ${sortedApps.size}")
        sortedApps
    }
        .catch { e ->
            if (e is CancellationException) throw e
            // Letztes Sicherheitsnetz: rawAppsFlow-Failures plus alles,
            // was die obigen Catches nicht abdecken (Programmierfehler).
            // silentError macht das in DEBUG laut, in RELEASE landet
            // emptyList() im Flow.
            TimberWrapper.silentError(e, "Critical error in drawerApps flow, emitting empty list")
            emit(emptyList())
        }
        .flowOn(dispatcher)
}