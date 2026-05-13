package com.github.reygnn.kolibri_launcher.ui.home.wallpaperfab

import android.os.SystemClock
import android.view.MotionEvent
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

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

                // To honestly test the diff-guard, the listener must
                // actually run with identical corners — otherwise the
                // assertion below passes simply because `View.setFrame`
                // short-circuited and `View.layout` never dispatched a
                // single listener call. Two pieces are needed:
                //
                //   1. A separate counter listener to prove dispatch
                //      happened at all (the guard inside the cluster's
                //      own listener is what we're testing — we can't
                //      use it as evidence).
                //   2. `requestLayout()` + `measure()` to set
                //      `PFLAG_LAYOUT_REQUIRED`. `requestLayout()` alone
                //      only sets `PFLAG_FORCE_LAYOUT`; the listener-
                //      dispatch check in `View.layout` reads
                //      `PFLAG_LAYOUT_REQUIRED`, which is set during
                //      `View.measure`. Skipping `measure()` would
                //      leave the dispatch short-circuited even with
                //      `requestLayout()`.
                val listenerInvocations = AtomicInteger(0)
                cluster.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    listenerInvocations.incrementAndGet()
                }

                cluster.x = 42f
                cluster.requestLayout()
                cluster.measure(
                    View.MeasureSpec.makeMeasureSpec(CLUSTER_SIZE, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(CLUSTER_SIZE, View.MeasureSpec.EXACTLY),
                )
                cluster.layout(
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE - CLUSTER_SIZE,
                    PARENT_SIZE,
                    PARENT_SIZE,
                )

                assertTrue(
                    "Counter listener must have fired — otherwise the " +
                        "diff-guard inside the cluster's listener was " +
                        "never reached, and the assertion below would " +
                        "pass for the wrong reason.",
                    listenerInvocations.get() > 0,
                )
                assertEquals(
                    "Diff-guard must short-circuit on identical corners " +
                        "and leave the scribbled x untouched.",
                    42f,
                    cluster.x,
                    0.01f,
                )
            }
        }
    }

    @Test
    fun `drag-end synchronously caches the new fraction so a later relayout does not snap back`() {
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
                val centered = (PARENT_SIZE - CLUSTER_SIZE) / 2f
                assertEquals(centered, cluster.x, 1f)

                // Drive the actual touch state machine: this is the
                // path that contains the `lastXFraction = xFrac`
                // synchronous cache write inside ACTION_UP. Dragging
                // via dispatchTouchEvent on the save FAB exercises
                // that branch — simulating the post-condition via
                // applyPosition() would bypass it. The drag goes from
                // (0, 0) raw to (DRAG_DELTA, DRAG_DELTA) raw, well past
                // the touch slop on any device profile.
                val fabSave = cluster.findViewById<View>(R.id.fabSave)
                val downTime = SystemClock.uptimeMillis()
                dispatchTouch(fabSave, downTime, downTime, MotionEvent.ACTION_DOWN, 0f, 0f)
                dispatchTouch(fabSave, downTime, downTime + 50, MotionEvent.ACTION_MOVE, DRAG_DELTA, DRAG_DELTA)
                dispatchTouch(fabSave, downTime, downTime + 100, MotionEvent.ACTION_UP, DRAG_DELTA, DRAG_DELTA)

                // ACTION_UP wrote the new fraction synchronously. The
                // drag clamps against parentSize - clusterSize = 800.
                val draggedX = cluster.x
                assertNotEquals(
                    "Drag must have moved the cluster off centre — " +
                        "otherwise the rest of this test is meaningless.",
                    centered,
                    draggedX,
                )

                // External relayout with shifted corners forces the
                // OnLayoutChangeListener to fire — applyPositionImmediate
                // re-runs against the cache. If ACTION_UP failed to
                // update the cache, x would snap back to `centered`
                // (the pre-drag fraction 0.5 → 400).
                cluster.layout(
                    PARENT_SIZE - CLUSTER_SIZE - 1,
                    PARENT_SIZE - CLUSTER_SIZE - 1,
                    PARENT_SIZE - 1,
                    PARENT_SIZE - 1,
                )

                assertEquals(
                    "Cache must reflect the drag end-point, not the " +
                        "pre-drag fraction.",
                    draggedX,
                    cluster.x,
                    1f,
                )
            }
        }
    }

    /**
     * Builds and dispatches a single [MotionEvent]; recycles after
     * dispatch so the obtain-pool doesn't leak across the test method.
     * `MotionEvent.obtain(downTime, eventTime, action, x, y, meta)`
     * sets `rawX = x` / `rawY = y`, which is what [FabDragHandler]
     * reads — so passing screen-space coordinates here is fine.
     */
    private fun dispatchTouch(
        target: View,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            target.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val PARENT_SIZE = 1000
        const val CLUSTER_SIZE = 200

        /**
         * Drag distance in raw pixels — large enough to clear the
         * default `ViewConfiguration.scaledTouchSlop` (16–24 px on
         * Robolectric) on every device profile, and large enough to
         * land outside the centred starting position so the test can
         * tell the dragged x from the centred x.
         */
        const val DRAG_DELTA = 300f
    }
}
