package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.StateFlow

interface InstalledAppsStateRepository : Purgeable {
    val rawAppsFlow: StateFlow<List<AppInfo>>
    fun updateApps(newApps: List<AppInfo>)
    fun getCurrentApps(): List<AppInfo>
}
