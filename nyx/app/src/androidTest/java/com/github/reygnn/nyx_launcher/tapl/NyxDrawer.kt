package com.github.reygnn.nyx_launcher.tapl

import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers.hasMinimumChildCount
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.launcher.testing.BasePage
import com.github.reygnn.launcher.testing.awaitUntil
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
        // replaceText, not typeText: it sets the field content directly (no
        // KeyCharacterMap key synthesis), so a non-ASCII query still enters cleanly
        // while still firing the search field's text-changed listener.
        onView(withId(R.id.search_edit_text)).perform(replaceText(query))
        closeSoftKeyboard()

        // The filtered list loads async; wait until it has a row before acting,
        // or RecyclerViewActions throws.
        awaitUntil(timeoutMs = 10_000, describe = { "drawer search '$query' returned no apps" }) {
            runCatching {
                onView(withId(R.id.drawer_panel)).check(matches(hasMinimumChildCount(1)))
            }.isSuccess
        }
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
}
