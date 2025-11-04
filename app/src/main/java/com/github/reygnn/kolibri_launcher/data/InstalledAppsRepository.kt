package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.Purgeable
import kotlinx.coroutines.flow.Flow

interface InstalledAppsRepository : Purgeable {
    fun getInstalledApps(): Flow<List<AppInfo>>
    suspend fun triggerAppsUpdate()
}