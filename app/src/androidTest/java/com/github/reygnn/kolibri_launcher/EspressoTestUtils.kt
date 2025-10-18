package com.github.reygnn.kolibri_launcher

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
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.Matcher
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.any

object EspressoTestUtils {

    // =================================================================================
    // --- Custom ViewActions ---
    // =================================================================================

    fun clickOnChipCloseIcon(): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return isAssignableFrom(Chip::class.java)
            }

            override fun getDescription(): String {
                return "Click on the close icon of a Chip."
            }

            override fun perform(uiController: UiController, view: View) {
                val chip = view as Chip
                chip.performCloseIconClick()
            }
        }
    }

    /**
     * Wartet auf den UI-Thread für bereits sichtbare Views.
     * Verwendet isDisplayed() als Constraint.
     */
    fun waitForUiThread(): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return isDisplayed()
            }

            override fun getDescription(): String {
                return "wait for UI thread to be idle (displayed views)"
            }

            override fun perform(uiController: UiController, view: View) {
                uiController.loopMainThreadUntilIdle()
            }
        }
    }

    /**
     * Wartet auf den UI-Thread für beliebige Views (auch unsichtbare).
     * WICHTIG: Nutze diese Version, wenn du auf State-Updates wartest,
     * bei denen Views erst noch erscheinen müssen.
     */
    fun waitForUiThreadAnyView(): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return any(View::class.java) // Akzeptiert jede View
            }

            override fun getDescription(): String {
                return "wait for UI thread to be idle (any view state)"
            }

            override fun perform(uiController: UiController, view: View) {
                uiController.loopMainThreadUntilIdle()
            }
        }
    }

    /**
     * Mehrfaches Warten mit kleinen Pausen.
     * Nützlich für komplexe Animationen oder State-Updates.
     */
    fun waitForUiThreadMultiple(iterations: Int = 2): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return any(View::class.java)
            }

            override fun getDescription(): String {
                return "wait for UI thread multiple times ($iterations iterations)"
            }

            override fun perform(uiController: UiController, view: View) {
                repeat(iterations) {
                    uiController.loopMainThreadUntilIdle()
                    uiController.loopMainThreadForAtLeast(16) // Eine Frame-Zeit
                }
            }
        }
    }

    // =================================================================================
    // --- Custom ViewAssertions ---
    // =================================================================================

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
     * Führt eine robuste, dreistufige Synchronisation durch, um sowohl Coroutinen als auch den
     * Android UI-Thread zu stabilisieren. Dies ist die bevorzugte Methode, um Flakiness in Tests zu
     * beheben, die auf das Beenden einer Activity oder komplexe asynchrone UI-Updates warten.
     *
     * Die Schritte sind:
     * 1. `runCurrent()`: Führt sofort alle anstehenden Coroutinen aus.
     * 2. `advanceUntilIdle()`: Stellt sicher, dass auch alle neu geplanten Coroutinen abgeschlossen sind.
     * 3. `waitForIdleSync()`: Wartet, bis der UI-Thread alle Konsequenzen (z.B. Activity.finish()) verarbeitet hat.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun TestCoroutineRule.awaitAll() {
        this.testDispatcher.scheduler.runCurrent()
        this.testDispatcher.scheduler.advanceUntilIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * DEPRECATED: Thread.sleep sollte vermieden werden.
     * Nutze stattdessen waitForUiThreadAnyView() oder waitForUiThreadMultiple().
     */
    @Deprecated("Use waitForUiThreadAnyView() instead")
    fun waitForUiIdle() {
        Espresso.onIdle()
        Thread.sleep(100)
    }

    @Deprecated("Use waitForUiThreadAnyView() instead")
    fun waitForUiIdleShort() {
        Espresso.onIdle()
        Thread.sleep(50)
    }
}