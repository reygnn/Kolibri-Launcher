package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.DefaultDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.sortedByDisplayName
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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

    // sortOrder drives the pipeline via flatMapLatest so that usageFlow is an
    // input ONLY in TIME_WEIGHTED_USAGE mode. In ALPHABETICAL mode the usage
    // order is irrelevant, so a per-launch usage tick must NOT re-run applyCustomNames +
    // filter + sort just to have the terminal distinctUntilChanged discard an
    // identical list (AUDIT-14 F2, bullet 1). Switching sort mode is a rare user
    // action; flatMapLatest cancels the previous inner flow and rebuilds, which
    // only re-reads the cheap DataStore flows once.
    @OptIn(ExperimentalCoroutinesApi::class)
    val drawerApps: Flow<List<AppInfo>> =
        settingsRepository.sortOrderFlow
            .catch { e ->
                if (e is CancellationException) throw e
                KolibriLog.w(e, "sortOrderFlow error - using ALPHABETICAL fallback")
                emit(SortOrder.ALPHABETICAL)
            }
            // Dedupe so a duplicate sortOrder emission does not cancel+restart the
            // inner pipeline. Correct independently of the source-flow dedupe.
            .distinctUntilChanged()
            .flatMapLatest { sortOrder ->
                if (sortOrder == SortOrder.TIME_WEIGHTED_USAGE) {
                    combine(
                        // Critical Flow: rawApps must not crash (no .catch());
                        // a failure propagates to the outer safety net below.
                        installedAppsStateRepository.rawAppsFlow,
                        hiddenAppsRepository.hiddenAppsFlow.catch { e ->
                            if (e is CancellationException) throw e
                            KolibriLog.w(e, "hiddenAppsFlow error - showing all apps")
                            emit(emptySet())
                        },
                        // Custom names folded in reactively (REACTIVE_APPLIST_SPEC
                        // Site 2): a rename re-derives here instead of re-enumerating.
                        customNamesRepository.customNamesFlow.catch { e ->
                            if (e is CancellationException) throw e
                            KolibriLog.w(e, "customNamesFlow error - using original names")
                            emit(emptyMap())
                        },
                        // Reactive usage snapshot (REACTIVE_APPLIST_SPEC): a launch
                        // re-derives the TIME_WEIGHTED_USAGE order reactively, and
                        // the emitted map is the already-parsed usage passed to the
                        // sort (read + parsed once here, not per re-sort). Collected
                        // ONLY in this mode.
                        appUsageRepository.usageSnapshotFlow.catch { e ->
                            if (e is CancellationException) throw e
                            KolibriLog.w(e, "usageSnapshotFlow error - proceeding without usage data")
                            emit(emptyMap<String, List<Long>>())
                        },
                    ) { rawApps, hiddenComponents, customNames, usageSnapshot ->
                        sortVisibleApps(rawApps, hiddenComponents, customNames, sortOrder, usageSnapshot)
                    }
                } else {
                    combine(
                        installedAppsStateRepository.rawAppsFlow,
                        hiddenAppsRepository.hiddenAppsFlow.catch { e ->
                            if (e is CancellationException) throw e
                            KolibriLog.w(e, "hiddenAppsFlow error - showing all apps")
                            emit(emptySet())
                        },
                        customNamesRepository.customNamesFlow.catch { e ->
                            if (e is CancellationException) throw e
                            KolibriLog.w(e, "customNamesFlow error - using original names")
                            emit(emptyMap())
                        },
                    ) { rawApps, hiddenComponents, customNames ->
                        sortVisibleApps(rawApps, hiddenComponents, customNames, sortOrder)
                    }
                }
            }
            // Collapse spurious re-emissions: any input change that leaves the
            // sorted list identical must not churn the adapter with an equal list.
            .distinctUntilChanged()
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

    // Name-apply + hidden-filter + sort, shared by both flatMapLatest branches so
    // the logic differs only by which upstream flows feed it. Byte-identical to the
    // former combine body — behaviour per sort mode is unchanged.
    private suspend fun sortVisibleApps(
        rawApps: List<AppInfo>,
        hiddenComponents: Set<String>,
        customNames: Map<String, String>,
        sortOrder: SortOrder,
        // Already-parsed usage snapshot, only populated in TIME_WEIGHTED_USAGE mode
        // (the ALPHABETICAL combine has no usage input). Passed straight to the
        // time-weighted sort so it does not re-read/re-parse the store.
        usageSnapshot: Map<String, List<Long>> = emptyMap(),
    ): List<AppInfo> {
        KolibriLog.d(
            "[DATAFLOW] 6. UseCase combine block triggered. " +
                "SortOrder: $sortOrder, Hidden components size: ${hiddenComponents.size}",
        )

        // Custom names applied over the veto-held raw list; since migration step 2b
        // the enumeration emits the original label, so this is the operative
        // name-application point. applyCustomNames returns input order (map-only,
        // RAL-4); sortVisibleApps sorts (alpha / time-weighted) below.
        val namedApps = applyCustomNames(rawApps, customNames)

        // Filter + alphabetischer Sort sind reine Operationen auf
        // String/Set/List — können nicht werfen. Frühere Throwable-Catches
        // hier entfernt (Throwable-Audit Pilot, §2): Programmierfehler
        // sollen via Rule 9 in DEBUG laut werden (über den Flow-catch
        // unten propagiert silentError und wirft).
        val visibleApps = namedApps.filter { app ->
            !hiddenComponents.contains(app.componentName)
        }

        val sortedApps = when (sortOrder) {
            SortOrder.ALPHABETICAL -> visibleApps.sortedByDisplayName()

            SortOrder.TIME_WEIGHTED_USAGE -> try {
                // Echte externe Abhängigkeit (UsageStats / System-Clock /
                // DataStore via Manager). Fallback auf Alphabetical ist
                // bewusster Graceful-Degrade. Dies ist der einzige
                // legitime Catch in diesem Block.
                appUsageRepository.sortAppsByTimeWeightedUsage(visibleApps, usageSnapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(
                    e,
                    "Error in time-weighted sort, falling back to alphabetical",
                )
                visibleApps.sortedByDisplayName()
            }
        }

        KolibriLog.d("[DATAFLOW] 7. UseCase is providing a new sorted list. Size: ${sortedApps.size}")
        return sortedApps
    }
}