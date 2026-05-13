package com.github.reygnn.kolibri_launcher.ui.home.wallpaperfab

import android.view.View
import androidx.test.core.app.ActivityScenario
import com.github.reygnn.kolibri_launcher.HiltTestActivity
import com.github.reygnn.kolibri_launcher.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for the SpeedDialFabCluster behaviour that
 * `FabPositionMath` + `FabDragHandler` JVM tests cannot reach. Today
 * that is one path: the accessibility entry point on the Save FAB.
 *
 * The cluster's tap handling lives in an `OnTouchListener` that
 * consumes events and never delegates to the FAB's own `onTouchEvent`.
 * TalkBack / Switch Access dispatch via `View.performClick()`, which
 * fires the FAB's `OnClickListener` — so without an explicit click
 * listener those users could never trigger Save. This test pins that
 * the click listener is wired and routes to the same `saveTapListener`
 * the touch path uses.
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
class SpeedDialFabClusterRobolectricTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun `performClick on Save FAB dispatches to saveTapListener (a11y entry point)`() {
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cluster = SpeedDialFabCluster(activity)
                var invocations = 0
                cluster.setOnSaveClicked { invocations++ }

                val fabSave = cluster.findViewById<View>(R.id.fabSave)
                fabSave.performClick()

                assertTrue(
                    "performClick must dispatch to saveTapListener — " +
                        "this is the path TalkBack / Switch Access take.",
                    invocations == 1,
                )
            }
        }
    }

    @Test
    fun `setOnSaveClicked replaces the listener on the existing performClick path`() {
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cluster = SpeedDialFabCluster(activity)
                var firstCalls = 0
                var secondCalls = 0
                cluster.setOnSaveClicked { firstCalls++ }
                cluster.setOnSaveClicked { secondCalls++ }

                val fabSave = cluster.findViewById<View>(R.id.fabSave)
                fabSave.performClick()

                // Late re-binding (e.g. controller swap) must keep the
                // single OnClickListener wired and route to the most
                // recent saveTapListener.
                assertTrue(
                    "Replacing setOnSaveClicked must route subsequent " +
                        "performClick()s to the new listener.",
                    firstCalls == 0 && secondCalls == 1,
                )
            }
        }
    }
}
