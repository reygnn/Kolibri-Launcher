package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.AppLoad
import kotlinx.coroutines.flow.Flow

interface InstalledAppsRepository : Purgeable {
    /**
     * Hot stream of the installed-app list as a typed [AppLoad]: [AppLoad.Loaded]
     * (possibly empty) or [AppLoad.Failed] on a load error — never an empty list
     * masquerading as a failure (INSTALLED_APPS_LOAD_SPEC IAL-INV-1).
     */
    fun getInstalledApps(): Flow<AppLoad>
    suspend fun triggerAppsUpdate()
}