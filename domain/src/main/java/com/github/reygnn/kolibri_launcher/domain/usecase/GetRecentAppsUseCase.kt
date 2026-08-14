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
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
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
    private val customNamesRepository: CustomNamesRepository,
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

        // Custom names folded in over the last-known-good point-read
        // (REACTIVE_APPLIST_SPEC Site 3): getCurrentApps() keeps its
        // lastSuccessfulAppList fallback (no transient-empty window), applyCustomNames
        // resolves the label via a snapshot. Since migration step 2b the
        // enumeration emits the original label, so this is the operative
        // name-application point.
        val customNames = customNamesRepository.getAllCustomNames()
        // applyCustomNames' terminal sort does NOT determine the recents order
        // (recency does, below) — but it DID silently determine the per-package
        // representative for multi-activity packages (see pickRecentApps). That
        // dependency is now explicit there, so this call site no longer relies on
        // the helper's order at all.
        val currentApps = applyCustomNames(installedAppsStateRepository.getCurrentApps(), customNames)

        pickRecentApps(currentApps, hidden, recentPackages, limit)
    }
}

/**
 * Builds the recents list: order by [recentPackages] (recency), one visible
 * [AppInfo] per package, capped at [limit].
 *
 * When a package exposes several launchable activities, the **alphabetically
 * first** by display name represents it — an EXPLICIT tie-break. Previously this
 * fell out of iterating an alpha-sorted `currentApps` with `putIfAbsent`
 * (first-seen wins); under RAL-4 map-only `currentApps` is no longer sorted here,
 * so the choice is made directly rather than inherited from the shared helper's
 * order. `displayNameLower` is the same key the display sorts use, so the
 * representative stays consistent with what the app is shown as elsewhere.
 *
 * Pure and total — extracted so it is unit-testable with an unsorted
 * `currentApps`: a fixture whose enumeration-first activity differs from the
 * alpha-first one pins that the tie-break, not the input order, decides.
 */
fun pickRecentApps(
    currentApps: List<AppInfo>,
    hidden: Set<String>,
    recentPackages: List<String>,
    limit: Int,
): List<AppInfo> {
    val visibleByPackage = HashMap<String, AppInfo>()
    for (app in currentApps) {
        if (app.componentName in hidden) continue
        val existing = visibleByPackage[app.packageName]
        if (existing == null || app.displayNameLower < existing.displayNameLower) {
            visibleByPackage[app.packageName] = app
        }
    }
    return recentPackages.mapNotNull { visibleByPackage[it] }.take(limit)
}
