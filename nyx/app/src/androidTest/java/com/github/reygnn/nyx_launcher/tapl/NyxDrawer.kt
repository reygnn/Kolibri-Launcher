package com.github.reygnn.nyx_launcher.tapl

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers.hasMinimumChildCount
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.home.drag.DragLayer
import com.github.reygnn.launcher.testing.BasePage
import com.github.reygnn.launcher.testing.awaitUntil
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf

/**
 * The app drawer overlay (R.id.drawer_panel is its RecyclerView). Opened via
 * [NyxHome.openDrawer]. A drawer icon long-press arms a DragPayload.NewApp drag
 * AND shows the context menu (Launcher3-style); releasing keeps the menu.
 *
 * Uses the context-menu path ("Aufs Home" / R.string.menu_add_to_home) to add an
 * app to home — the deterministic of the two production routes (the other being
 * a continuous drag onto the add-to-home bar). Both end at the same
 * addToHome -> HomeViewModel.place; the menu route avoids gesture-timing flake.
 */
internal class NyxDrawer : BasePage() {
    override val anchorId: Int = R.id.drawer_panel
    override val name: String = "NyxDrawer"

    init { assertOnPage() }

    /**
     * Types [query] into the drawer search box (which flattens folders and filters
     * to matching APP entries — so row 0 is guaranteed an app, not a seeded drawer
     * folder), long-presses row 0 to raise its context menu, then taps "Add to
     * home" — placing the app on the home grid (onDrop -> addToHome).
     */
    fun addAppToHomeViaMenu(query: String) {
        searchForApps(query)
        onView(withId(R.id.drawer_panel))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, longClick()))

        // The context menu row carries R.string.menu_add_to_home. Match on the
        // DISPLAYED one — the add-to-home *bar* shares the string but stays
        // INVISIBLE when there is no active drag.
        awaitUntil(timeoutMs = 5_000, describe = { "'Add to home' menu row never appeared" }) {
            runCatching {
                onView(allOf(withText(R.string.menu_add_to_home), isDisplayed()))
                    .check(matches(isDisplayed()))
            }.isSuccess
        }
        onView(allOf(withText(R.string.menu_add_to_home), isDisplayed())).perform(click())
    }

    /**
     * The OTHER production route (Weg 2): search, then drag the first app row up
     * onto the add-to-home bar with a real continuous touch — long-press to arm,
     * move past slop to promote to a drag, carry it into the top add-to-home strip,
     * drop (onDrop -> addToHome). The arm→promote transition has no visible signal,
     * so this awaits the read-only [DragLayer.isDragArmed] hook instead of guessing
     * a hold duration (the timing that made this route flaky before the hook).
     */
    fun dragAppToHomeBar(query: String) {
        searchForApps(query)
        onView(withId(R.id.home_root)).perform(ArmedDrawerDragToHomeBar())
    }

    /** Enters [query] and waits until the filtered drawer list has rendered a row. */
    private fun searchForApps(query: String) {
        // replaceText, not typeText: it sets the field content directly (no
        // KeyCharacterMap key synthesis), so a non-ASCII query still enters cleanly
        // while still firing the search field's text-changed listener.
        onView(withId(R.id.search_edit_text)).perform(replaceText(query))
        closeSoftKeyboard()

        // The filtered list loads async; wait until it has a row before acting,
        // or RecyclerViewActions / the drag would target nothing.
        awaitUntil(timeoutMs = 10_000, describe = { "drawer search '$query' returned no apps" }) {
            runCatching {
                onView(withId(R.id.drawer_panel)).check(matches(hasMinimumChildCount(1)))
            }.isSuccess
        }
    }

    /**
     * Drags drawer row 0 onto the add-to-home bar through the real touch pipeline.
     * Runs on the main thread (Espresso [ViewAction]), so it reads the live
     * [DragLayer.isDragArmed] hook directly to sequence the gesture: DOWN on the
     * row → hold until armed → move up (promotes past slop) into the top strip → UP.
     */
    private class ArmedDrawerDragToHomeBar : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(DragLayer::class.java)
        override fun getDescription() = "arm-confirmed drawer drag onto the add-to-home bar"

        override fun perform(uiController: UiController, view: View) {
            val dragLayer = view as DragLayer
            val panel = view.rootView.findViewById<RecyclerView>(R.id.drawer_panel)
            val row = requireNotNull(panel?.getChildAt(0)) { "drawer has no row 0 to drag" }
            val bar = view.rootView.findViewById<View>(R.id.add_to_home_bar)
            val start = centerOnScreen(row)
            val target = centerOnScreen(bar) // INVISIBLE but laid out → bounds valid

            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float) {
                val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                uiController.injectMotionEvent(e)
                e.recycle()
            }

            send(MotionEvent.ACTION_DOWN, start.first, start.second)
            // Hold (pumping the main thread) until the row's long-press arms the drag —
            // deterministic via the hook instead of a guessed hold.
            val armDeadline = SystemClock.uptimeMillis() + 4_000
            while (!dragLayer.isDragArmed && SystemClock.uptimeMillis() < armDeadline) {
                uiController.loopMainThreadForAtLeast(50)
            }
            check(dragLayer.isDragArmed) { "drawer long-press never armed a drag" }

            // First step past slop promotes the arm to a real drag; the rest carry it
            // into the top add-to-home strip. Drop on the bar.
            val steps = 16
            for (i in 1..steps) {
                val x = start.first + (target.first - start.first) * i / steps
                val y = start.second + (target.second - start.second) * i / steps
                send(MotionEvent.ACTION_MOVE, x, y)
                uiController.loopMainThreadForAtLeast(20)
            }
            send(MotionEvent.ACTION_UP, target.first, target.second)
            uiController.loopMainThreadUntilIdle()
        }

        private fun centerOnScreen(v: View): Pair<Float, Float> {
            val loc = IntArray(2).also(v::getLocationOnScreen)
            return (loc[0] + v.width / 2f) to (loc[1] + v.height / 2f)
        }
    }
}
