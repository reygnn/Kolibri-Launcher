package com.github.reygnn.kolibri_launcher.fakes

// TIMESTAMP 2025-12-04 20:19

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeInstalledAppsStateRepository : InstalledAppsStateRepository, Purgeable {
    private val stateFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    private var lastSuccessfulAppList: List<AppInfo> = emptyList()
    override val rawAppsFlow: StateFlow<List<AppInfo>> = stateFlow
    override fun updateApps(newApps: List<AppInfo>) {
        if (newApps.isNotEmpty()) {
            lastSuccessfulAppList = newApps
        }; stateFlow.value = newApps
    }

    override fun getCurrentApps(): List<AppInfo> {
        val currentApps = stateFlow.value; return if (currentApps.isNotEmpty()) {
            currentApps
        } else {
            lastSuccessfulAppList
        }
    }

    override fun hasLoadedApps(): Boolean =
        stateFlow.value.isNotEmpty() || lastSuccessfulAppList.isNotEmpty()

    override suspend fun purgeRepository() {
        stateFlow.value = emptyList(); lastSuccessfulAppList = emptyList()
    }
}