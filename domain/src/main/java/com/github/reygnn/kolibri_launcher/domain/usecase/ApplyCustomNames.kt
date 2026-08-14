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
 * name shows it, everything else falls back to its original label.
 *
 * **Returns the input order — this function does NOT sort (RAL-4, map-only).**
 * Name resolution is a shared concern; display ORDER is not. Every displaying
 * consumer sorts its own list at its display boundary:
 *  - drawer: alphabetical / time-weighted (`GetDrawerAppsUseCase`);
 *  - favorites: `savedOrder` + alpha remainder (`FavoritesOrderRepositoryImpl`);
 *  - recents: recency, with an explicit per-package representative tie-break
 *    (`pickRecentApps`);
 *  - CustomNames / Onboarding: `sortedByDisplayName()` (`buildCustomNamesViews`,
 *    `toOnboardingPicker`);
 *  - Hidden / Swipe: their own `sortedByDisplayName()` on the master list.
 * Settings is order-agnostic. The former terminal `.sortedByDisplayName()` was
 * migration scaffolding — it reproduced the enumeration's sorted post-condition so
 * consumers didn't have to change during the RAL-1 rollout; with every consumer
 * now owning its order, that scaffolding is removed. The five-version RAL-4
 * argument, the JMH `mapOnly` measurement and the two multi-agent reviews live in
 * git history + `APPLIST_SORT_SPLIT_SPEC.md`.
 *
 * The detection invariant is unaffected: `originalName != displayName` still
 * identifies renamed apps — that is name resolution, not order.
 */
fun applyCustomNames(apps: List<AppInfo>, customNames: Map<String, String>): List<AppInfo> =
    apps.map { it.copy(displayName = customNames[it.packageName] ?: it.originalName) }
