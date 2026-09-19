package com.github.reygnn.nyx_launcher.tapl

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.viewpager2.widget.ViewPager2
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.launcher.testing.BasePage
import com.github.reygnn.launcher.testing.awaitUntil
import com.github.reygnn.launcher.testing.longPressDrag
import com.github.reygnn.launcher.testing.tap
import org.hamcrest.Matcher

/**
 * The Nyx home grid: a [ViewPager2] of grid pages (R.id.home_pager), each page a
 * RecyclerView of `columns*rows` cells (index = y*columns + x, empty cells
 * included). Drags go through the real DragLayer/DragController touch pipeline —
 * that is the device-only seam these tests exist for.
 */
internal class NyxHome : BasePage() {
    override val anchorId: Int = R.id.home_pager
    override val name: String = "NyxHome"

    init { assertOnPage() }

    /**
     * Long-press-drags the cell at linear index [from] onto the cell at [to] on
     * the CURRENTLY shown page. Drops over the target cell's centre (→
     * DropTarget.Cell). Empty target → the item is placed there.
     */
    fun dragCellWithinPage(from: Int, to: Int) {
        onView(withId(R.id.home_root)).perform(dragCellsAction(from, to))
    }

    /**
     * Long-press-drags the cell at [from] straight up to the remove bar (the top
     * strip drop zone, which reaches y=0), where a home item is removed.
     */
    fun dragCellToRemoveBar(from: Int) {
        onView(withId(R.id.home_root)).perform(dragToRemoveAction(from))
    }

    /**
     * Long-press-drags the cell at [from] down onto the dock (R.id.dock), where a
     * drop lands in a DockSlot — moving the item from the grid into the dock.
     */
    fun dragCellToDock(from: Int) {
        onView(withId(R.id.home_root)).perform(dragToDockAction(from))
    }

    /**
     * Long-press-drags the dock item at [dockIndex] up onto grid cell [toCell] on
     * the current page — the reverse of [dragCellToDock]. A drop over the (empty)
     * grid cell moves the item from the dock back onto the grid
     * (DropTarget.Cell -> HomeViewModel.move).
     */
    fun dragDockItemToCell(dockIndex: Int, toCell: Int) {
        onView(withId(R.id.home_root)).perform(dragDockToCellAction(dockIndex, toCell))
    }

    /**
     * Long-press-drags the dock item at [fromIndex] onto the RIGHT outer edge of
     * the dock item at [ontoIndex] — a DockSlot insert AFTER it (reorder within the
     * dock). The outer edge, not the centre: the central band would be a
     * DockItem drop (folder create / add-to-folder), not a reorder.
     */
    fun dragDockItemAfter(fromIndex: Int, ontoIndex: Int) {
        onView(withId(R.id.home_root)).perform(dragDockReorderAction(fromIndex, ontoIndex))
    }

