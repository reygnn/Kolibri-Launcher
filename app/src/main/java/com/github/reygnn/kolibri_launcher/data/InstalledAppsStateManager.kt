package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central state manager for installed applications with fail-safe caching.
 *
 * This singleton acts as the **final state holder** in the app loading pipeline,
 * sitting between `InstalledAppsManager` (data source) and the UI layer (consumers).
 * It provides a fail-safe mechanism to ensure the UI always has access to valid
 * app data, even in error scenarios or during transitions.
 *
 * **Architectural Role:**
 * - **Single Source of Truth**: Holds the canonical app list state for the entire app
 * - **Decoupling Layer**: Separates data loading logic from UI observation
 * - **Resilience Buffer**: Prevents empty states from reaching UI during transient errors
 *
 * **Data Flow Position:**
 * ```
 * InstalledAppsManager (loads) → InstalledAppsStateManager (holds) → UI (observes)
 * ```
 *
 * **Key Responsibilities:**
 * - Maintains the current list of installed apps via [kotlinx.coroutines.flow.StateFlow]
 * - Caches the last successful non-empty app list as fallback
 * - Provides thread-safe access to app data
 * - Handles errors gracefully without crashing the app
 * - Ensures UI never receives empty list unless legitimately no apps exist
 *
 * **Thread-Safety:**
 * The manager uses [kotlinx.coroutines.flow.MutableStateFlow] for reactive updates and a `@Volatile`
 * cache variable to ensure visibility across threads. All operations are
 * protected with comprehensive try-catch blocks catching [Throwable].
 *
 * **Error Handling & Fail-Safe Logic:**
 * If an error occurs during updates or retrieval, the manager falls back to
 * [lastSuccessfulAppList] to prevent UI disruptions. This ensures:
 * - UI never shows empty state due to transient errors
 * - App remains functional even if data loading fails temporarily
 * - Stale data is preferable to no data for user experience
 *
 * **Why Not Combine with InstalledAppsManager?**
 * Separation provides:
 * - Clear responsibility boundaries (loading vs. holding)
 * - Easier testing of state management logic
 * - Flexibility to swap data sources without affecting state logic
 * - Fail-safe layer independent of loading complexity
 *
 * @property rawAppsFlow Observable flow of the current app list for reactive UI updates
 * @property lastSuccessfulAppList Volatile cache of last known good state for error recovery
 *
 * @see InstalledAppsManager for the data loading and processing logic
 */
@Singleton
class InstalledAppsStateManager @Inject constructor() : InstalledAppsStateRepository {

    private val _rawAppsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    override val rawAppsFlow: StateFlow<List<AppInfo>> = _rawAppsFlow

    @Volatile  // Nur das hier von paranoid
    private var lastSuccessfulAppList: List<AppInfo> = emptyList()

    override fun updateApps(newApps: List<AppInfo>) {
        try {
            Timber.Forest.d("[DATAFLOW] 5. StateManager is being updated. Size: ${newApps.size}")

            if (newApps.isNotEmpty()) {
                lastSuccessfulAppList = newApps
            }

            _rawAppsFlow.value = newApps

        } catch (e: Throwable) {  // Throwable statt Exception
            TimberWrapper.silentError(e, "Error updating apps in StateManager, keeping previous state")
        }
    }

    override fun getCurrentApps(): List<AppInfo> {
        return try {
            _rawAppsFlow.value.ifEmpty {
                Timber.Forest.d("Returning cached list with ${lastSuccessfulAppList.size} apps")
                lastSuccessfulAppList
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error getting current apps, returning cached list")
            lastSuccessfulAppList
        }
    }

    override suspend fun purgeRepository() {
        // NICHTS TUN!
        // Der StateManager hält nur den aktuellen State der installierten Apps.
        // Diese Daten kommen vom System-PackageManager und sollten nicht geleert werden.
        // Ein Reload wird über InstalledAppsRepository.triggerAppsUpdate() ausgelöst.

        Timber.Forest.d("InstalledAppsStateManager: purge requested, but state manager does not purge system data")
    }
}