package com.github.reygnn.nyx_launcher.home.model

/**
 * PURE LOGIC — the drawer-search predicate.
 *
 * Filters the receiver to the apps whose [display name][displayName] matches
 * [query], case-insensitively. Folds [query] once per call and matches against
 * each app's display name; a blank query returns the receiver unchanged.
 *
 * Mirrors kolibri's `List<AppInfo>.filterByName`: the single pure home for the
 * search predicate, kept JVM-testable outside any Android-runtime class
 * (CLAUDE.md Rule 10). Nyx drawer lists are small, so — unlike kolibri's hot
 * path — no precomputed lower-case key is needed; the per-app fold is fine.
 */
fun List<LauncherApp>.filterByName(query: String): List<LauncherApp> {
    if (query.isBlank()) return this
    val lowerQuery = query.lowercase()
    return filter { it.displayName.lowercase().contains(lowerQuery) }
}
