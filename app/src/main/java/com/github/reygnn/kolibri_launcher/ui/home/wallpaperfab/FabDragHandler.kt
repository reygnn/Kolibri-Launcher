package com.github.reygnn.kolibri_launcher.ui.home.wallpaperfab

import kotlin.math.abs

/**
 * Touch-state machine for a draggable FAB, kept Android-free so it
 * can be unit-tested on the JVM. The view layer feeds it ACTION_DOWN /
 * ACTION_MOVE / ACTION_UP coordinates (typically `MotionEvent.rawX`/
 * `rawY`) and reads the cumulative drag delta back.
 *
 * Behaviour:
 *   - Slop guard: a movement under [touchSlopPx] is not considered a
 *     drag. The first MOVE past slop flips `isDragging` to `true`,
 *     and from that point on every MOVE returns a delta.
 *   - End classification: [onUp] reports whether the gesture was a
 *     drag (so the caller knows to persist the new position) or a tap
 *     (so the caller knows to handle it as a click).
 *
 * The handler is single-gesture: a new [onDown] resets state. No
 * multi-touch handling — the FAB is a single-finger control and the
 * caller is expected to forward only the primary pointer's events.
 */
internal class FabDragHandler(private val touchSlopPx: Int) {

    private var startX: Float = 0f
    private var startY: Float = 0f
    private var draggingInternal: Boolean = false

    val isDragging: Boolean get() = draggingInternal

    fun onDown(rawX: Float, rawY: Float) {
        startX = rawX
        startY = rawY
        draggingInternal = false
    }

    /**
     * Returns the cumulative drag delta from the start of the gesture,
     * or `null` if the gesture has not yet crossed the touch-slop
     * threshold. Once slop is crossed, every subsequent move returns
     * a non-null delta, even if the absolute movement shrinks back
     * below slop — drag, once started, sticks.
     */
    fun onMove(rawX: Float, rawY: Float): Delta? {
        val dx = rawX - startX
        val dy = rawY - startY
        if (!draggingInternal && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
            draggingInternal = true
        }
        return if (draggingInternal) Delta(dx, dy) else null
    }

    fun onUp(): EndState = if (draggingInternal) EndState.Drag else EndState.Tap

    /** Cumulative drag offset from the gesture's start point. */
    data class Delta(val dx: Float, val dy: Float)

    enum class EndState { Drag, Tap }
}
