package com.github.reygnn.kolibri_launcher.tapl

import android.view.View
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
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

    /**
     * A real left-to-right horizontal swipe across the home wrapper
     * ([R.id.homeGestureRoot]) — the gesture HomeGestureLayout maps to
     * onSwipeRight -> viewModel.onSwipeFromLeftToRight (the SWIPE_FROM_LEFT_TO_RIGHT
     * slot). Swipe on empty home space (seed no favourites) so the favourites list
     * doesn't intercept the horizontal touch.
     *
     * Insets the endpoints to 15%..85% of the width rather than edge-to-edge: a
     * touch starting at x≈0 lands in the system back-gesture exclusion band on
     * gesture-nav devices, where SystemUI can pilfer the pointer before the
     * analyzer sees it (same edge-avoidance rationale as VISIBLE_CENTER for the
     * status bar, INSTRUMENTED_TESTING_NOTES §11). 70% of the width still clears
     * touch-slop and the FAST fling threshold comfortably.
     */
    fun swipeLeftToRight() {
        view(R.id.homeGestureRoot).perform(
            GeneralSwipeAction(
                Swipe.FAST,
                horizontalFraction(0.15f),
                horizontalFraction(0.85f),
                Press.FINGER,
            )
        )
    }

    /** Screen-space point at [fraction] of the view's width, vertically centred. */
    private fun horizontalFraction(fraction: Float) = CoordinatesProvider { v: View ->
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        floatArrayOf(loc[0] + v.width * fraction, loc[1] + v.height / 2f)
    }

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
