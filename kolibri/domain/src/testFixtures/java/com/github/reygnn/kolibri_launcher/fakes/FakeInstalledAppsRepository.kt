package com.github.reygnn.kolibri_launcher.fakes

// 2025-12-04 20:13

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppLoad
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Universelles Fake für Unit- und Instrumented-Tests.
 *
 * Verwendung:
 * - Einfach: `fake.installedApps = listOf(...)`
 * - Reaktiv: `fake.appsFlow.value = listOf(...)`
 */
class FakeInstalledAppsRepository : InstalledAppsRepository, Purgeable {

    val appsFlow = MutableStateFlow<List<AppInfo>>(emptyList())

    var triggerUpdateCallCount = 0
        private set

    /** Convenience-Property für einfache Zuweisung */
    var installedApps: List<AppInfo>
        get() = appsFlow.value
        set(value) { appsFlow.value = value }

    // Happy-path fake: always Loaded. The Failed branch is impl-only (like the
    // DataStore fakes) — tests that need Failed emit AppLoad.Failed directly.
    // Cached as one instance so getInstalledApps() returns the SAME flow across
    // calls (contract: same instance, mirrors the impl's stateIn).
    private val loadFlow: Flow<AppLoad> = appsFlow.map { AppLoad.Loaded(it) }

    override fun getInstalledApps(): Flow<AppLoad> = loadFlow

    override suspend fun triggerAppsUpdate() {
        triggerUpdateCallCount++
    }

    override suspend fun purgeRepository() {
        appsFlow.value = emptyList()
        triggerUpdateCallCount = 0
    }
}