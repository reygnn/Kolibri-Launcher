package com.github.reygnn.kolibri_launcher.fakes

// TIMESTAMP 2025-12-06 09:10

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable

class FakeAppUsageRepository : AppUsageRepository, Purgeable {
    val launchedPackages = mutableListOf<String>()

    override suspend fun recordPackageLaunch(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        launchedPackages.add(packageName)
    }

    override suspend fun sortAppsByTimeWeightedUsage(apps: List<AppInfo>): List<AppInfo> = apps

    override suspend fun removeUsageDataForPackage(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        launchedPackages.removeAll { it == packageName }
    }

    override suspend fun hasUsageDataForPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return launchedPackages.contains(packageName)
    }

    override suspend fun purgeRepository() {
        launchedPackages.clear()
    }
}