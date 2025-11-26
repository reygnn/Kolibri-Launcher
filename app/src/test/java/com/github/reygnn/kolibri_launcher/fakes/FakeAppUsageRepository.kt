package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable

class FakeAppUsageRepository : AppUsageRepository, Purgeable {
    val launchedPackages = mutableListOf<String>()
    override suspend fun recordPackageLaunch(packageName: String?) {
        packageName?.let { launchedPackages.add(it) }
    }

    override suspend fun sortAppsByTimeWeightedUsage(apps: List<AppInfo>): List<AppInfo> = apps
    override suspend fun removeUsageDataForPackage(packageName: String?) {
        launchedPackages.removeAll { it == packageName }
    }

    override suspend fun hasUsageDataForPackage(packageName: String?): Boolean =
        launchedPackages.contains(packageName)

    override suspend fun purgeRepository() {
        launchedPackages.clear()
    }
}