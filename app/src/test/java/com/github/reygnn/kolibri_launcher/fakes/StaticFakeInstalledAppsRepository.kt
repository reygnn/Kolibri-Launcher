package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class StaticFakeInstalledAppsRepository : InstalledAppsRepository {
    var installedApps = listOf<AppInfo>()

    override fun getInstalledApps(): Flow<List<AppInfo>> {
        return flowOf(installedApps)
    }

    override suspend fun triggerAppsUpdate() {}

    override suspend fun purgeRepository() {}
}