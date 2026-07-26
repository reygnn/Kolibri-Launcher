package com.github.reygnn.kolibri_launcher.ui.home

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Robolectric coverage for the ONE piece of [HomeGestureLayout] that is pure
 * decision logic rather than real-device touch feel: a confirmed double tap
 * consumes the whole touch sequence, so neither the long-press nor a
 * directional swipe fires off the same gesture (the `doubleTapFired` flag).
 *
 * Why Robolectric is honest here, when the swipe-velocity path is not (that
 * one stays instrumented — see `androidTest/.../HomeGestureLayoutTest`): the
 * suppression is driven by `GestureDetector`'s double-tap recognition, which
 * keys off `MotionEvent` event times we set exactly, plus the `doubleTapFired`
 * guard that short-circuits the analyzer BEFORE any velocity is computed. None
 * of that needs real elapsed-time deltas — we feed the framework
 * `GestureDetector` deterministic event times and drive its long-press
 * `Handler` message by advancing the paused main looper.
 *
 * The four cases mirror the flag's KDoc verbatim:
 *  - a confirmed double tap fires `onDoubleTap`;
 *  - "tap-tap-flick" does not ALSO dispatch a swipe;
 *  - "tap-tap-hold" does not ALSO fire the long-press;
 *  - the one modal exception: with `onDoubleTap` nulled (wallpaper-edit mode)
 *    the double tap does NOT consume, so the long-press stays free as the exit
 *    gesture.
 */
@RunWith(RobolectricTestRunner::class)
class HomeGestureLayoutDoubleTapRobolectricTest {

    private lateinit var layout: HomeGestureLayout

    private var doubleTaps = 0
    private var longPresses = 0
    private var swipes = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        layout = HomeGestureLayout(context).apply {
            onDoubleTap = { doubleTaps++ }
            onLongPress = { longPresses++ }
            // Any directional swipe counts — the guard must suppress all four.
            onSwipeUp = { swipes++ }
            onSwipeDown = { swipes++ }
            onSwipeLeft = { swipes++ }
            onSwipeRight = { swipes++ }
        }
    }

    // Same tap coordinates for both taps, so distance stays within the
    // double-tap slop; the 90 ms gap between first-up and second-down is
    // inside GestureDetector's [40 ms, 300 ms] double-tap window.
    private val tapX = 200f
    private val tapY = 600f

    /**
     * Feeds the two taps that make a double tap. The first tap's DOWN posts
     * GestureDetector's TAP timeout; leaving the looper paused keeps that
     * message queued, so the second DOWN is recognised as the second tap. Ends
     * on the second tap's DOWN — the caller decides what the held finger does
     * next (release, flick, or hold).
     */
    private fun dispatchDoubleTapUpToSecondDown(base: Long) {
        dispatch(MotionEvent.ACTION_DOWN, downTime = base, eventTime = base, x = tapX, y = tapY)
        dispatch(MotionEvent.ACTION_UP, downTime = base, eventTime = base + 10, x = tapX, y = tapY)
        dispatch(MotionEvent.ACTION_DOWN, downTime = base + 100, eventTime = base + 100, x = tapX, y = tapY)
    }

    private fun dispatch(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            layout.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `a confirmed double tap fires onDoubleTap`() {
        val base = SystemClock.uptimeMillis()
        dispatchDoubleTapUpToSecondDown(base)
        // Release cleanly.
        dispatch(MotionEvent.ACTION_UP, downTime = base + 100, eventTime = base + 110, x = tapX, y = tapY)

        assertEquals("the second tap must be recognised as a double tap", 1, doubleTaps)
    }

    @Test
    fun `the control - the same flick without a double tap DOES dispatch a swipe`() {
        // Control for `tap-tap-flick does not also dispatch a swipe`: proves the
        // flick shape used there is genuinely swipe-worthy, so that test's
        // swipes==0 is real suppression, not a move too weak to trip the analyzer.
        val base = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, downTime = base, eventTime = base, x = tapX, y = tapY)
        dispatch(MotionEvent.ACTION_MOVE, downTime = base, eventTime = base + 16, x = tapX, y = tapY - 600f)

        assertEquals("a fast upward flick must dispatch exactly one swipe", 1, swipes)
        assertEquals("no double tap was performed", 0, doubleTaps)
    }

    @Test
    fun `tap-tap-flick does not also dispatch a swipe`() {
        val base = SystemClock.uptimeMillis()
        dispatchDoubleTapUpToSecondDown(base)

        // A flick off the second tap: 600 px upward in 16 ms → ~37 px/ms, well
        // past the 1.2 px/ms swipe threshold. Absent the doubleTapFired guard
        // the analyzer would fire onSwipeUp here.
        dispatch(MotionEvent.ACTION_MOVE, downTime = base + 100, eventTime = base + 116, x = tapX, y = tapY - 600f)

        assertEquals("the double tap must still have fired", 1, doubleTaps)
        assertEquals("a flick off the double tap must not dispatch a swipe", 0, swipes)
    }

    @Test
    fun `tap-tap-hold does not also fire the long-press`() {
        val base = SystemClock.uptimeMillis()
        dispatchDoubleTapUpToSecondDown(base)

        // Hold the second tap: drive GestureDetector's LONG_PRESS message by
        // advancing the paused looper past the long-press timeout.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals("the double tap must have fired", 1, doubleTaps)
        assertEquals("a hold after a double tap must not fire the long-press", 0, longPresses)
    }

    @Test
    fun `with onDoubleTap nulled the long-press stays free as the exit gesture`() {
        // Wallpaper-edit mode nulls every swipe callback AND onDoubleTap, but
        // keeps onLongPress wired as the way out. A double tap must NOT consume
        // the gesture here, or the exit long-press would be swallowed.
        layout.onDoubleTap = null

        val base = SystemClock.uptimeMillis()
        dispatchDoubleTapUpToSecondDown(base)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals("a nulled double tap must not be counted", 0, doubleTaps)
        assertEquals("the long-press must stay free when double tap is unwired", 1, longPresses)
    }
}
