package com.github.reygnn.kolibri_launcher.ui.customnames

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.filterByName
import com.github.reygnn.kolibri_launcher.domain.model.sortedByDisplayName

/**
 * The two lists the Custom Names screen renders from its master app list.
 */
data class CustomNamesViews(
    val displayedApps: List<AppInfo>,
    val appsWithCustomNames: List<AppInfo>,
)

/**
 * Pure shaping for the Custom Names screen: from [masterAppList], produce the
 * search-filtered display list and the "apps with a custom name" section, both
 * sorted by display name.
 *
 * The sort lives HERE, at the display boundary, not upstream (RAL-4 map-only:
 * the shared name-resolution no longer imposes an order, so every displaying
 * consumer owns its own). It is applied to **both** master-derived views, not
 * only [CustomNamesViews.displayedApps] — [CustomNamesViews.appsWithCustomNames]
 * derives from the same master list and would otherwise render in the master
 * list's (post-map-only: enumeration) order.
 *
 * Pure and total — extracted so it is unit-testable with an unsorted master list:
 * a test feeds an unsorted list straight in and asserts sorted output, so a
 * dropped sort fails the test independently of what the upstream helper does.
 */
fun buildCustomNamesViews(masterAppList: List<AppInfo>, query: String): CustomNamesViews =
    CustomNamesViews(
        displayedApps = masterAppList
            .filterByName(query, includeOriginalName = true)
            .sortedByDisplayName(),
        appsWithCustomNames = masterAppList
            .filter { it.originalName != it.displayName }
            .sortedByDisplayName(),
    )
