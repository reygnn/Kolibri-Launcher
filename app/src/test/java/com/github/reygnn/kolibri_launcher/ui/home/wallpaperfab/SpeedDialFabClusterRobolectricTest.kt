package com.github.reygnn.kolibri_launcher.ui.home.wallpaperfab

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import com.github.reygnn.kolibri_launcher.HiltTestActivity
import com.github.reygnn.kolibri_launcher.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
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

    // ============================================================
    // RELAYOUT-REAPPLY COVERAGE
    //
    // These tests pin the non-trivial part of the position-drift fix:
    // applyPosition stores the fractions, an OnLayoutChangeListener
    // re-runs the math on every layout pass that actually relocates
    // the cluster. Without this guard, a parent-size change (rotation,
    // inset dispatch, fold-state) would leave the cluster displaced by
    // exactly the delta between old and new `left` / `top`.
    // ============================================================

    /**
     * Builds a parent FrameLayout + child cluster sized to a known
     * geometry. Returns both; the caller drives layout passes by
     * calling `cluster.layout(l, t, r, b)` directly — `View.layout(...)`
     * is what fires the OnLayoutChangeListener inside the cluster.
     */
    private fun buildParentedCluster(
        activity: HiltTestActivity,
        parentSize: Int = PARENT_SIZE,
        clusterSize: Int = CLUSTER_SIZE,
    ): Pair<FrameLayout, SpeedDialFabCluster> {
        val parent = FrameLayout(activity)
        parent.layout(0, 0, parentSize, parentSize)
        val cluster = SpeedDialFabCluster(activity)
        parent.addView(
            cluster,
            ViewGroup.LayoutParams(clusterSize, clusterSize),
        )
        // Position the cluster at bottom|end of the parent (matching
        // the production layout_gravity).
        cluster.layout(
            parentSize - clusterSize,
            parentSize - clusterSize,
            parentSize,
            parentSize,
        )
        return parent to cluster
    }

    @Test
    fun `applyPosition centers the cluster after the first layout pass`() {
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val (_, cluster) = buildParentedCluster(activity)

                cluster.applyPosition(0.5f, 0.5f)
                // applyPosition defers to the next layout-pass via
                // doOnLayout — trigger one with the same corners so the
                // deferred apply runs.
                cluster.layout(
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE,
                    PARENT_SIZE,
                )

                // Center at parentW/2 = 500, cluster width = 200,
                // top-left = 400. Same for Y.
                val expected = (PARENT_SIZE - CLUSTER_SIZE) / 2f
                assertEquals(expected, cluster.x, 1f)
                assertEquals(expected, cluster.y, 1f)
            }
        }
    }

    @Test
    fun `OnLayoutChangeListener re-applies the cached fraction on a relayout`() {
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val (_, cluster) = buildParentedCluster(activity)
                cluster.applyPosition(0.5f, 0.5f)
                cluster.layout(
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE,
                    PARENT_SIZE,
                )

                // Sanity: deferred apply set x to the centered value.
                val centered = (PARENT_SIZE - CLUSTER_SIZE) / 2f
                assertEquals(centered, cluster.x, 1f)

                // Simulate a layout-pass that moves the cluster's slot
                // (corners differ from old) — this is what rotation /
                // inset dispatch would trigger under bottom|end gravity.
                // The listener must re-run applyPositionImmediate and
                // restore the centered position.
                cluster.x = 0f
                cluster.y = 0f
                cluster.layout(
                    PARENT_SIZE - CLUSTER_SIZE - 1,
                    PARENT_SIZE - CLUSTER_SIZE - 1,
                    PARENT_SIZE - 1,
                    PARENT_SIZE - 1,
                )

                assertEquals(
                    "Layout-pass with different corners must re-apply",
                    centered,
                    cluster.x,
                    1f,
                )
                assertEquals(centered, cluster.y, 1f)
            }
        }
    }

    @Test
    fun `OnLayoutChangeListener skips re-apply when corners are unchanged`() {
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val (_, cluster) = buildParentedCluster(activity)
                cluster.applyPosition(0.5f, 0.5f)
                cluster.layout(
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE,
                    PARENT_SIZE,
                )

                // Now scribble over x and re-layout with the SAME
                // corners. The diff-guard inside the listener must
                // skip applyPositionImmediate, leaving our scribble in
                // place — proves the guard isn't a paranoia no-op.
                cluster.x = 42f
                cluster.layout(
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE,
                    PARENT_SIZE,
                )

                assertEquals(
                    "Layout-pass with identical corners must NOT re-apply",
                    42f,
                    cluster.x,
                    0.01f,
                )
            }
        }
    }

    @Test
    fun `drag-end caches new fraction so a later relayout does not snap back`() {
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val (_, cluster) = buildParentedCluster(activity)
                cluster.applyPosition(0.5f, 0.5f)
                cluster.layout(
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE,
                    PARENT_SIZE,
                )

                // Simulate a drag-end at a different position. The view
                // ends up at (100, 100); the cluster updates its cached
                // fraction internally before invoking onPositionChanged.
                // We trigger that path by setting x/y and asking the
                // public onPositionChanged callback to drive the math —
                // but the touch dispatch is private, so we instead
                // simulate the post-condition: x/y at a new location,
                // then an external relayout. If the cache wasn't
                // synced, the listener would snap back to (400, 400).
                val draggedX = 100f
                val draggedY = 100f
                cluster.x = draggedX
                cluster.y = draggedY
                // applyPosition with the NEW fraction reflects what the
                // touch handler does at drag-end (cache update +
                // listener invoke). This is the public surface for the
                // same effect.
                val newFraction = (draggedX + CLUSTER_SIZE / 2f) / PARENT_SIZE
                cluster.applyPosition(newFraction, newFraction)
                cluster.layout(
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE,
                    PARENT_SIZE,
                )

                // External relayout with different corners — must
                // restore the dragged position, not the original 0.5
                // center.
                cluster.layout(
                    PARENT_SIZE - CLUSTER_SIZE - 1,
                    PARENT_SIZE - CLUSTER_SIZE - 1,
                    PARENT_SIZE - 1,
                    PARENT_SIZE - 1,
                )

                assertEquals(draggedX, cluster.x, 1f)
                assertEquals(draggedY, cluster.y, 1f)
            }
        }
    }

    private companion object {
        const val PARENT_SIZE = 1000
        const val CLUSTER_SIZE = 200
    }
}
