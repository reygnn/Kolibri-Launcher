package com.github.reygnn.kolibri_launcher.ui.appdrawer

/**
 * PURE LOGIC — tells a genuine user keystroke apart from a `StateFlow` replay.
 *
 * The app-drawer search query is a `StateFlow`, and a `StateFlow` replays its
 * current value to every new collector. Because the drawer collects it inside
 * `repeatOnLifecycle(STARTED)`, the collector re-subscribes on every STARTED
 * transition — returning from the App Info screen, rotation, process restore —
 * so the *current* query is re-delivered without the user typing anything.
 *
 * That re-delivery must NOT trigger the single-match auto-launch: otherwise
 * reopening the drawer while a one-match query is left over (e.g. after
 * uninstalling one of two matches via the context menu) would launch an app the
 * user never tapped. Only a value that actually differs from the previously
 * seen one counts as a real keystroke.
 *
 * Extracted from [AppDrawerFragment] so the decision is JVM-testable
 * (CLAUDE.md Rule 10). The fragment owns one instance and feeds every
 * `appDrawerSearchQuery` emission through [onQueryEmitted].
 */
class SearchQueryChangeTracker {

    private var lastSeenQuery: String? = null

    /**
     * Records [query] and returns `true` iff it is a genuine user change — a
     * value different from the previously seen one. The first emission after
     * construction or [reset] is always treated as a replay and returns
     * `false`, because there is nothing to compare it against.
     */
    fun onQueryEmitted(query: String): Boolean {
        val isUserChange = lastSeenQuery != null && query != lastSeenQuery
        lastSeenQuery = query
        return isUserChange
    }

    /**
     * Clears the tracked value so the next [onQueryEmitted] is treated as a
     * replay. Called on view teardown.
     */
    fun reset() {
        lastSeenQuery = null
    }
}