    /**
     * Taps the folder at grid cell [cellIndex] to open its overlay and waits for
     * the member list to render. Returns the folder sub-page.
     */
    fun openFolderAtCell(cellIndex: Int): NyxFolder {
        onView(withId(R.id.home_root)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
            override fun getDescription() = "tap folder at cell $cellIndex"
            override fun perform(uiController: UiController, view: View) {
                val pager = view.findViewById<ViewPager2>(R.id.home_pager)
                val c = cellCenterOnScreen(pager, pager.currentItem, cellIndex)
                    ?: error("No cell at index $cellIndex")
                tap(uiController, c.first, c.second)
            }
        })
        awaitUntil(timeoutMs = 5_000, describe = { "folder overlay members never appeared" }) {
            runCatching {
                onView(withId(R.id.folder_members)).check(matches(isDisplayed()))
            }.isSuccess
        }
        return NyxFolder()
    }

    /**
     * Opens the app drawer via the production swipe-up gesture (home_root's
     * GestureDispatchCore -> onSwipeUp -> showDrawer) and waits for the drawer
     * list to render. Returns the drawer sub-page.
     */
    fun openDrawer(): NyxDrawer {
        view(R.id.home_root).perform(
            GeneralSwipeAction(
                Swipe.FAST,
                GeneralLocation.CENTER,
                GeneralLocation.TOP_CENTER,
                Press.FINGER,
            )
        )
        awaitUntil(timeoutMs = 5_000, describe = { "drawer never opened after swipe-up" }) {
            runCatching {
                onView(withId(R.id.drawer_panel)).check(matches(isDisplayed()))
            }.isSuccess
        }
        return NyxDrawer()
    }

    /**
     * Long-press-drags the cell at [from] on the current page toward the right
     * edge (dwelling so the pager edge-advance fires), then onto cell [to] on the
     * next page — all in ONE continuous gesture (lifting would end the drag).
     *
     * Each page's grid occupies the same screen rectangle, so the drop point for
     * cell [to] on the next page equals cell [to]'s screen centre on the current
     * page — read up front, before the advance. [dwellMs] is the edge hover.
     */
    fun dragCellToNextPage(from: Int, to: Int, dwellMs: Long = 1500) {
        onView(withId(R.id.home_root)).perform(dragToNextPageAction(from, to, dwellMs))
    }

    /** Within-page: one straight leg from source to target cell centre. */
    private fun dragCellsAction(from: Int, to: Int): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
            override fun getDescription() = "long-press-drag cell $from -> $to (same page)"
            override fun perform(uiController: UiController, view: View) {
                val pager = view.findViewById<ViewPager2>(R.id.home_pager)
                val page = pager.currentItem
                val fromCenter = cellCenterOnScreen(pager, page, from)
                    ?: error("No cell at index $from on page $page")
                val toCenter = cellCenterOnScreen(pager, page, to)
                    ?: error("No cell at index $to on page $page")
                longPressDrag(uiController, fromCenter.first, fromCenter.second, listOf(toCenter))
            }
        }

    private fun dragToRemoveAction(from: Int): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
            override fun getDescription() = "long-press-drag cell $from -> remove bar (top edge)"
            override fun perform(uiController: UiController, view: View) {
                val pager = view.findViewById<ViewPager2>(R.id.home_pager)
                val page = pager.currentItem
                val fromCenter = cellCenterOnScreen(pager, page, from)
                    ?: error("No cell at index $from on page $page")
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                // Aim high into the top strip (the remove zone reaches y=0).
                val topY = (loc[1] + 24).toFloat()
                longPressDrag(
                    uiController,
                    fromCenter.first, fromCenter.second,
                    waypoints = listOf(fromCenter.first to topY),
                )
            }
        }

    private fun dragToDockAction(from: Int): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
            override fun getDescription() = "long-press-drag cell $from -> dock"
            override fun perform(uiController: UiController, view: View) {
                val pager = view.findViewById<ViewPager2>(R.id.home_pager)
                val page = pager.currentItem
                val fromCenter = cellCenterOnScreen(pager, page, from)
                    ?: error("No cell at index $from on page $page")
                val dock = view.findViewById<View>(R.id.dock)
                val loc = IntArray(2)
                dock.getLocationOnScreen(loc)
                val dockCenter = (loc[0] + dock.width / 2f) to (loc[1] + dock.height / 2f)
                longPressDrag(uiController, fromCenter.first, fromCenter.second, listOf(dockCenter))
            }
        }

    private fun dragDockToCellAction(dockIndex: Int, toCell: Int): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
            override fun getDescription() = "long-press-drag dock item $dockIndex -> grid cell $toCell"
            override fun perform(uiController: UiController, view: View) {
                val dock = view.findViewById<RecyclerView>(R.id.dock)
                val dockItem = dock.findViewHolderForAdapterPosition(dockIndex)?.itemView
                    ?: error("No dock item at index $dockIndex")
                val loc = IntArray(2)
                dockItem.getLocationOnScreen(loc)
                val start = (loc[0] + dockItem.width / 2f) to (loc[1] + dockItem.height / 2f)
                val pager = view.findViewById<ViewPager2>(R.id.home_pager)
                val toCenter = cellCenterOnScreen(pager, pager.currentItem, toCell)
                    ?: error("No cell at index $toCell")
                longPressDrag(uiController, start.first, start.second, listOf(toCenter))
            }
        }

    private fun dragDockReorderAction(fromIndex: Int, ontoIndex: Int): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
            override fun getDescription() = "long-press-drag dock item $fromIndex after dock item $ontoIndex"
            override fun perform(uiController: UiController, view: View) {
                val dock = view.findViewById<RecyclerView>(R.id.dock)
                fun dockItemView(i: Int) = dock.findViewHolderForAdapterPosition(i)?.itemView
                    ?: error("No dock item at index $i")
                val from = dockItemView(fromIndex)
                val onto = dockItemView(ontoIndex)
                val fromLoc = IntArray(2).also(from::getLocationOnScreen)
                val start = (fromLoc[0] + from.width / 2f) to (fromLoc[1] + from.height / 2f)
                val ontoLoc = IntArray(2).also(onto::getLocationOnScreen)
                // fx ~0.85 of the onto icon → outer band → DockSlot AFTER it (a reorder),
                // not the central DockItem band (which would create/enter a folder).
                val target = (ontoLoc[0] + onto.width * 0.85f) to (ontoLoc[1] + onto.height / 2f)
                longPressDrag(uiController, start.first, start.second, listOf(target))
            }
        }

    private fun dragToNextPageAction(from: Int, to: Int, dwellMs: Long): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
            override fun getDescription() = "long-press-drag cell $from -> next-page $to"
            override fun perform(uiController: UiController, view: View) {
                val pager = view.findViewById<ViewPager2>(R.id.home_pager)
                val page = pager.currentItem
                val fromCenter = cellCenterOnScreen(pager, page, from)
                    ?: error("No cell at index $from on page $page")
                // Same grid rectangle on every page → target coord read up front.
                val toCenter = cellCenterOnScreen(pager, page, to)
                    ?: error("No cell at index $to on page $page")
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val edgeX = (loc[0] + view.width - 4).toFloat()
                longPressDrag(
                    uiController,
                    fromCenter.first, fromCenter.second,
                    waypoints = listOf(edgeX to fromCenter.second, toCenter),
                    dwellMsPerWaypoint = dwellMs,
                )
            }
        }

    companion object {
        /**
         * Screen-space centre of the cell at linear [index] on [page], or null if
         * that view holder isn't laid out. Walks ViewPager2's internal RecyclerView
         * to the page's RecyclerView, then to the cell.
         */
        fun cellCenterOnScreen(pager: ViewPager2, page: Int, index: Int): Pair<Float, Float>? {
            val inner = pager.getChildAt(0) as? RecyclerView ?: return null
            val pageRecycler = inner.findViewHolderForAdapterPosition(page)?.itemView as? RecyclerView
                ?: return null
            val cell = pageRecycler.findViewHolderForAdapterPosition(index)?.itemView ?: return null
            val loc = IntArray(2)
            cell.getLocationOnScreen(loc)
            return (loc[0] + cell.width / 2f) to (loc[1] + cell.height / 2f)
        }
    }
}
