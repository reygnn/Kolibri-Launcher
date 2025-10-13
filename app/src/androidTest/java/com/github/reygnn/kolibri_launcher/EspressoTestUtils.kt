package com.github.reygnn.kolibri_launcher // or a shared test utility package

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.matcher.ViewMatchers.assertThat
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import com.google.android.material.chip.Chip
import org.hamcrest.Matcher
import org.hamcrest.Matchers.`is`

object EspressoTestUtils {

    // =================================================================================
    // --- Custom ViewActions ---
    // =================================================================================

    /**
     * Eine benutzerdefinierte ViewAction, die den Klick auf das Schließen-Icon eines
     * com.google.android.material.chip.Chip simuliert.
     */
    fun clickOnChipCloseIcon(): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                // Stellt sicher, dass diese Aktion nur auf Chip-Widgets angewendet wird.
                return isAssignableFrom(Chip::class.java)
            }

            override fun getDescription(): String {
                return "Click on the close icon of a Chip."
            }

            override fun perform(uiController: UiController, view: View) {
                val chip = view as Chip
                // Ruft die interne Methode auf, die der OnClickListener des Icons auslösen würde.
                chip.performCloseIconClick()
            }
        }
    }

    /**
     * Eine leere Aktion, die Espresso zwingt, auf den UI-Thread zu warten, bis er idle ist.
     * Das ist nützlich, um auf das Rendern von RecyclerViews zu warten.
     * HINWEIS: Oft nicht notwendig, da Espresso dies automatisch tut.
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

    // =================================================================================
    // --- Custom ViewAssertions ---
    // =================================================================================

    /**
     * Eine ViewAssertion, die die Anzahl der Elemente in einem RecyclerView überprüft.
     */
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

    // =================================================================================
    // --- Synchronization / Waiting Helpers ---
    // =================================================================================

    /**
     * Wartet, bis Espresso den UI-Thread als "idle" betrachtet.
     * ACHTUNG: Die Verwendung von Thread.sleep() sollte vermieden werden, da es Tests
     * verlangsamt und instabil ("flaky") machen kann. Bevorzugen Sie Idling Resources.
     */
    fun waitForUiIdle() {
        Espresso.onIdle()
        Thread.sleep(100) // Nur als letzter Ausweg verwenden
    }

    fun waitForUiIdleShort() {
        Espresso.onIdle()
        Thread.sleep(50) // Nur als letzter Ausweg verwenden
    }
}