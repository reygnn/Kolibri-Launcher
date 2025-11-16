package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import kotlinx.coroutines.flow.StateFlow

interface InstalledAppsStateRepository : Purgeable {
    val rawAppsFlow: StateFlow<List<AppInfo>>
    fun updateApps(newApps: List<AppInfo>)
    fun getCurrentApps(): List<AppInfo>
}