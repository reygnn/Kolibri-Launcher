package com.github.reygnn.kolibri_launcher.tapl

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.testing.BasePage

/**
 * Search-within-drawer sub-page. Anchor is the focused search field; the
 * drawer's own list (apps_recycler_view) doubles as the filtered results.
 *
 * Phase 1 provides only presence detection so the AppDrawer.search() -> Search
 * navigation compiles and is exercisable. The result assertions are Phase 2:
 * reading a RecyclerView's itemCount needs a small custom ViewAction, and
 * launchFirst() leaves the facade (fires an external app), so it wants its own
 * Intents-based test rather than a fluent return.
 */
internal class Search(private val launcher: Launcher) : BasePage() {
    override val anchorId: Int = R.id.search_edit_text
    override val name: String = "Search"

    init { assertOnPage() }

    /** Number of filtered results currently rendered. */
    fun resultCount(): Int =
        TODO("Phase 2: RecyclerView itemCount on apps_recycler_view via a ViewAction")

    /** Launches the first result. Leaves the facade (external app). */
    fun launchFirst(): Unit =
        TODO("Phase 2: RecyclerViewActions.actionOnItemAtPosition(0, click()); assert via Intents")
}
