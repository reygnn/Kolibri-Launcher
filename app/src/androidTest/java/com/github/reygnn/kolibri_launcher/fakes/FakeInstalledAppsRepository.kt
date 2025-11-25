package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hält den Flow der installierten Apps. Wird jetzt reaktiv vom FakeAppNamesRepository
 * aktualisiert, wann immer sich ein Name ändert.
 */
class FakeInstalledAppsRepository : InstalledAppsRepository, Purgeable {
    val appsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    override fun getInstalledApps(): Flow<List<AppInfo>> = appsFlow
    override suspend fun triggerAppsUpdate() {}
    override suspend fun purgeRepository() {
        appsFlow.value = emptyList()
    }
}