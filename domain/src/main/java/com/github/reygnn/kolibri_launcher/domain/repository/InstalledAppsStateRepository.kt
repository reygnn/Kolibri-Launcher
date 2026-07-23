package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import kotlinx.coroutines.flow.StateFlow

interface InstalledAppsStateRepository : Purgeable {
    val rawAppsFlow: StateFlow<List<AppInfo>>
    fun updateApps(newApps: List<AppInfo>)
    fun getCurrentApps(): List<AppInfo>

    /**
     * True once at least one non-empty app load has succeeded (i.e. the app
     * list is available, live or via the last-known-good cache). Distinguishes
     * the cold-start window before the first load — where [getCurrentApps]
     * legitimately returns an empty list — from a genuinely loaded state.
     * Callers that must not treat "not loaded yet" as "empty result" gate on
     * this instead of inferring load status from list emptiness.
     */
    fun hasLoadedApps(): Boolean
}