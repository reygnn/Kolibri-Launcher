package com.github.reygnn.kolibri_launcher.util

import android.graphics.Rect
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.PerformException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.util.HumanReadables
import androidx.test.espresso.util.TreeIterables
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.util.TestCoroutineRule
import com.google.android.material.chip.Chip
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.Matcher
import org.hamcrest.Matchers
import java.util.concurrent.TimeoutException

object EspressoTestUtils {

    // =================================================================================
    // --- Custom ViewActions ---
    // =================================================================================

    /**
     * WICHTIG für asynchrone UI-Updates (Flows/Coroutines):
     * Wartet bis ein View, der dem Matcher entspricht, in der Hierarchie auftaucht.
     * Zwingend notwendig, wenn Views dynamisch per addView() hinzugefügt werden.
     */
    fun waitForView(matcher: Matcher<View>, timeoutMillis: Long = 5000): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return ViewMatchers.isRoot()
            }

            override fun getDescription(): String {
                return "wait for a specific view with timeout $timeoutMillis ms"
            }

            override fun perform(uiController: UiController, view: View) {
                uiController.loopMainThreadUntilIdle()
                val startTime = System.currentTimeMillis()
                val endTime = startTime + timeoutMillis

                do {
                    try {
                        // Durchsucht den gesamten ViewTree nach dem Matcher
                        val foundViews = TreeIterables.breadthFirstViewTraversal(view)
                            .filter { matcher.matches(it) }

                        if (foundViews.isNotEmpty()) {
                            return // Gefunden!
                        }
                    } catch (e: Throwable) {
                        // Ignorieren und weiter warten
                    }

                    uiController.loopMainThreadForAtLeast(50)
                } while (System.currentTimeMillis() < endTime)

                // Timeout: Exception werfen
                throw PerformException.Builder()
                    .withActionDescription(this.description)
                    .withViewDescription(HumanReadables.describe(view))
                    .withCause(TimeoutException())
                    .build()
            }
        }
    }

    /**
     * Alternative zu scrollTo(), die robuster bei verschachtelten Layouts (ScrollView -> LinearLayout -> Wrapper) ist.
     * Standard scrollTo() verlangt oft direkte Kinder oder strikte Constraints, die hier fehlschlagen.
     */
    fun nestedScrollTo(): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return Matchers.allOf(
                    ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE),
                    ViewMatchers.isDescendantOfA(
                        Matchers.anyOf(
                            ViewMatchers.isAssignableFrom(ScrollView::class.java),
                            ViewMatchers.isAssignableFrom(HorizontalScrollView::class.java),
                            ViewMatchers.isAssignableFrom(NestedScrollView::class.java)
                        )
                    )
                )
            }

            override fun getDescription(): String {
                return "scroll to view in nested hierarchy"
            }

            override fun perform(uiController: UiController, view: View) {
                val rect = Rect()
                view.getDrawingRect(rect)
                // requestRectangleOnScreen triggert das Scrollen der Eltern-Views
                view.requestRectangleOnScreen(rect, true)
                uiController.loopMainThreadUntilIdle()
            }
        }
    }

    fun clickOnChipCloseIcon(): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return ViewMatchers.isAssignableFrom(Chip::class.java)
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
                return ViewMatchers.isDisplayed()
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
                return Matchers.any(View::class.java) // Akzeptiert jede View
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
                return Matchers.any(View::class.java)
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
            ViewMatchers.assertThat(adapter!!.itemCount, matcher)
        }

        companion object {
            fun withItemCount(matcher: Matcher<Int>): RecyclerViewItemCountAssertion {
                return RecyclerViewItemCountAssertion(matcher)
            }
            fun withItemCount(expectedCount: Int): RecyclerViewItemCountAssertion {
                return RecyclerViewItemCountAssertion(Matchers.`is`(expectedCount))
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