package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.isValidUsageTimestamp
import com.github.reygnn.launcher.core.timeWeightedUsageScore
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [AppUsageRepository] test double. Backed by a StateFlow so [usageSnapshotFlow]
 * re-emits after every record; [scoreApps]/[recordPackageLaunch] use the SAME shared `:core`
 * math as the impl, so the contract means the same on both sides. [nowProvider] is injectable
 * so tests can pin timestamps deterministically.
 */
class FakeAppUsageRepository(
    initial: Map<String, List<Long>> = emptyMap(),
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) : AppUsageRepository {

    private val state = MutableStateFlow(initial)
    private val writeMutex = Mutex()

    val current: Map<String, List<Long>> get() = state.value

    override val usageSnapshotFlow: Flow<Map<String, List<Long>>> = state

    override suspend fun recordPackageLaunch(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        writeMutex.withLock {
            val now = nowProvider()
            val existing = state.value[packageName].orEmpty().filter { isValidUsageTimestamp(it, now) }
            state.value = state.value + (packageName to (existing + now).sortedDescending())
        }
    }

    override fun scoreApps(
        apps: List<LauncherApp>,
        usageSnapshot: Map<String, List<Long>>,
    ): Map<ComponentKey, Double> {
        val now = nowProvider()
        return apps.associate { app ->
            val timestamps = usageSnapshot[app.key.packageName]?.filter { isValidUsageTimestamp(it, now) }.orEmpty()
            app.key to timeWeightedUsageScore(timestamps, now)
        }
    }

    override suspend fun purgeRepository() {
        writeMutex.withLock { state.value = emptyMap() }
    }
}
