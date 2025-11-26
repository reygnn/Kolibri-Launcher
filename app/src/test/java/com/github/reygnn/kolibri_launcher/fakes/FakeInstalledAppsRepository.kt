package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Universelles Fake für Unit- und Instrumented-Tests.
 *
 * Verwendung:
 * - Einfach: `fake.installedApps = listOf(...)`
 * - Reaktiv: `fake.appsFlow.value = listOf(...)`
 */
class FakeInstalledAppsRepository : InstalledAppsRepository, Purgeable {

    val appsFlow = MutableStateFlow<List<AppInfo>>(emptyList())

    /** Convenience-Property für einfache Zuweisung */
    var installedApps: List<AppInfo>
        get() = appsFlow.value
        set(value) { appsFlow.value = value }

    override fun getInstalledApps(): Flow<List<AppInfo>> = appsFlow

    override suspend fun triggerAppsUpdate() {
        // No-op für Tests - State wird direkt gesetzt
    }

    override suspend fun purgeRepository() {
        appsFlow.value = emptyList()
    }
}