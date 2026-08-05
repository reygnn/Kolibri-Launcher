/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.DefaultDispatcher
import com.github.reygnn.kolibri_launcher.core.KolibriLog
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Returns the most recently launched apps (pure recency, newest first),
 * capped at [limit]. Drives the swipe-down "recent apps" dialog.
 *
 * Composes three existing sources:
 *  - [AppUsageRepository.getRecentlyLaunchedPackages] for the recency order
 *    (the launcher's own recorded launches — no system usage-access needed),
 *  - [InstalledAppsStateRepository.getCurrentApps] to resolve packages to
 *    the currently installed [AppInfo] (dropping apps uninstalled since),
 *  - [HiddenAppsRepository] to exclude apps the user hid (consistent with the
 *    drawer). A hidden-flow read failure degrades to "show all", mirroring
 *    [GetDrawerAppsUseCase].
 *
 * We over-fetch package names (× 4) so that after dropping hidden and
 * uninstalled entries there are still up to [limit] left to show.
 */
class GetRecentAppsUseCase @Inject constructor(
    private val appUsageRepository: AppUsageRepository,
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val hiddenAppsRepository: HiddenAppsRepository,
    @param:DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(limit: Int = 8): List<AppInfo> = withContext(dispatcher) {
        if (limit <= 0) return@withContext emptyList()

        val recentPackages = appUsageRepository.getRecentlyLaunchedPackages(limit * 4)
        if (recentPackages.isEmpty()) return@withContext emptyList()

        val hidden = hiddenAppsRepository.hiddenAppsFlow
            .catch { e ->
                if (e is CancellationException) throw e
                KolibriLog.w(e, "hiddenAppsFlow error in recents - showing all")
                emit(emptySet())
            }
            .first()

        // First visible AppInfo per package (launcher apps typically expose
        // one launchable activity; putIfAbsent keeps that deterministic).
        val visibleByPackage = LinkedHashMap<String, AppInfo>()
        for (app in installedAppsStateRepository.getCurrentApps()) {
            if (app.componentName !in hidden) visibleByPackage.putIfAbsent(app.packageName, app)
        }

        recentPackages.mapNotNull { visibleByPackage[it] }.take(limit)
    }
}
