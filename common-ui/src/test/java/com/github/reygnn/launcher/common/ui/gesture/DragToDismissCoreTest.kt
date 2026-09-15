package com.github.reygnn.launcher.common.ui.gesture

import android.animation.Animator
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
 * JVM pins for the shared [DragToDismissCore], covering every host (Nyx +
 * Kolibri). Beyond the original WEDGE fix (patch 7, below), it also pins the
 * gesture-decision branches that the instrumented suite cannot drive
 * deterministically: distance-release dismiss, the upward-flick CANCEL of a
 * past-threshold drag, the at-top fling guard (a downward flick that only
 * scrolls the list must never dismiss), and the pre-scroll re-collapse
 * accounting.
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

    @Test fun `a slow drag past the threshold dismisses on release`() {
        // The primary "pull past the threshold and let go" gesture — distinct
        // from the fling shortcut. Pull PAST 0.28*height, then release.
        assertTrue(core.onStartNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH))
        core.onNestedScrollAccepted()
        core.onNestedScroll(listChild, dyUnconsumed = -PAST_THRESHOLD_PULL, type = ViewCompat.TYPE_TOUCH, consumed = null)
        core.onStopNestedScroll(ViewCompat.TYPE_TOUCH)
        assertEquals("A release past the distance threshold dismisses", 1, dismissCount)
    }

    @Test fun `a decisive upward flick cancels a past-threshold drag instead of dismissing`() {
        // Pull PAST the threshold, then flick UP to abort. Must settle back, not
        // dismiss — onStopNestedScroll then no-ops because settling is set.
        assertTrue(core.onStartNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH))
        core.onNestedScrollAccepted()
        core.onNestedScroll(listChild, dyUnconsumed = -PAST_THRESHOLD_PULL, type = ViewCompat.TYPE_TOUCH, consumed = null)

        val consumed = core.onNestedPreFling(listChild, velocityY = FAST_UP_VELOCITY)
        assertTrue("An upward cancel flick must consume the fling", consumed)

        core.onStopNestedScroll(ViewCompat.TYPE_TOUCH)
        assertEquals("An upward cancel flick must NOT dismiss, even past threshold", 0, dismissCount)
        verify { dragTarget.animate() } // settle-back was started
    }

    @Test fun `a fast downward flick that scrolls the list up must not dismiss`() {
        // The core safety guarantee: with the list still scrollable up and no
        // active drag, a hard finger-down flick means "scroll the list", never
        // "dismiss". Pre-guard this against a regression that drops the at-top check.
        every { listChild.canScrollVertically(-1) } returns true // list NOT at top

        assertTrue(core.onStartNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH))
        val consumed = core.onNestedPreFling(listChild, velocityY = -FAST_DOWN_VELOCITY)
        assertFalse("A downward fling while the list can still scroll up must not be consumed", consumed)
        assertEquals("No dismiss while the list is not at its top", 0, dismissCount)
    }

    @Test fun `preScroll re-collapses the pulled sheet and reports what it consumed`() {
        // Finger moving UP while the sheet is pulled down must spend the delta on
        // re-collapsing (before the list scrolls), clamped to the available offset,
        // and report exactly that via consumed[1] so the nested-scroll chain stays
        // consistent.
        assertTrue(core.onStartNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH))
        core.onNestedScrollAccepted()
        core.onNestedScroll(listChild, dyUnconsumed = -BELOW_THRESHOLD_PULL, type = ViewCompat.TYPE_TOUCH, consumed = null)

        // Over-consume: dy (250) exceeds the current 200 px offset → clamp to 200.
        val consumed = intArrayOf(0, 0)
        core.onNestedPreScroll(dy = BELOW_THRESHOLD_PULL + 50, consumed = consumed, type = ViewCompat.TYPE_TOUCH)
        assertEquals("Re-collapse consumes only the available offset", BELOW_THRESHOLD_PULL, consumed[1])

        // Fully collapsed now: a release neither dismisses nor settles.
        core.onStopNestedScroll(ViewCompat.TYPE_TOUCH)
        assertEquals("A fully re-collapsed sheet does not dismiss on release", 0, dismissCount)
    }

    @Test fun `settle-back end detaches the shared-VPA listeners and resets state`() {
        // Positive pin for the shared-VPA fix: on natural end, animateSettleBack's
        // AnimatorListener MUST detach both the update listener and itself from
        // dragTarget's ViewPropertyAnimator (which the host also animates on
        // show/hide) and reset settling/dragOffset. The relaxed mock never fires
        // the listener on its own, so capture it and invoke onAnimationEnd here —
        // otherwise dropping the detach/reset would leave every test green.
        //
        // The relaxed builder returns a fresh mock per chained call, so stub the
        // chain to return the same animator and record the installed listener.
        val installedListeners = mutableListOf<Animator.AnimatorListener?>()
        every { settleAnimator.translationY(any()) } returns settleAnimator
        every { settleAnimator.setDuration(any()) } returns settleAnimator
        every { settleAnimator.setInterpolator(any()) } returns settleAnimator
        every { settleAnimator.setUpdateListener(any()) } returns settleAnimator
        every { settleAnimator.setListener(any()) } answers { installedListeners.add(firstArg()); settleAnimator }

        // Below-threshold pull + release → animateSettleBack() (settling = true).
        assertTrue(core.onStartNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH))
        core.onNestedScrollAccepted()
        core.onNestedScroll(listChild, dyUnconsumed = -BELOW_THRESHOLD_PULL, type = ViewCompat.TYPE_TOUCH, consumed = null)
        core.onStopNestedScroll(ViewCompat.TYPE_TOUCH)

        val listener = installedListeners.firstOrNull { it != null }
            ?: error("animateSettleBack must install an AnimatorListener on the settle animator")

        listener.onAnimationEnd(mockk(relaxed = true))

        // Both shared-VPA listeners detached (else the update listener keeps
        // firing on the host's later slides and corrupts dragOffset).
        verify { settleAnimator.setUpdateListener(null) }
        verify { settleAnimator.setListener(null) }

        // settling cleared + dragOffset reset to 0 — proven by a fresh fast
        // downward fling now committing a dismiss (a stuck `settling` would gate it).
        assertTrue(
            "After settle-back end, settling must be cleared so a new fling can dismiss",
            core.onNestedPreFling(listChild, velocityY = -FAST_DOWN_VELOCITY),
        )
        assertEquals(1, dismissCount)
    }

    @Test fun `a target disarmed mid-drag does not strand dragOffset into the next open`() {
        // A host hide (BACK / launch) can null dragTarget during an active pull,
        // before the finger lifts. onStopNestedScroll then settles with no target;
        // the drag state MUST reset, or the stranded offset carries into the next
        // arm and can cross the dismiss threshold on the first pull.
        assertTrue(core.onStartNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH))
        core.onNestedScrollAccepted()
        core.onNestedScroll(listChild, dyUnconsumed = -BELOW_THRESHOLD_PULL, type = ViewCompat.TYPE_TOUCH, consumed = null)

        core.dragTarget = null // disarmed mid-drag by a host hide
        core.onStopNestedScroll(ViewCompat.TYPE_TOUCH) // settle with no target → must reset

        // Re-arm (next open) and do a fresh sub-threshold pull. Without the reset,
        // the stranded 200px + this 100px would exceed the 280px threshold and
        // dismiss the freshly-opened drawer.
        core.dragTarget = dragTarget
        assertTrue(core.onStartNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH))
        core.onNestedScrollAccepted()
        core.onNestedScroll(listChild, dyUnconsumed = -SMALL_PULL, type = ViewCompat.TYPE_TOUCH, consumed = null)
        core.onStopNestedScroll(ViewCompat.TYPE_TOUCH)
        assertEquals("A disarm-mid-drag must not strand dragOffset into the next open", 0, dismissCount)
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
        const val SMALL_PULL = 100           // 200 (stranded) + 100 would exceed 280
        const val PAST_THRESHOLD_PULL = 300  // 300 > 0.28 * HOST_HEIGHT (280)
        const val MIN_FLING_VELOCITY = 50    // → flingDismissVelocity = 150 px/s
        const val FAST_DOWN_VELOCITY = 100_000f
        const val FAST_UP_VELOCITY = 100_000f // finger-up (velocityY > 0)
    }
}
