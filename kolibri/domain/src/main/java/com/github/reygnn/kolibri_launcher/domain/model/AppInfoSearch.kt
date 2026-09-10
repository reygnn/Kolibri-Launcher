package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Filters the receiver to the apps whose name matches [query], case-insensitively.
 *
 * Folds [query] once and matches the precomputed [AppInfo.displayNameLower]
 * (locale-invariant, AUDIT-14 Nit §208) rather than re-folding every displayName
 * per app on every keystroke via `contains(ignoreCase = true)` — AUDIT-15 F2 /
 * AUDIT-16 N2. A blank query returns the receiver unchanged.
 *
 * This is the single home for the display-name search predicate that used to be
 * copy-pasted across [AppSearchFilter] (the drawer) and the four settings-screen
 * ViewModels (Hidden / Onboarding / SwipeActions / CustomNames). Keeping one
 * pure function means a change like the fold-once fix lands in one place, not five.
 *
 * When [includeOriginalName] is true, an app also matches on its
 * [AppInfo.originalName]. That name has no precomputed lower key, so it is folded
 * per app — but only for the custom-named subset (where it differs from
 * displayName) and only when the displayName match already missed, so the common
 * case never pays for it. Only the Custom Names screen needs this; every other
 * caller matches displayName alone.
 */
fun List<AppInfo>.filterByName(
    query: String,
    includeOriginalName: Boolean = false,
): List<AppInfo> {
    if (query.isBlank()) return this
    val lowerQuery = query.lowercase()
    return filter { app ->
        app.displayNameLower.contains(lowerQuery) ||
            (includeOriginalName &&
                app.displayName != app.originalName &&
                app.originalName.lowercase().contains(lowerQuery))
    }
}
