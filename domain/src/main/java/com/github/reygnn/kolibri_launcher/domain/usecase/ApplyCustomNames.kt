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
 * (CustomNames / Settings screens and the Onboarding picker) keep renamed apps
 * at their custom-name position rather than their original-name position.
 *
 * **This is now the sole name-application point (migration step 2b landed):**
 * the enumeration emits the ORIGINAL label (`InstalledAppsRepositoryImpl`
 * `processResolveInfoList`), so `applyCustomNames` is what actually sets a custom
 * `displayName` — no longer the behavior-neutral no-op overlay it was during
 * step 2a, when the enumeration still baked the name in.
 *
 * == SPEC-DECISION RAL-1a — the terminal sort is DELIBERATELY bundled; do NOT split ==
 * The `.sortedBy { displayNameLower }` below is load-bearing ONLY for consumers
 * that do NOT re-sort themselves: Site 1's CustomNames / Settings VMs and the
 * Onboarding picker's main list. (The other two Site-1 consumers -- Hidden and
 * Swipe VMs -- re-sort their master list themselves, so for them it is dead work
 * too.) For the three self-sorting reactive consumers -- drawer
 * (GetDrawerAppsUseCase: alpha / time-weighted), favorites (GetFavoriteAppsUseCase:
 * savedOrder), recents (GetRecentAppsUseCase: recency) -- the sort here is DEAD
 * WORK: their own downstream sort discards this order. (Sole caveat: the favorites
 * error-fallback paths in FavoritesOrderRepositoryImpl return the list unsorted, so
 * on those degenerate paths this alphabetical order does surface -- not the steady
 * state.) That dead sort is KNOWN and ACCEPTED, not an oversight.
 *
 * Splitting into a sorted / unsorted pair to skip the dead work has been raised
 * three times -- deferred at AUDIT-14 F1 §5.3, then closed at AUDIT-15 F3 (the
 * 2026-08 review, same decision): the sort runs flowOn(Default) over ~50-200
 * strings (µs, off-Main, in the noise) -> value ~= 0, while a split fragments the
 * RAL-1 invariant "name-application at a source boundary yields the sorted
 * post-condition" -- the exact property that makes the reactive re-derivation a
 * drop-in for the enumeration (which set the label AND sorted atomically). A split
 * therefore needs a spec amendment (RAL-4), touches all call sites, and moves the
 * "must sort myself" knowledge to a weaker place (a future Site-N consumer that
 * forgets it gets a SILENT mis-order).
 *
 * If you arrived here from a "dead sort, optimize it away" observation: that is the
 * expected path, and the answer is no -- unless applyCustomNames is being split for some
 * OTHER reason anyway, in which case the dead-sort removal rides along free. The
 * three self-sorting call sites carry a thin pointer back to this block. Closed.
 */
fun applyCustomNames(apps: List<AppInfo>, names: Map<String, String>): List<AppInfo> =
    apps.map { it.copy(displayName = names[it.packageName] ?: it.originalName) }
        .sortedBy { it.displayNameLower }
