package com.github.reygnn.nyx_launcher.home.model

/**
 * PURE LOGIC — the drawer-search decision engine (mirrors kolibri's
 * `AppSearchFilter`). Given the full flat app list and the current query, it
 * decides whether to show a filtered list or to auto-launch the single match.
 *
 * Search **flattens folders** (DRAWER_FOLDERS_SPEC §10 D-3): the caller feeds
 * the flat drawer-app list here whenever the query is non-blank, so a query
 * reaches apps tucked inside folders too; a blank query bypasses this engine and
 * the folder view is shown instead.
 *
 * Auto-launch fires only when the query is non-blank, exactly one app matches,
 * AND the user enabled it. The replay-vs-keystroke guard that stops a StateFlow
 * replay from auto-launching is a separate concern owned by
 * `SearchQueryChangeTracker` at the fragment — this engine is stateless and
 * decides purely from its inputs.
 */
object DrawerAppSearch {

    fun filterAndDecide(
        allApps: List<LauncherApp>,
        query: String,
        isAutoLaunchEnabled: Boolean,
    ): DrawerSearchResult {
        val filtered = allApps.filterByName(query)
        if (query.isNotBlank() && filtered.size == 1 && isAutoLaunchEnabled) {
            return DrawerSearchResult.AutoLaunch(filtered.first())
        }
        return DrawerSearchResult.ShowList(filtered)
    }
}

sealed interface DrawerSearchResult {
    /** Show this filtered list in the drawer (flat, no folders). */
    data class ShowList(val apps: List<LauncherApp>) : DrawerSearchResult

    /** Launch this app immediately and dismiss the keyboard. */
    data class AutoLaunch(val app: LauncherApp) : DrawerSearchResult
}
