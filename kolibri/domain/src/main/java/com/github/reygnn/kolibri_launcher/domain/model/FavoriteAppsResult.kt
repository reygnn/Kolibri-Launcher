package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.launcher.core.AppInfo

/**
 * @property apps The favorites (or the top-N fallback) to render on home.
 * @property isFallback True when [apps] is the top-N alphabetical fallback shown
 * because the user has set no favorites (never true when favorites exist).
 * @property missingComponents The `componentName`s within [apps] whose target app
 * is no longer installed — the Windows-shortcut "missing" state. Such favorites
 * are kept (never auto-pruned) and rendered greyed; interacting with one offers
 * to remove it. Always empty on the fallback path. A synthesized "missing" entry
 * carries a best-effort label (its custom name if set, else the package name).
 */
data class FavoriteAppsResult(
    val apps: List<AppInfo>,
    val isFallback: Boolean,
    val missingComponents: Set<String> = emptySet(),
)
