package com.github.reygnn.launcher.core.installedapps

import com.github.reygnn.launcher.core.AppEnumerator
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.AppLoad
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Shared test fakes for the installed-apps subsystem, migrated into `:core`
 * testFixtures so both apps consume ONE copy (Contract-Triple-Regel,
 * MONOREPO_MERGE_SPEC §7 / §4). Neutral `com.github.reygnn.launcher.core`.
 */

/**
 * Happy-path fake loader: always [AppLoad.Loaded]. The Failed branch is impl-only
 * (like the DataStore fakes) — a test that needs Failed emits it directly.
 * `getInstalledApps()` returns the SAME flow across calls (mirrors the impl's
 * stateIn instance contract).
 */
class FakeInstalledAppsRepository : InstalledAppsRepository {

    val appsFlow = MutableStateFlow<List<AppInfo>>(emptyList())

    var triggerUpdateCallCount = 0
        private set

    var installedApps: List<AppInfo>
        get() = appsFlow.value
        set(value) { appsFlow.value = value }

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

/** In-memory holder fake with the same last-good fallback as the impl (SIA-INV-5). */
class FakeInstalledAppsStateRepository : InstalledAppsStateRepository {
    private val stateFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    private var lastSuccessfulAppList: List<AppInfo> = emptyList()

    override val rawAppsFlow = stateFlow

    override fun updateApps(newApps: List<AppInfo>) {
        if (newApps.isNotEmpty()) lastSuccessfulAppList = newApps
        stateFlow.value = newApps
    }

    override fun getCurrentApps(): List<AppInfo> =
        stateFlow.value.ifEmpty { lastSuccessfulAppList }

    override suspend fun purgeRepository() {
        stateFlow.value = emptyList()
        lastSuccessfulAppList = emptyList()
    }
}

/**
 * Fake for the new [AppEnumerator] port. Set [result] for the success list, or set
 * [throwable] to make [enumerate] throw (drives the motor's `AppLoad.Failed`
 * branch and the per-app fail-closed reconcile tests). Empty list is a legitimate
 * success (§9.2).
 */
class FakeAppEnumerator(
    var result: List<AppInfo> = emptyList(),
    var throwable: Throwable? = null,
) : AppEnumerator {
    var enumerateCallCount = 0
        private set

    override suspend fun enumerate(): List<AppInfo> {
        enumerateCallCount++
        throwable?.let { throw it }
        return result
    }
}
