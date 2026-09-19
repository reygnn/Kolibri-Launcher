package com.github.reygnn.kolibri_launcher.tapl

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.testing.awaitUntil

/**
 * The layout-customization dialog (`LayoutCustomizationDialogFragment`,
 * anchored on R.id.slider_text_size). Shares `LauncherViewModel` with the host
 * activity (activityViewModels), so a slider change previews live on Home
 * behind it.
 *
 * The dialog is a transparent, dim-less bottom-sheet-style window, so its views
 * are matched with `inRoot(isDialog())` rather than the default root (which
 * stays on the Activity behind it).
 *
 * The text-size slider only forwards to `viewModel.onSetLayoutScale` when
 * `fromUser` is true, so a programmatic value-set does NOT propagate — only a
 * real touch drag does. That is precisely why the live-preview path needs a
 * device, and why this uses a genuine [GeneralSwipeAction] rather than setting
 * the slider value.
 */
internal class LayoutCustomization {

    init {
        awaitUntil(
            timeoutMs = 5_000,
            describe = { "layout dialog's text-size slider never displayed" },
        ) {
            runCatching {
                onView(withId(R.id.slider_text_size))
                    .inRoot(isDialog())
                    .check(matches(isDisplayed()))
            }.isSuccess
        }
    }

    /**
     * Drags the text-size slider left-edge → right-edge with a real finger
     * swipe, driving the value up toward LAYOUT_SCALE_MAX (fromUser = true).
     */
    fun dragTextSizeUp() {
        onView(withId(R.id.slider_text_size))
            .inRoot(isDialog())
            .perform(
                GeneralSwipeAction(
                    Swipe.SLOW,
                    GeneralLocation.CENTER_LEFT,
                    GeneralLocation.CENTER_RIGHT,
                    Press.FINGER,
                )
            )
    }
}
