package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.Flow

interface InstalledAppsRepository : Purgeable {
    fun getInstalledApps(): Flow<List<AppInfo>>
    suspend fun triggerAppsUpdate()
}