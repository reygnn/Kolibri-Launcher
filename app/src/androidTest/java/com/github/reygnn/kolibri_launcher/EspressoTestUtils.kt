package com.github.reygnn.kolibri_launcher

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.matcher.ViewMatchers.assertThat
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import org.hamcrest.Matcher
import org.hamcrest.Matchers.`is`

object EspressoTestUtils {

    /**
     * Eine leere Aktion, die Espresso zwingt, auf den UI-Thread zu warten, bis er idle ist.
     * Das ist extrem nützlich, um auf das Rendern von RecyclerViews zu warten.
     */
    fun waitForUiThread(): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return isDisplayed()
            }

            override fun getDescription(): String {
                return "wait for UI thread to be idle"
            }

            override fun perform(uiController: UiController, view: View) {
                uiController.loopMainThreadUntilIdle()
            }
        }
    }

    class RecyclerViewItemCountAssertion(private val matcher: Matcher<Int>) : ViewAssertion {
        override fun check(view: View?, noViewFoundException: NoMatchingViewException?) {
            if (noViewFoundException != null) {
                throw noViewFoundException
            }
            val recyclerView = view as RecyclerView
            val adapter = recyclerView.adapter
            assertThat(adapter!!.itemCount, matcher)
        }

        companion object {
            fun withItemCount(matcher: Matcher<Int>): RecyclerViewItemCountAssertion {
                return RecyclerViewItemCountAssertion(matcher)
            }
            fun withItemCount(expectedCount: Int): RecyclerViewItemCountAssertion {
                return RecyclerViewItemCountAssertion(`is`(expectedCount))
            }
        }
    }
}