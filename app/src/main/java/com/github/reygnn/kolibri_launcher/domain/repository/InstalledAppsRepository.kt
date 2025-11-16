package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import kotlinx.coroutines.flow.Flow

interface InstalledAppsRepository : Purgeable {
    fun getInstalledApps(): Flow<List<AppInfo>>
    suspend fun triggerAppsUpdate()
}