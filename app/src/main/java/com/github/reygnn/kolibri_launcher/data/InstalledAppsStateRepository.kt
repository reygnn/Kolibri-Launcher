package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.AppInfo
import kotlinx.coroutines.flow.StateFlow

interface InstalledAppsStateRepository : Purgeable {
    val rawAppsFlow: StateFlow<List<AppInfo>>
    fun updateApps(newApps: List<AppInfo>)
    fun getCurrentApps(): List<AppInfo>
}