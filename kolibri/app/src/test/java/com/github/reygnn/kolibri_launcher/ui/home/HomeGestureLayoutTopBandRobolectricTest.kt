package com.github.reygnn.kolibri_launcher.ui.home

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric coverage for the top notification-shade exclusion band in
 * [HomeGestureLayout]: a swipe-DOWN whose ACTION_DOWN lands inside the top band
 * ([com.github.reygnn.launcher.common.ui.gesture.GestureThresholds.TOP_NOTIFICATION_EXCLUSION_DP])
 * is ceded to the system so the recent-apps gesture no longer collides with the
 * user's notification pull-down; every other direction stays live everywhere.
 *
 * Why Robolectric is honest here, not instrumented (same split the sibling
 * [HomeGestureLayoutDoubleTapRobolectricTest] draws): the band decision is a
 * pure coordinate compare — `downY < topExclusionPx` — layered on top of the
 * analyzer whose swipe-velocity FEEL is what the instrumented
 * `androidTest/.../HomeGestureLayoutTest` proves. We feed `MotionEvent`s with
 * deterministic coordinates and event times, so the DOWN result is unambiguous
 * regardless of platform; a real device would pin the same answer (Rule 10's
 * redundancy guardrail — a device test here would only re-check arithmetic).
 * The end-to-end case cannot even be delivered by a real touch: a swipe
 * starting in the top pixels goes to the system status-bar window and never
 * reaches `dispatchTouchEvent` (INSTRUMENTED_TESTING_NOTES rule 11).
 *
 * Coordinates are chosen to be unambiguous across densities: y = 5 px is inside
 * the band for any density ≥ 1 (band = 48 dp), y = 700 px is outside it for any
 * realistic density. The 600 px move over 16 ms is ~37 px/ms — the same
 * swipe-worthy shape the sibling file's control test uses, well past the
 * 1.2 px/ms threshold.
 */
@RunWith(RobolectricTestRunner::class)
class HomeGestureLayoutTopBandRobolectricTest {

    private lateinit var layout: HomeGestureLayout

    private var swipeUps = 0
    private var swipeDowns = 0
    private var swipeLefts = 0
    private var swipeRights = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        layout = HomeGestureLayout(context).apply {
            onSwipeUp = { swipeUps++ }
            onSwipeDown = { swipeDowns++ }
            onSwipeLeft = { swipeLefts++ }
            onSwipeRight = { swipeRights++ }
        }
    }

    private fun dispatch(action: Int, x: Float, y: Float, eventTime: Long) {
        val event = MotionEvent.obtain(BASE, eventTime, action, x, y, 0)
        try {
            layout.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    /**
     * A single down-then-move flick. [downY] decides band membership; the move
     * travels [dx]/[dy] from the down point in 16 ms.
     */
    private fun flick(downX: Float, downY: Float, dx: Float, dy: Float) {
        dispatch(MotionEvent.ACTION_DOWN, x = downX, y = downY, eventTime = BASE)
        dispatch(MotionEvent.ACTION_MOVE, x = downX + dx, y = downY + dy, eventTime = BASE + 16)
    }

    @Test
    fun `a downward flick BELOW the top band dispatches swipeDown`() {
        // Control: proves the flick shape is genuinely swipe-worthy, so the
        // in-band test's swipeDowns == 0 is real suppression, not a weak move.
        flick(downX = 200f, downY = 700f, dx = 0f, dy = 600f)

        assertWithMessage("a downward flick below the band must fire swipeDown").that(swipeDowns).isEqualTo(1)
    }

    @Test
    fun `a downward flick STARTING inside the top band does not dispatch swipeDown`() {
        flick(downX = 200f, downY = 5f, dx = 0f, dy = 600f)

        assertWithMessage("a swipe-down starting in the top band is ceded to the shade").that(swipeDowns).isEqualTo(0)
    }

    @Test
    fun `an upward flick starting inside the top band still dispatches swipeUp`() {
        // Only DOWN is gated — an upward flick from the same in-band origin must
        // still fire, proving the exclusion is scoped to swipe-down alone.
        flick(downX = 200f, downY = 5f, dx = 0f, dy = -600f)

        assertWithMessage("swipeUp from the top band must stay live").that(swipeUps).isEqualTo(1)
        assertWithMessage("no swipeDown must fire for an upward flick").that(swipeDowns).isEqualTo(0)
    }

    @Test
    fun `a horizontal flick starting inside the top band still dispatches a side swipe`() {
        // Horizontal swipes from the top edge are not notification-shade
        // gestures, so they stay live in the band too. Assert the combined
        // horizontal count to stay independent of the analyzer's left/right
        // sign convention.
        flick(downX = 200f, downY = 5f, dx = 600f, dy = 0f)

        assertWithMessage("a horizontal flick from the top band must fire exactly one side swipe").that(swipeLefts + swipeRights).isEqualTo(1)
        assertWithMessage("no swipeDown must fire for a horizontal flick").that(swipeDowns).isEqualTo(0)
    }

    private companion object {
        const val BASE = 1_000L
    }
}
