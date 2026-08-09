package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo

/**
 * Applies custom names onto an app list as a pure, reactive re-derivation
 * (REACTIVE_APPLIST_SPEC RAL-1). The single name-application LOGIC, applied at
 * every source boundary (Site 1 = getInstalledApps family, Site 2 =
 * drawer/favorites over `rawAppsFlow`, Site 3 = recents point-read) so a rename
 * is a `combine` re-derivation instead of a full PackageManager re-enumeration.
 *
 * `displayName = names[packageName] ?: originalName`: a package with a custom
 * name shows it, everything else falls back to its original label. The result is
 * re-sorted by `displayName` — this reproduces the post-condition that the
 * enumeration used to guarantee (`processResolveInfoList` `sortedBy {
 * displayName.lowercase() }`), so consumers that do NOT re-sort themselves
 * (CustomNames / Settings screens) keep renamed apps at their custom-name
 * position rather than their original-name position.
 *
 * **Idempotent while the enumeration still bakes the name in:** with
 * `displayName` already equal to the custom name, `names[pkg] ?: originalName`
 * yields the same value and the sort is stable — so introducing this at every
 * site BEFORE the enumeration flip (migration step 2a) is a behavior-neutral
 * no-op overlay.
 */
fun applyNames(apps: List<AppInfo>, names: Map<String, String>): List<AppInfo> =
    apps.map { it.copy(displayName = names[it.packageName] ?: it.originalName) }
        .sortedBy { it.displayName.lowercase() }
