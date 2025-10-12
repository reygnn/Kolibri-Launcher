/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/*
 * WHY NO .catch() ON INDIVIDUAL SOURCE FLOWS:
 *
 * The deprecation warning for .catch() on SharedFlow/StateFlow exists because these are "hot" flows
 * that never complete. The .catch() operator only catches exceptions that occur during the SUBSCRIPTION
 * phase (when you first collect the flow), NOT during emissions of new values.
 *
 * In our architecture, this makes .catch() on source flows unnecessary because:
 *
 * 1. STATE FLOWS HANDLE ERRORS AT THE SOURCE
 *    - InstalledAppsStateManager.updateApps() wraps updates in try-catch
 *    - If an error occurs while updating a StateFlow, the previous value is retained
 *    - StateFlow.value updates never throw exceptions to collectors
 *
 * 2. NO TRANSFORMATION BEFORE COMBINE
 *    - We're passing raw flows directly to combine()
 *    - There are no .map(), .filter(), or other operators that could fail
 *    - The flows emit plain values (List<AppInfo>, SortOrder, Set<ComponentName>)
 *
 * 3. COMPREHENSIVE ERROR HANDLING IN COMBINE
 *    - All transformation logic happens inside the combine block
 *    - Every operation (filtering, sorting) is wrapped in try-catch
 *    - If any operation fails, we gracefully degrade (return unsorted, return all apps, etc.)
 *
 * 4. FINAL SAFETY NET
 *    - The .catch() operator AFTER combine() catches any unexpected errors
 *    - This handles edge cases like coroutine cancellation issues
 *    - It ensures the LiveData always receives a valid list (even if empty)
 *
 * WHAT WOULD .catch() ON SOURCE FLOWS ACTUALLY DO?
 *    - Only catch errors during flow.collect() initialization
 *    - Since StateFlow.collect() never throws, .catch() would never trigger
 *    - It's literally dead code that serves no purpose
 *
 * WHEN WOULD YOU NEED .catch() ON SOURCE FLOWS?
 *    - If they were cold flows (Flow, not StateFlow/SharedFlow)
 *    - If you had transformations before combine (like .map { throw Exception() })
 *    - If the repository threw exceptions during value emission (bad practice)
 *
 * TL;DR: Hot flows + error handling at source + no transformations before combine = .catch() unnecessary
 */

@Singleton
class GetDrawerAppsUseCase @Inject constructor(
    private val appUsageManager: AppUsageRepository,
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val appVisibilityManager: AppVisibilityRepository,
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
            Timber.d("[DATAFLOW] 6. UseCase combine block triggered. SortOrder: $sortOrder, Hidden components size: ${hiddenComponents.size}")

            // Filtere versteckte Apps
            val visibleApps = try {
                rawApps.filter { app ->
                    try {
                        !hiddenComponents.contains(app.componentName)
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error checking visibility for app: ${app.packageName}")
                        true // Im Fehlerfall: App sichtbar lassen
                    }
                }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error filtering visible apps, using all apps")
                rawApps
            }

            // Sortiere basierend auf Einstellung
            val sortedApps = try {
                when (sortOrder) {
                    SortOrder.ALPHABETICAL -> {
                        try {
                            visibleApps.sortedBy { it.displayName.lowercase() }
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error in alphabetical sort, returning unsorted")
                            visibleApps
                        }
                    }
                    SortOrder.TIME_WEIGHTED_USAGE -> {
                        try {
                            appUsageManager.sortAppsByTimeWeightedUsage(visibleApps)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error in time-weighted sort, falling back to alphabetical")
                            try {
                                visibleApps.sortedBy { it.displayName.lowercase() }
                            } catch (e2: Exception) {
                                TimberWrapper.silentError(e2, "Error in fallback sort, returning unsorted")
                                visibleApps
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Critical error in sorting, returning visible apps unsorted")
                visibleApps
            }

            Timber.d("[DATAFLOW] 7. UseCase is providing a new sorted list. Size: ${sortedApps.size}")
            sortedApps

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
