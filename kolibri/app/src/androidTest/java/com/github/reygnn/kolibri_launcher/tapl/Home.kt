package com.github.reygnn.kolibri_launcher.tapl

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.testing.BasePage

/**
 * The home surface: favourites list (R.id.favoritesRecyclerView) plus the clock/date/
 * battery chrome. Note the overlay model — when the drawer is open, Home sits
 * BEHIND it and stays displayed; "drawer closed" is asserted on AppDrawer, not
 * here (see AppDrawer.swipeDownToDismiss).
 *
 * openAppDrawer() lives on [Launcher], not here, because opening drives
 * viewModel.onFlingUp() and the facade owns the scenario/viewModel handle.
 */
internal class Home : BasePage() {
    override val anchorId: Int = R.id.favoritesRecyclerView
    override val name: String = "Home"

    init { assertOnPage() }

    // ── Phase 2 (stubs) ─────────────────────────────────────────────────────

    /** Rendered favourite count (real RecyclerView measure pass). */
    fun favoritesCount(): Int =
        TODO("Phase 2: read the adapter's itemCount via a RecyclerView ViewAction")

    /** Long-press favourite [index] -> app context menu. */
    fun longPressFavorite(index: Int): AppContextMenu =
        TODO("Phase 2: RecyclerViewActions.actionOnItemAtPosition(index, longClick())")
}

/** Placeholder page returned by long-press; fleshed out in Phase 2. */
internal class AppContextMenu
