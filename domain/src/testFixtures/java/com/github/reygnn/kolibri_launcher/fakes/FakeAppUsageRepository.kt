package com.github.reygnn.kolibri_launcher.fakes

// TIMESTAMP 2025-12-06 09:10

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeAppUsageRepository : AppUsageRepository, Purgeable {
    val launchedPackages = mutableListOf<String>()

    // A monotonically-bumped version backs the change-signal: each usage
    // mutation advances it, so the mapped snapshot re-emits distinctly (the
    // version is folded into the values so even a repeat launch of the same
    // package yields a different map, keeping ticks distinct through
    // distinctUntilChanged downstream). The pseudo-timestamp is the version — the
    // fake's sort is identity, so real scoring is impl-only.
    private val usageVersion = MutableStateFlow(0)
    override val usageSnapshotFlow: Flow<Map<String, List<Long>>> =
        usageVersion.map { version ->
            launchedPackages.distinct().associateWith { listOf(version.toLong()) }
        }

    override suspend fun recordPackageLaunch(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        launchedPackages.add(packageName)
        usageVersion.value++
    }

    override suspend fun sortAppsByTimeWeightedUsage(
        apps: List<AppInfo>,
        usageSnapshot: Map<String, List<Long>>,
    ): List<AppInfo> = apps

    override suspend fun removeUsageDataForPackage(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        launchedPackages.removeAll { it == packageName }
        usageVersion.value++
    }

    override suspend fun hasUsageDataForPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return launchedPackages.contains(packageName)
    }

    override suspend fun getRecentlyLaunchedPackages(limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        // Launches are appended, so the most recent are at the end;
        // reverse + distinct keeps each package once at its most-recent
        // position, mirroring the impl's per-package recency ordering.
        return launchedPackages.asReversed().distinct().take(limit)
    }

    override suspend fun purgeRepository() {
        launchedPackages.clear()
        usageVersion.value++
    }
}