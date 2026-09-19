package com.github.reygnn.kolibri_launcher.tapl

import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.actionWithAssertions
import androidx.test.espresso.action.ViewActions.typeText
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.testing.BasePage

/**
 * The app-drawer overlay (R.id.app_drawer_root). Opened via [Launcher.openAppDrawer]
 * through the production event path; dismissed by a decisive downward fling that
 * DragToDismissCore commits.
 */
internal class AppDrawer(private val launcher: Launcher) : BasePage() {
    override val anchorId: Int = R.id.app_drawer_root
    override val name: String = "AppDrawer"

    init {
        // showDrawer runs a short slide-in and the ShowAppDrawer event hops a
        // Channel before it fires, so wait for the list, not assume it.
        waitForDisplayed(R.id.apps_recycler_view) {
            "AppDrawer overlay never became visible after openAppDrawer()"
        }
        assertOnPage()
    }

    /** Types [query] into the search field and returns the search sub-page. */
    fun search(query: String): Search {
        view(R.id.search_edit_text).perform(typeText(query))
        return Search(launcher)
    }

    /**
     * Fast swipe-down on the top-pinned list. The RecyclerView cannot consume
     * the downward delta (onDrawerShown scrolled it to position 0), so the
     * remainder reaches SwipeDownDismissLayout's DragToDismissCore and a FAST
     * swipe blows past its fling-dismiss velocity. Then waits for the overlay
     * to be gone and hands back to Home.
     *
     * VISIBLE_CENTER (not TOP_CENTER): y=0 is the system status-bar window;
     * TOP_CENTER touches would never reach the list. See
     * INSTRUMENTED_TESTING_NOTES §11.
     */
    fun swipeDownToDismiss(): Home {
        view(R.id.apps_recycler_view).perform(
            actionWithAssertions(
                GeneralSwipeAction(
                    Swipe.FAST,
                    GeneralLocation.VISIBLE_CENTER,
                    GeneralLocation.BOTTOM_CENTER,
                    Press.FINGER,
                )
            )
        )
        waitForGone(R.id.app_drawer_root) {
            "AppDrawer overlay never hid after fast swipe-down"
        }
        return launcher.home()
    }
}
