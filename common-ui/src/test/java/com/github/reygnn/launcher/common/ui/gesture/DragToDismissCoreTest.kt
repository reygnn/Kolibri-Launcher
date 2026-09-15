package com.github.reygnn.launcher.common.ui.gesture

import android.content.Context
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import androidx.core.view.ViewCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic pin for the drag-to-dismiss WEDGE fix (patch 7), at the level
 * of the shared [DragToDismissCore] so it covers every host (Nyx + Kolibri).
 *
 * Why this test and not only the instrumented one: the wedge is that a STALE
 * `settling` flag survives when the host cancels the settle-back animator on
 * the shared [DragToDismissCore.dragTarget] and ViewPropertyAnimator's
 * withEndAction happens not to run on that cancel. Whether withEndAction runs
 * on cancel is platform/timing dependent, so an instrumented reproduction is
 * inherently flaky and can pass even against the un-fixed code. Here the
 * stranded state is produced deterministically: [dragTarget]'s `animate()` is
 * a mock whose chained builder never invokes the withEndAction runnable, so a
 * genuine below-threshold release leaves `settling` stuck true — exactly the
 * post-cancel state — with zero dependence on real animator semantics.
 *
 * The regression assertion: with `settling` stranded, a fresh gesture start
 * must still be ACCEPTED (the pre-fix `onStartNestedScroll` returned false
 * while settling) and the fling-dismiss path must be live again (the pre-fix
 * `onNestedPreFling` was gated on `!settling`). Both fail against the code
 * before patch 7 and pass after it, independent of any animator behaviour.
 */
class DragToDismissCoreTest {

    private val host = mockk<ViewGroup>(relaxed = true)
    private val dragTarget = mockk<View>(relaxed = true)
    private val listChild = mockk<View>(relaxed = true)
    private val viewConfig = mockk<ViewConfiguration>()
    private val settleAnimator = mockk<ViewPropertyAnimator>(relaxed = true)

    private var dismissCount = 0
    private lateinit var core: DragToDismissCore

    @Before fun setUp() {
        // Constructor reads ViewConfiguration.get(host.context) for the fling
        // threshold; stub it so no real device config is needed.
        mockkStatic(ViewConfiguration::class)
        every { ViewConfiguration.get(any()) } returns viewConfig
        every { viewConfig.scaledMinimumFlingVelocity } returns MIN_FLING_VELOCITY

        every { host.context } returns mockk<Context>(relaxed = true)
        every { host.height } returns HOST_HEIGHT

        // The mock animator's chained builder returns relaxed mocks and its
        // withEndAction runnable is never run — the crux of the stranded state.
        every { dragTarget.animate() } returns settleAnimator
        every { dragTarget.translationY } returns 0f
        every { listChild.canScrollVertically(-1) } returns false // list pinned at top

        core = DragToDismissCore(host).apply {
            dragTarget = this@DragToDismissCoreTest.dragTarget
            onDismiss = { dismissCount++ }
        }
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `a new gesture clears stale settling and re-enables dismiss`() {
        strandSettling()

        // (a) A fresh gesture start must be accepted despite the stranded flag.
        //     Pre-fix this returned false (gated on !settling) → the wedge.
        assertTrue(
            "onStartNestedScroll must accept a new gesture even when a stale " +
                "settling flag is set (wedge regression)",
            core.onStartNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH),
        )

        // (b) The fling-dismiss path must be live again — proof settling cleared.
        //     A fast downward fling with the list pinned at the top dismisses.
        val consumed = core.onNestedPreFling(listChild, velocityY = -FAST_DOWN_VELOCITY)
        assertTrue("A fast downward fling should commit a dismiss after settling clears", consumed)
        assertEquals("Dismiss should fire exactly once", 1, dismissCount)
    }

    @Test fun `onStartNestedScroll is rejected without a dragTarget`() {
        core.dragTarget = null
        assertFalse(
            core.onStartNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH),
        )
    }

    @Test fun `onStartNestedScroll ignores a horizontal axis`() {
        assertFalse(
            core.onStartNestedScroll(ViewCompat.SCROLL_AXIS_HORIZONTAL, ViewCompat.TYPE_TOUCH),
        )
    }

    /**
     * Drives the real code path into a stranded `settling == true`: a short
     * below-threshold downward drag, then release. `onStopNestedScroll` starts
     * the settle-back spring (settling = true) on the mock animator whose
     * withEndAction never runs, so the flag is left stuck exactly as a
     * host-side `container.animate().cancel()` can leave it on a real device.
     */
    private fun strandSettling() {
        assertTrue(core.onStartNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH))
        core.onNestedScrollAccepted()
        // dyUnconsumed is NEGATIVE for a downward pull; 200 px < 0.28 * 1000 = 280
        // px threshold, so the release settles back rather than dismissing.
        core.onNestedScroll(listChild, dyUnconsumed = -BELOW_THRESHOLD_PULL, type = ViewCompat.TYPE_TOUCH, consumed = null)
        core.onStopNestedScroll(ViewCompat.TYPE_TOUCH)

        // Self-guard: the whole stranding trick relies on the settle-back going
        // through dragTarget.animate() (whose mocked withEndAction never runs).
        // If the settle animation is ever reworked off ViewPropertyAnimator
        // (e.g. a SpringAnimation), this fails loudly here instead of the test
        // silently ceasing to reproduce the stranded `settling` state.
        verify { dragTarget.animate() }
    }

    private companion object {
        const val HOST_HEIGHT = 1000
        const val BELOW_THRESHOLD_PULL = 200 // 200 < 0.28 * HOST_HEIGHT (280)
        const val MIN_FLING_VELOCITY = 50    // → flingDismissVelocity = 150 px/s
        const val FAST_DOWN_VELOCITY = 100_000f
    }
}
